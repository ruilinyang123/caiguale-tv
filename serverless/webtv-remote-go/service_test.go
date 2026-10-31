package main

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

type byteReader struct {
	b []byte
	i int
}

func (r *byteReader) Read(p []byte) (int, error) {
	if r.i >= len(r.b) {
		return 0, io.EOF
	}
	n := copy(p, r.b[r.i:])
	r.i += n
	return n, nil
}
func bytesReader(b []byte) *byteReader { return &byteReader{b: b} }

func testService(t *testing.T) *service {
	t.Helper()
	s, err := newService(filepath.Join(t.TempDir(), "identities.json"), "http://127.0.0.1:8787")
	if err != nil {
		t.Fatal(err)
	}
	return s
}
func call(t *testing.T, s *service, method, path string, body any, headers map[string]string) (int, map[string]any) {
	t.Helper()
	var raw []byte
	if body != nil {
		raw, _ = json.Marshal(body)
	}
	r := httptest.NewRequest(method, path, bytesReader(raw))
	r.RemoteAddr = "127.0.0.1:1234"
	for k, v := range headers {
		r.Header.Set(k, v)
	}
	if body != nil {
		r.Header.Set("Content-Type", "application/json")
	}
	w := httptest.NewRecorder()
	s.ServeHTTP(w, r)
	var out map[string]any
	_ = json.Unmarshal(w.Body.Bytes(), &out)
	return w.Code, out
}
func register(t *testing.T, s *service, name string) (string, string) {
	t.Helper()
	code, out := call(t, s, http.MethodPost, "/api/device/register", map[string]any{"name": name}, nil)
	if code != 200 {
		t.Fatalf("register: %d %#v", code, out)
	}
	return out["deviceId"].(string), out["deviceToken"].(string)
}
func pair(t *testing.T, s *service, id, token string) (string, string, string) {
	t.Helper()
	code, out := call(t, s, http.MethodPost, "/api/device/bind-code", map[string]any{"deviceId": id, "deviceToken": token}, nil)
	if code != 200 {
		t.Fatalf("bind code: %d %#v", code, out)
	}
	code, out = call(t, s, http.MethodPost, "/api/groups/claim", map[string]any{"code": out["code"]}, nil)
	if code != 200 {
		t.Fatalf("claim: %d %#v", code, out)
	}
	return out["groupId"].(string), out["groupToken"].(string), id
}
func enqueue(t *testing.T, s *service, groupToken, id, typ string, payload map[string]any, key string) (string, map[string]any) {
	t.Helper()
	code, out := call(t, s, http.MethodPost, "/api/commands", map[string]any{"targetDeviceId": id, "type": typ, "payload": payload, "idempotencyKey": key}, map[string]string{"Authorization": "Bearer " + groupToken})
	if code != 200 {
		t.Fatalf("enqueue: %d %#v", code, out)
	}
	return out["commandId"].(string), out
}
func deviceHeaders(id, token string) map[string]string {
	return map[string]string{"Authorization": "Bearer " + token, "X-Device-Id": id}
}

func TestRegisterBindClaimCommandAndPersistence(t *testing.T) {
	s := testService(t)
	id, token := register(t, s, "TV")
	code, out := call(t, s, http.MethodPost, "/api/device/bind-code", map[string]any{"deviceId": id, "deviceToken": token}, nil)
	if code != 200 {
		t.Fatal(code, out)
	}
	bind := out["code"].(string)
	code, out = call(t, s, http.MethodPost, "/api/groups/claim", map[string]any{"code": bind}, nil)
	if code != 200 {
		t.Fatal(code, out)
	}
	gt := out["groupToken"].(string)
	code, out = call(t, s, http.MethodPost, "/api/groups/claim", map[string]any{"code": bind}, map[string]string{"Authorization": "Bearer " + gt})
	if code != 404 {
		t.Fatalf("one time code reused: %d %#v", code, out)
	}
	first, _ := enqueue(t, s, gt, id, "action.control", map[string]any{"action": "next"}, "request-1234")
	_, again := enqueue(t, s, gt, id, "action.control", map[string]any{"action": "next"}, "request-1234")
	if again["commandId"] != first {
		t.Fatal("idempotency failed")
	}
	code, _ = call(t, s, http.MethodPost, "/api/commands", map[string]any{"targetDeviceId": id, "type": "action.control", "payload": map[string]any{"action": "home"}, "idempotencyKey": "request-5678"}, map[string]string{"Authorization": "Bearer " + gt})
	if code != 400 {
		t.Fatal("unsafe control accepted", code)
	}
	s.mu.Lock()
	s.now = func() time.Time { return time.Now().Add(31 * time.Second) }
	s.cleanup()
	s.mu.Unlock()
	s2, err := newService(s.path, s.origin)
	if err != nil {
		t.Fatal(err)
	}
	if len(s2.disk.Devices) != 1 || len(s2.disk.Groups) != 1 || len(s2.commands) != 0 || len(s2.codes) != 0 {
		t.Fatal("identity persistence or temporary state isolation failed")
	}
}

