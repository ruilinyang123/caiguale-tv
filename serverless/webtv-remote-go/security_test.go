package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestBindingRotationAndClaimRateLimit(t *testing.T) {
	s := testService(t)
	id, token := register(t, s, "TV")
	credentials := map[string]any{"deviceId": id, "deviceToken": token}
	_, first := call(t, s, "POST", "/api/device/bind-code", credentials, nil)
	_, second := call(t, s, "POST", "/api/device/bind-code", credentials, nil)
	if first["code"] == second["code"] {
		t.Fatal("binding rotation reused code")
	}
	if status, _ := call(t, s, "POST", "/api/groups/claim", map[string]any{"code": first["code"]}, nil); status != 404 {
		t.Fatal("rotated code still valid", status)
	}
	for i := 1; i < 10; i++ {
		if status, _ := call(t, s, "POST", "/api/groups/claim", map[string]any{"code": "invalid"}, nil); status != 404 {
			t.Fatalf("attempt %d: %d", i, status)
		}
	}
	if status, _ := call(t, s, "POST", "/api/groups/claim", map[string]any{"code": second["code"]}, nil); status != 429 {
		t.Fatal("claim not throttled", status)
	}
}

func TestGroupRevocationLeavesOtherGroupsAndIdentityIntact(t *testing.T) {
	s := testService(t)
	id, token := register(t, s, "TV")
	gid, gt, _ := pair(t, s, id, token)
	_, other, _ := pair(t, s, id, token)
	cid, _ := enqueue(t, s, gt, id, "device.status", map[string]any{}, "revoke-group-command")
	call(t, s, "POST", "/api/device/poll", map[string]any{}, deviceHeaders(id, token))
	if status, _ := call(t, s, "DELETE", "/api/devices/"+id, nil, map[string]string{"Authorization": "Bearer " + gt}); status != 200 {
		t.Fatal(status)
	}
	if status, out := call(t, s, "POST", "/api/device/poll", map[string]any{}, deviceHeaders(id, token)); status != 200 || out["command"] != nil {
		t.Fatal(status, out)
	}
	if status, _ := call(t, s, "POST", "/api/commands/"+cid+"/result", map[string]any{"ok": true, "result": map[string]any{}}, deviceHeaders(id, token)); status != 409 {
		t.Fatal("revoked result accepted", status)
	}
	if status, _ := call(t, s, "GET", "/api/commands/"+cid, nil, map[string]string{"Authorization": "Bearer " + gt}); status != 404 {
		t.Fatal("revoked command readable", status)
	}
	enqueue(t, s, other, id, "device.status", map[string]any{}, "other-group-survives")
	if s.disk.Groups[gid].Devices[id] {
		t.Fatal("revoked membership retained")
	}
	// No client-supplied groups may create implicit authorizations.
	if status, _ := call(t, s, "POST", "/api/device/register", map[string]any{"deviceId": id, "deviceToken": token, "groupIds": []string{gid}}, nil); status != 400 {
		t.Fatal("self authorized registration", status)
	}
}

func TestRestartPreservesAuthorizationButDropsTemporaryState(t *testing.T) {
	s := testService(t)
	id, token := register(t, s, "TV")
	_, gt, _ := pair(t, s, id, token)
	cid, _ := enqueue(t, s, gt, id, "action.control", map[string]any{"action": "next"}, "restart-command")
	_, bind := call(t, s, "POST", "/api/device/bind-code", map[string]any{}, deviceHeaders(id, token))
	data, err := os.ReadFile(s.path)
	if err != nil {
		t.Fatal(err)
	}
	for _, secret := range []string{token, gt, cid, bind["code"].(string)} {
		if strings.Contains(string(data), secret) {
			t.Fatal("raw token or temporary data persisted")
		}
	}
	next, err := newService(s.path, s.origin)
	if err != nil {
		t.Fatal(err)
	}
	if status, out := call(t, next, "POST", "/api/device/poll", map[string]any{}, deviceHeaders(id, token)); status != 200 || out["command"] != nil {
		t.Fatal(status, out)
	}
	if status, out := call(t, next, "GET", "/api/devices", nil, map[string]string{"Authorization": "Bearer " + gt}); status != 200 || len(out["devices"].([]any)) != 1 {
		t.Fatal(status, out)
	}
	if status, _ := call(t, next, "GET", "/api/commands/"+cid, nil, map[string]string{"Authorization": "Bearer " + gt}); status != 404 {
		t.Fatal("command survived restart", status)
	}
	if status, _ := call(t, next, "POST", "/api/groups/claim", map[string]any{"code": bind["code"]}, nil); status != 404 {
		t.Fatal("binding survived restart", status)
	}
}

