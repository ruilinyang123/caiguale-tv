package main

import (
	"encoding/json"
	"errors"
	"io"
	"mime"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"time"
	"unicode/utf8"
)

const maxBody = 16 << 10

type apiError struct {
	Status int
	Code   string
}

func (e apiError) Error() string { return e.Code }

type request struct {
	DeviceID       string         `json:"deviceId,omitempty"`
	DeviceToken    string         `json:"deviceToken,omitempty"`
	Name           string         `json:"name,omitempty"`
	AppVersion     string         `json:"appVersion,omitempty"`
	Code           string         `json:"code,omitempty"`
	TargetDeviceID string         `json:"targetDeviceId,omitempty"`
	Type           string         `json:"type,omitempty"`
	Payload        map[string]any `json:"payload,omitempty"`
	IdempotencyKey string         `json:"idempotencyKey,omitempty"`
	TTLSeconds     int            `json:"ttlSeconds,omitempty"`
	OK             *bool          `json:"ok,omitempty"`
	Result         map[string]any `json:"result,omitempty"`
}

func jsonResponse(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
func fail(w http.ResponseWriter, err error) {
	var e apiError
	if !errors.As(err, &e) {
		e = apiError{500, "internal_error"}
	}
	if e.Status == 429 {
		w.Header().Set("Retry-After", "60")
	}
	jsonResponse(w, e.Status, map[string]any{"ok": false, "error": e.Code})
}
func decode(w http.ResponseWriter, r *http.Request, b *request) error {
	mediaType, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if err != nil || mediaType != "application/json" {
		return apiError{415, "json_required"}
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxBody)
	dec := json.NewDecoder(r.Body)
	dec.DisallowUnknownFields()
	if err := dec.Decode(b); err != nil {
		var large *http.MaxBytesError
		if errors.As(err, &large) {
			return apiError{413, "body_too_large"}
		}
		return apiError{400, "invalid_json"}
	}
	var extra any
	if err := dec.Decode(&extra); err != io.EOF {
		var large *http.MaxBytesError
		if errors.As(err, &large) {
			return apiError{413, "body_too_large"}
		}
		return apiError{400, "invalid_json"}
	}
	return nil
}
func bearer(r *http.Request) string {
	a := r.Header.Get("Authorization")
	if strings.HasPrefix(a, "Bearer ") {
		return strings.TrimPrefix(a, "Bearer ")
	}
	return ""
}
func (s *service) requireDevice(r *http.Request, b request) (*device, error) {
	id := r.Header.Get("X-Device-Id")
	if id == "" {
		id = b.DeviceID
	}
	token := bearer(r)
	if token == "" {
		token = b.DeviceToken
	}
	if b.DeviceID != "" && b.DeviceID != id || b.DeviceToken != "" && b.DeviceToken != token {
		return nil, apiError{401, "invalid_device_credentials"}
	}
	d := s.disk.Devices[id]
	if d == nil || d.Revoked || token == "" || d.TokenHash != hash(token) {
		return nil, apiError{401, "invalid_device_credentials"}
	}
	s.seen[id] = s.now().UnixMilli()
	return d, nil
}
func (s *service) requireGroup(r *http.Request) (*group, error) {
	token := bearer(r)
	if token != "" {
		h := hash(token)
		for _, g := range s.disk.Groups {
			if g.TokenHash == h {
				return g, nil
			}
		}
	}
	return nil, apiError{401, "invalid_group_credentials"}
}
func (s *service) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Referrer-Policy", "no-referrer")
	w.Header().Set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
	if o := r.Header.Get("Origin"); o != "" && o != s.origin {
		fail(w, apiError{403, "origin_denied"})
		return
	}
	if r.URL.RawQuery != "" {
		fail(w, apiError{400, "query_not_supported"})
		return
	}
	if !strings.HasPrefix(r.URL.Path, "/api/") {
		s.serveStatic(w, r)
		return
	}
	s.mu.Lock()
	s.cleanup()
	allowed := len(s.limits) < 10000 && s.allow("ip:"+s.peer(r), 600, time.Minute)
	s.mu.Unlock()
	if !allowed {
		fail(w, apiError{429, "rate_limited"})
		return
	}
	if r.URL.Path == "/api/device/ws" && r.Method == http.MethodGet {
		s.serveWS(w, r)
		return
	}
	b := request{}
	if r.Method == http.MethodPost {
		if err := decode(w, r, &b); err != nil {
			fail(w, err)
			return
		}
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	v, err := s.route(r, b)
	if err != nil {
		fail(w, err)
		return
	}
	jsonResponse(w, 200, v)
}
func (s *service) route(r *http.Request, b request) (any, error) {
	p, m := r.URL.Path, r.Method
	ok := map[string]any{"ok": true}
	switch {
	case m == "GET" && p == "/api/health":
		return ok, nil
	case m == "GET" && p == "/api/server/capabilities":
		return map[string]any{"ok": true, "server": capabilities()}, nil
	case m == "POST" && p == "/api/device/register":
		if len(b.Name) > 200 || len(b.AppVersion) > 80 {
			return nil, apiError{400, "invalid_device_metadata"}
		}
		if b.DeviceID != "" || b.DeviceToken != "" || bearer(r) != "" || r.Header.Get("X-Device-Id") != "" {
			d, err := s.requireDevice(r, b)
			if err != nil {
				return nil, err
			}
			id := d.ID
			if err = s.persistChange(func() {
				if b.Name != "" {
					d.Name = b.Name
				}
				if b.AppVersion != "" {
					d.AppVersion = b.AppVersion
				}
			}); err != nil {
				return nil, err
			}
			token := bearer(r)
			if token == "" {
				token = b.DeviceToken
			}
			return map[string]any{"ok": true, "deviceId": id, "deviceToken": token, "groupIds": s.groupIDs(id), "server": capabilities()}, nil
		}
		if !s.allow("register:"+s.peer(r), 10, time.Hour) || len(s.disk.Devices) >= 10000 {
			return nil, apiError{429, "registration_limited"}
		}
		token, id := random("dtk_"), random("dev_")
		name := strings.TrimSpace(b.Name)
		if name == "" {
			name = "WebTV"
		}
		err := s.persistChange(func() {
			s.disk.Devices[id] = &device{ID: id, TokenHash: hash(token), Name: name, AppVersion: b.AppVersion}
		})
		if err != nil {
			return nil, err
		}
		s.seen[id] = s.now().UnixMilli()
		return map[string]any{"ok": true, "deviceId": id, "deviceToken": token, "groupIds": []string{}, "server": capabilities()}, nil
	case m == "POST" && p == "/api/device/bind-code":
		d, err := s.requireDevice(r, b)
		if err != nil {
			return nil, err
		}
		if !s.allow("bind:"+d.ID, 5, time.Minute) {
			return nil, apiError{429, "binding_limited"}
		}
		for code, v := range s.codes {
			if v.DeviceID == d.ID {
				delete(s.codes, code)
			}
		}
		code := bindNumber()
		for s.codes[code].DeviceID != "" {
			code = bindNumber()
		}
		expires := s.now().Add(5 * time.Minute).UnixMilli()
		s.codes[code] = binding{d.ID, expires}
		return map[string]any{"ok": true, "code": code, "expiresIn": 300, "expiresAt": expires}, nil
	case m == "POST" && p == "/api/groups/claim":
		if !s.allow("claim:"+s.peer(r), 10, time.Minute) || !s.allow("claim:global", 120, time.Minute) {
			return nil, apiError{429, "binding_limited"}
		}
		v, found := s.codes[b.Code]
		if !found || v.ExpiresAt <= s.now().UnixMilli() {
			return nil, apiError{404, "bind_code_invalid"}
		}
		d := s.disk.Devices[v.DeviceID]
		if d == nil || d.Revoked {
			return nil, apiError{404, "bind_code_invalid"}
		}
		token := bearer(r)
		var g *group
		if token != "" {
			var err error
			g, err = s.requireGroup(r)
			if err != nil {
				return nil, err
			}
		} else {
			token = random("gtk_")
			g = &group{ID: random("grp_"), TokenHash: hash(token), Devices: map[string]bool{}}
		}
		if len(s.groupIDs(d.ID)) >= 16 || len(g.Devices) >= 100 || len(s.disk.Groups) >= 10000 {
			return nil, apiError{429, "group_limit"}
		}
		if err := s.persistChange(func() { g.Devices[d.ID] = true; s.disk.Groups[g.ID] = g }); err != nil {
			return nil, err
		}
		delete(s.codes, b.Code)
		return map[string]any{"ok": true, "groupId": g.ID, "groupToken": token, "deviceId": d.ID}, nil
	case m == "POST" && p == "/api/device/poll":
		d, err := s.requireDevice(r, b)
		if err != nil {
			return nil, err
		}
		return map[string]any{"ok": true, "command": s.next(d.ID), "groupIds": s.groupIDs(d.ID)}, nil
	case m == "POST" && p == "/api/device/revoke":
		d, err := s.requireDevice(r, b)
		if err != nil {
			return nil, err
		}
		id := d.ID
		if err = s.persistChange(func() {
			d.Revoked = true
			for _, g := range s.disk.Groups {
				delete(g.Devices, id)
			}
		}); err != nil {
			return nil, err
		}
		s.invalidate(id, "")
		return ok, nil
	case m == "POST" && strings.HasPrefix(p, "/api/device/groups/") && strings.HasSuffix(p, "/revoke"):
		d, err := s.requireDevice(r, b)
		if err != nil {
			return nil, err
		}
		gid := strings.TrimSuffix(strings.TrimPrefix(p, "/api/device/groups/"), "/revoke")
		g := s.disk.Groups[gid]
		if g == nil || !g.Devices[d.ID] {
			return nil, apiError{404, "group_not_found"}
		}
		if err = s.persistChange(func() { delete(g.Devices, d.ID) }); err != nil {
			return nil, err
		}
		s.invalidate(d.ID, gid)
		return ok, nil
	case m == "GET" && p == "/api/devices":
		g, err := s.requireGroup(r)
		if err != nil {
			return nil, err
		}
		list := []map[string]any{}
		ids := []string{}
		for id := range g.Devices {
			ids = append(ids, id)
		}
		sort.Strings(ids)
		for _, id := range ids {
			d := s.disk.Devices[id]
			if d != nil && !d.Revoked {
				pub := s.publicDevice(d)
				pub["groupIds"] = []string{g.ID}
				list = append(list, pub)
			}
		}
		return map[string]any{"ok": true, "devices": list}, nil
	case m == "DELETE" && strings.HasPrefix(p, "/api/devices/"):
		g, err := s.requireGroup(r)
		if err != nil {
			return nil, err
		}
		id := strings.TrimPrefix(p, "/api/devices/")
		if !g.Devices[id] {
			return nil, apiError{404, "device_not_found"}
		}
		if err = s.persistChange(func() { delete(g.Devices, id) }); err != nil {
			return nil, err
		}
		s.invalidate(id, g.ID)
		return ok, nil
	case m == "POST" && p == "/api/commands":
		g, err := s.requireGroup(r)
		if err != nil {
			return nil, err
		}
		d := s.disk.Devices[b.TargetDeviceID]
		if d == nil || d.Revoked || !g.Devices[d.ID] {
			return nil, apiError{403, "device_not_authorized"}
		}
		if err = validateCommand(b); err != nil {
			return nil, err
		}
		if b.TTLSeconds == 0 {
			b.TTLSeconds = 30
		}
		if b.TTLSeconds < 1 || b.TTLSeconds > 120 {
			return nil, apiError{400, "invalid_ttl"}
		}
		if len(b.IdempotencyKey) < 8 || len(b.IdempotencyKey) > 128 {
			return nil, apiError{400, "invalid_idempotency_key"}
		}
		fingerprint, _ := json.Marshal([]any{b.TargetDeviceID, b.Type, b.Payload, b.TTLSeconds})
		key := g.ID + ":" + b.IdempotencyKey
		if cid := s.idempotency[key]; cid != "" {
			c := s.commands[cid]
			if c.Fingerprint != hash(string(fingerprint)) {
				return nil, apiError{409, "idempotency_conflict"}
			}
			return map[string]any{"ok": true, "commandId": c.ID, "command": c}, nil
		}
		if !s.allow("command:"+g.ID, 120, time.Minute) || len(s.commands) >= 10000 {
			return nil, apiError{429, "command_limited"}
		}
		pending := 0
		for _, c := range s.commands {
			if c.TargetDeviceID == d.ID && (c.Status == "queued" || c.Status == "delivered") {
				pending++
			}
		}
		if pending >= 32 {
			return nil, apiError{429, "device_queue_full"}
		}
		c := &Command{ID: random("cmd_"), GroupID: g.ID, TargetDeviceID: d.ID, Type: b.Type, Payload: b.Payload, Status: "queued", CreatedAt: s.now().UnixMilli(), ExpiresAt: s.now().Add(time.Duration(b.TTLSeconds) * time.Second).UnixMilli(), Key: key, Fingerprint: hash(string(fingerprint))}
		s.commands[c.ID] = c
		s.idempotency[key] = c.ID
		s.wake(d.ID)
		return map[string]any{"ok": true, "commandId": c.ID, "command": c}, nil
	case m == "POST" && strings.HasPrefix(p, "/api/commands/") && strings.HasSuffix(p, "/result"):
		d, err := s.requireDevice(r, b)
		if err != nil {
			return nil, err
		}
		id := strings.TrimSuffix(strings.TrimPrefix(p, "/api/commands/"), "/result")
		if err = s.finish(d.ID, id, b.OK, b.Result); err != nil {
			return nil, err
		}
		return ok, nil
	case m == "GET" && strings.HasPrefix(p, "/api/commands/"):
		g, err := s.requireGroup(r)
		if err != nil {
			return nil, err
		}
		c := s.commands[strings.TrimPrefix(p, "/api/commands/")]
		if c == nil || c.GroupID != g.ID || !g.Devices[c.TargetDeviceID] {
			return nil, apiError{404, "command_not_found"}
		}
		return map[string]any{"ok": true, "command": c}, nil
	}
	return nil, apiError{404, "not_found"}
}
func validateCommand(b request) error {
	bad := apiError{400, "invalid_command"}
	if b.Payload == nil {
		return bad
	}
	switch b.Type {
	case "device.status":
		if len(b.Payload) != 0 {
			return bad
		}
	case "action.search":
		v, ok := b.Payload["word"].(string)
		if !ok || strings.TrimSpace(v) == "" || utf8.RuneCountInString(v) > 200 || len(b.Payload) != 1 {
			return bad
		}
	case "action.push":
		v, ok := b.Payload["url"].(string)
		if !ok || len(v) > 4096 || len(b.Payload) != 1 || strings.ContainsAny(v, "\r\n\x00") {
			return bad
		}
		u, err := url.Parse(v)
		if err != nil || (u.Scheme != "https" && u.Scheme != "http") || u.Hostname() == "" || u.User != nil || u.Fragment != "" {
			return bad
		}
	case "action.control":
		v, ok := b.Payload["action"].(string)
		if !ok || len(b.Payload) != 1 {
			return bad
		}
		switch v {
		case "play", "pause", "stop", "prev", "next", "repeat", "loop", "replay":
		default:
			return bad
		}
	default:
		return bad
	}
	return nil
}