func TestCrossGroupAuthorizationAndPayloadBoundaries(t *testing.T) {
	s := testService(t)
	a, at := register(t, s, "A")
	_, ag, _ := pair(t, s, a, at)
	b, bt := register(t, s, "B")
	_, bg, _ := pair(t, s, b, bt)
	id, _ := enqueue(t, s, ag, a, "device.status", map[string]any{}, "group-a-status")
	if id == "" {
		t.Fatal("missing command id")
	}
	if code, _ := call(t, s, http.MethodGet, "/api/commands/"+id, nil, map[string]string{"Authorization": "Bearer " + bg}); code != 404 {
		t.Fatalf("cross-group read: %d", code)
	}
	if code, _ := call(t, s, http.MethodPost, "/api/commands", map[string]any{"targetDeviceId": a, "type": "device.status", "payload": map[string]any{}, "idempotencyKey": "group-b-send"}, map[string]string{"Authorization": "Bearer " + bg}); code != 403 {
		t.Fatalf("cross-group send: %d", code)
	}
	for _, tc := range []struct {
		typ     string
		payload map[string]any
	}{
		{"device.status", map[string]any{"extra": true}},
		{"action.search", map[string]any{"word": "x", "extra": true}},
		{"action.control", map[string]any{"action": "next", "extra": true}},
		{"unknown.command", map[string]any{}},
	} {
		if code, _ := call(t, s, http.MethodPost, "/api/commands", map[string]any{"targetDeviceId": a, "type": tc.typ, "payload": tc.payload, "idempotencyKey": "boundary-" + tc.typ}, map[string]string{"Authorization": "Bearer " + ag}); code != 400 {
			t.Errorf("payload %s accepted with status %d", tc.typ, code)
		}
	}
}

func TestBindExpiryAndRateLimit(t *testing.T) {
	s := testService(t)
	id, token := register(t, s, "TV")
	s.mu.Lock()
	now := time.Now()
	s.now = func() time.Time { return now }
	s.mu.Unlock()
	code, out := call(t, s, http.MethodPost, "/api/device/bind-code", map[string]any{"deviceId": id, "deviceToken": token}, nil)
	if code != 200 {
		t.Fatal(code)
	}
	bind := out["code"].(string)
	s.mu.Lock()
	s.now = func() time.Time { return now.Add(6 * time.Minute) }
	s.mu.Unlock()
	if code, _ = call(t, s, http.MethodPost, "/api/groups/claim", map[string]any{"code": bind}, nil); code != 404 {
		t.Fatalf("expired bind code status %d", code)
	}
	s.mu.Lock()
	s.now = func() time.Time { return now }
	s.limits = map[string]bucket{}
	s.mu.Unlock()
	for i := 0; i < 5; i++ {
		if code, _ = call(t, s, http.MethodPost, "/api/device/bind-code", map[string]any{"deviceId": id, "deviceToken": token}, nil); code != 200 {
			t.Fatalf("bind attempt %d: %d", i, code)
		}
	}
	if code, _ = call(t, s, http.MethodPost, "/api/device/bind-code", map[string]any{"deviceId": id, "deviceToken": token}, nil); code != 429 {
		t.Fatalf("bind rate limit status %d", code)
	}
}

func TestPollSingleDeliveryAndExpiredResult(t *testing.T) {
	s := testService(t)
	id, token := register(t, s, "TV")
	_, gt, _ := pair(t, s, id, token)
	cid, _ := enqueue(t, s, gt, id, "device.status", map[string]any{}, "poll-once")
	code, out := call(t, s, http.MethodPost, "/api/device/poll", map[string]any{"deviceId": id, "deviceToken": token}, nil)
	if code != 200 || out["command"] == nil {
		t.Fatalf("poll first: %d %#v", code, out)
	}
	code, out = call(t, s, http.MethodPost, "/api/device/poll", map[string]any{"deviceId": id, "deviceToken": token}, nil)
	if code != 200 || out["command"] != nil {
		t.Fatalf("poll redelivered: %d %#v", code, out)
	}
	s.mu.Lock()
	s.commands[cid].ExpiresAt = s.now().Add(-time.Second).UnixMilli()
	s.mu.Unlock()
	if code, _ = call(t, s, http.MethodPost, "/api/commands/"+cid+"/result", map[string]any{"deviceId": id, "deviceToken": token, "ok": true, "result": map[string]any{"x": 1}}, nil); code != 409 {
		t.Fatalf("expired result status %d", code)
	}
}