func TestIdempotencyConflictAndResultsCannotBeOverwritten(t *testing.T) {
	s := testService(t)
	id, token := register(t, s, "TV")
	_, gt, _ := pair(t, s, id, token)
	cid, _ := enqueue(t, s, gt, id, "device.status", map[string]any{}, "stable-idempotency")
	if status, _ := call(t, s, "POST", "/api/commands", map[string]any{"targetDeviceId": id, "type": "action.control", "payload": map[string]any{"action": "play"}, "idempotencyKey": "stable-idempotency"}, map[string]string{"Authorization": "Bearer " + gt}); status != 409 {
		t.Fatal(status)
	}
	call(t, s, "POST", "/api/device/poll", map[string]any{}, deviceHeaders(id, token))
	for _, value := range []string{"first", "second"} {
		if status, _ := call(t, s, "POST", "/api/commands/"+cid+"/result", map[string]any{"ok": true, "result": map[string]any{"value": value}}, deviceHeaders(id, token)); status != 200 {
			t.Fatal(status)
		}
	}
	if s.commands[cid].Result["value"] != "first" {
		t.Fatal("duplicate result overwrote original")
	}
}

func TestHTTPBoundariesAndTokenIsolation(t *testing.T) {
	s := testService(t)
	cases := []struct {
		body, ct string
		status   int
	}{
		{`{"name":"TV","extra":1}`, "application/json", 400},
		{`{} {}`, "application/json", 400},
		{`{}`, "text/plain", 415},
		{`{"name":"` + strings.Repeat("x", maxBody) + `"}`, "application/json", 413},
	}
	for _, tc := range cases {
		r := httptest.NewRequest("POST", "/api/device/register", strings.NewReader(tc.body))
		r.Header.Set("Content-Type", tc.ct)
		w := httptest.NewRecorder()
		s.ServeHTTP(w, r)
		if w.Code != tc.status {
			t.Errorf("HTTP boundary expected %d got %d", tc.status, w.Code)
		}
	}
	if status, _ := call(t, s, "GET", "/api/health?deviceToken=secret", nil, nil); status != 400 {
		t.Fatal(status)
	}
	if status, _ := call(t, s, "GET", "/api/health", nil, map[string]string{"Origin": "https://hostile.example"}); status != 403 {
		t.Fatal(status)
	}
	id, token := register(t, s, "TV")
	_, gt, _ := pair(t, s, id, token)
	if status, _ := call(t, s, "POST", "/api/device/poll", map[string]any{"deviceId": id}, map[string]string{"Authorization": "Bearer " + gt}); status != 401 {
		t.Fatal("group token impersonated device", status)
	}
	if status, _ := call(t, s, "GET", "/api/devices", nil, deviceHeaders(id, token)); status != 401 {
		t.Fatal("device token impersonated group", status)
	}
	for _, u := range []string{"file:///etc/passwd", "intent://x", "javascript:alert(1)", "https://user:pass@example.com", "https://example.com\nX: y"} {
		if err := validateCommand(request{Type: "action.push", Payload: map[string]any{"url": u}}); err == nil {
			t.Error("unsafe URL accepted", u)
		}
	}
}

func TestConcurrentClaimConsumesCodeOnce(t *testing.T) {
	s := testService(t)
	id, token := register(t, s, "TV")
	_, bind := call(t, s, "POST", "/api/device/bind-code", map[string]any{}, deviceHeaders(id, token))
	var wg sync.WaitGroup
	statuses := make(chan int, 8)
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			status, _ := call(t, s, "POST", "/api/groups/claim", map[string]any{"code": bind["code"]}, nil)
			statuses <- status
		}()
	}
	wg.Wait()
	close(statuses)
	success := 0
	for status := range statuses {
		if status == 200 {
			success++
		} else if status != 404 {
			t.Error(status)
		}
	}
	if success != 1 || len(s.disk.Groups) != 1 {
		t.Fatalf("claim succeeded %d times", success)
	}
}

func TestConcurrentPollDeliversOnlyOnce(t *testing.T) {
	s := testService(t)
	id, token := register(t, s, "TV")
	_, gt, _ := pair(t, s, id, token)
	enqueue(t, s, gt, id, "device.status", map[string]any{}, "parallel-poll")
	var wg sync.WaitGroup
	received := make(chan bool, 8)
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			status, out := call(t, s, "POST", "/api/device/poll", map[string]any{}, deviceHeaders(id, token))
			if status != 200 {
				t.Errorf("poll: %d", status)
			}
			received <- out["command"] != nil
		}()
	}
	wg.Wait()
	close(received)
	n := 0
	for v := range received {
		if v {
			n++
		}
	}
	if n != 1 {
		t.Fatalf("delivered %d times", n)
	}
}

func TestPersistenceFailureRollsBackNewAuthorization(t *testing.T) {
	s := testService(t)
	id, token := register(t, s, "TV")
	_, bind := call(t, s, "POST", "/api/device/bind-code", map[string]any{}, deviceHeaders(id, token))
	previous := s.path
	s.path = t.TempDir()
	if status, _ := call(t, s, "POST", "/api/groups/claim", map[string]any{"code": bind["code"]}, nil); status != 500 {
		t.Fatal(status)
	}
	if len(s.disk.Groups) != 0 || len(s.groupIDs(id)) != 0 || len(s.codes) != 1 {
		t.Fatal("failed claim mutated authorization or consumed code")
	}
	s.path = previous
	if status, _ := call(t, s, "POST", "/api/groups/claim", map[string]any{"code": bind["code"]}, nil); status != 200 {
		t.Fatal("retry failed", status)
	}
}

func TestTTLBoundaries(t *testing.T) {
	s := testService(t)
	id, token := register(t, s, "TV")
	_, gt, _ := pair(t, s, id, token)
	for _, ttl := range []int{-1, 121} {
		if status, _ := call(t, s, "POST", "/api/commands", map[string]any{"targetDeviceId": id, "type": "device.status", "payload": map[string]any{}, "ttlSeconds": ttl, "idempotencyKey": fmt.Sprintf("ttl-test-%d", ttl)}, map[string]string{"Authorization": "Bearer " + gt}); status != 400 {
			t.Fatal(status)
		}
	}
	cid, _ := enqueue(t, s, gt, id, "device.status", map[string]any{}, "expiry-before-delivery")
	s.mu.Lock()
	s.commands[cid].ExpiresAt = time.Now().Add(-time.Second).UnixMilli()
	s.mu.Unlock()
	_, out := call(t, s, "POST", "/api/device/poll", map[string]any{}, deviceHeaders(id, token))
	if out["command"] != nil {
		t.Fatal("expired command delivered")
	}
}

func TestProxyHeaderIgnoredUnlessTrusted(t *testing.T) {
	s := testService(t)
	r := httptest.NewRequest(http.MethodGet, "/", nil)
	r.RemoteAddr = "127.0.0.1:42"
	r.Header.Set("X-Real-IP", "8.8.8.8")
	if s.peer(r) != "127.0.0.1" {
		t.Fatal("untrusted forwarding header honored")
	}
	// Secrets never appear in list responses.
	id, token := register(t, s, "TV")
	_, gt, _ := pair(t, s, id, token)
	_, out := call(t, s, "GET", "/api/devices", nil, map[string]string{"Authorization": "Bearer " + gt})
	raw, _ := json.Marshal(out)
	if strings.Contains(string(raw), token) || strings.Contains(string(raw), gt) || strings.Contains(string(raw), "tokenHash") {
		t.Fatal("list leaked credentials")
	}
}