func TestDeviceRevokeInvalidatesCredentialsAndCommands(t *testing.T) {
	s := testService(t)
	id, token := register(t, s, "TV")
	_, gt, _ := pair(t, s, id, token)
	cid, _ := enqueue(t, s, gt, id, "device.status", map[string]any{}, "revoke-command")
	if code, _ := call(t, s, http.MethodPost, "/api/device/revoke", map[string]any{"deviceId": id, "deviceToken": token}, nil); code != 200 {
		t.Fatal(code)
	}
	for path, method := range map[string]string{"/api/device/poll": http.MethodPost, "/api/commands/" + cid + "/result": http.MethodPost} {
		body := map[string]any{"deviceId": id, "deviceToken": token}
		if strings.HasSuffix(path, "/result") {
			body["ok"] = true
			body["result"] = map[string]any{}
		}
		if code, _ := call(t, s, method, path, body, nil); code != 401 {
			t.Errorf("revoked %s status %d", path, code)
		}
	}
	s.mu.Lock()
	if s.commands[cid].Status != "revoked" {
		t.Errorf("command status %s", s.commands[cid].Status)
	}
	s.mu.Unlock()
}

func TestWebSocketCommandResultClosedLoop(t *testing.T) {
	s := testService(t)
	id, token := register(t, s, "TV")
	_, gt, _ := pair(t, s, id, token)
	cid, _ := enqueue(t, s, gt, id, "action.control", map[string]any{"action": "play"}, "ws-loop-command")
	httpServer := httptest.NewServer(s)
	defer httpServer.Close()
	wsURL := "ws" + strings.TrimPrefix(httpServer.URL, "http") + "/api/device/ws"
	conn, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	if err = conn.WriteJSON(map[string]any{"type": "hello", "deviceId": id, "deviceToken": token}); err != nil {
		t.Fatal(err)
	}
	var ready map[string]any
	if err = conn.ReadJSON(&ready); err != nil {
		t.Fatal(err)
	}
	if ready["type"] != "ready" {
		t.Fatalf("ready frame %#v", ready)
	}
	var frame struct {
		Type    string  `json:"type"`
		Command Command `json:"command"`
	}
	if err = conn.ReadJSON(&frame); err != nil {
		t.Fatal(err)
	}
	if frame.Type != "command" || frame.Command.ID != cid {
		t.Fatalf("command frame %#v", frame)
	}
	if err = conn.WriteJSON(map[string]any{"type": "result", "commandId": cid, "ok": true, "result": map[string]any{"media": "snapshot"}}); err != nil {
		t.Fatal(err)
	}
	var ack map[string]any
	if err = conn.ReadJSON(&ack); err != nil {
		t.Fatal(err)
	}
	if ack["type"] != "ack" {
		t.Fatalf("ack frame %#v", ack)
	}
	s.mu.Lock()
	status := s.commands[cid].Status
	s.mu.Unlock()
	if status != "done" {
		t.Fatalf("command status %s", status)
	}
	if code, _ := call(t, s, http.MethodPost, "/api/device/revoke", map[string]any{}, deviceHeaders(id, token)); code != 200 {
		t.Fatal(code)
	}
	if _, _, err := conn.ReadMessage(); err == nil {
		t.Fatal("revoked websocket remained open")
	}
	other, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer other.Close()
	_ = other.SetReadDeadline(time.Now().Add(5 * time.Second))
	_ = other.WriteJSON(map[string]any{"type": "hello", "deviceId": id, "deviceToken": token})
	var rejected map[string]any
	if err := other.ReadJSON(&rejected); err != nil {
		t.Fatal(err)
	}
	if rejected["type"] != "error" || rejected["error"] != "invalid_device_credentials" {
		t.Fatalf("revoked hello accepted: %#v", rejected)
	}
}

func TestPersistenceFailureDoesNotChangeMemory(t *testing.T) {
	s := testService(t)
	id, token := register(t, s, "TV")
	before := s.disk.Devices[id].Name
	s.path = filepath.Join(t.TempDir(), "a-directory")
	if err := os.MkdirAll(s.path, 0700); err != nil {
		t.Fatal(err)
	}
	code, _ := call(t, s, http.MethodPost, "/api/device/register", map[string]any{"deviceId": id, "deviceToken": token, "name": "MUTATED"}, nil)
	if code != 500 {
		t.Fatalf("persistence failure status %d", code)
	}
	if s.disk.Devices[id].Name != before {
		t.Fatalf("memory changed after failed persistence: %q", s.disk.Devices[id].Name)
	}
}

func TestAtomicStatePermissions(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Windows chmod does not expose POSIX mode bits")
	}
	s := testService(t)
	if err := s.persistChange(func() { s.disk.Devices["x"] = &device{ID: "x", TokenHash: hash("x")} }); err != nil {
		t.Fatal(err)
	}
	st, err := os.Stat(s.path)
	if err != nil {
		t.Fatal(err)
	}
	if st.Mode().Perm() != 0600 {
		t.Fatalf("mode %o", st.Mode().Perm())
	}
}
