package main

import (
	"encoding/json"
	"net/http"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

type socket struct {
	conn    *websocket.Conn
	wake    chan struct{}
	done    chan struct{}
	once    sync.Once
	writeMu sync.Mutex
}

func (c *socket) close() { c.once.Do(func() { close(c.done); _ = c.conn.Close() }) }
func (c *socket) write(v any) error {
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	_ = c.conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	return c.conn.WriteJSON(v)
}
func (s *service) serveWS(w http.ResponseWriter, r *http.Request) {
	// Exact configured Origin is enforced by ServeHTTP. Native clients may omit it.
	up := websocket.Upgrader{CheckOrigin: func(r *http.Request) bool { return r.Header.Get("Origin") == "" || r.Header.Get("Origin") == s.origin }, ReadBufferSize: 1024, WriteBufferSize: 1024}
	s.mu.Lock()
	allowed := s.allow("ws:"+s.peer(r), 30, time.Minute)
	s.mu.Unlock()
	if !allowed {
		fail(w, apiError{429, "rate_limited"})
		return
	}
	conn, err := up.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	c := &socket{conn: conn, wake: make(chan struct{}, 1), done: make(chan struct{})}
	defer c.close()
	conn.SetReadLimit(maxBody)
	_ = conn.SetReadDeadline(time.Now().Add(10 * time.Second))
	var hello struct {
		Type        string `json:"type"`
		DeviceID    string `json:"deviceId"`
		DeviceToken string `json:"deviceToken"`
	}
	if err = conn.ReadJSON(&hello); err != nil || hello.Type != "hello" {
		_ = c.write(map[string]any{"type": "error", "ok": false, "error": "invalid_hello"})
		return
	}
	s.mu.Lock()
	d, err := s.requireDevice(r, request{DeviceID: hello.DeviceID, DeviceToken: hello.DeviceToken})
	if err != nil {
		s.mu.Unlock()
		_ = c.write(map[string]any{"type": "error", "ok": false, "error": "invalid_device_credentials"})
		return
	}
	id := d.ID
	if old := s.sockets[id]; old != nil {
		old.close()
	}
	s.sockets[id] = c
	ready := map[string]any{"type": "ready", "ok": true, "deviceId": id, "groupIds": s.groupIDs(id)}
	s.mu.Unlock()
	defer func() {
		s.mu.Lock()
		if s.sockets[id] == c {
			delete(s.sockets, id)
		}
		s.mu.Unlock()
	}()
	if c.write(ready) != nil {
		return
	}
	_ = conn.SetReadDeadline(time.Now().Add(75 * time.Second))
	conn.SetPongHandler(func(string) error {
		s.mu.Lock()
		s.seen[id] = s.now().UnixMilli()
		s.mu.Unlock()
		return conn.SetReadDeadline(time.Now().Add(75 * time.Second))
	})
	go func() {
		defer c.close()
		for {
			var frame struct {
				Type      string         `json:"type"`
				CommandID string         `json:"commandId"`
				OK        *bool          `json:"ok"`
				Result    map[string]any `json:"result"`
			}
			if conn.ReadJSON(&frame) != nil {
				return
			}
			s.mu.Lock()
			s.cleanup()
			d := s.disk.Devices[id]
			if d == nil || d.Revoked || s.sockets[id] != c {
				s.mu.Unlock()
				return
			}
			if !s.allow("ws-result:"+id, 240, time.Minute) {
				s.mu.Unlock()
				return
			}
			var resultErr error
			if frame.Type != "result" {
				resultErr = apiError{400, "invalid_frame"}
			} else {
				resultErr = s.finish(id, frame.CommandID, frame.OK, frame.Result)
			}
			s.seen[id] = s.now().UnixMilli()
			s.mu.Unlock()
			if resultErr != nil {
				if c.write(map[string]any{"type": "error", "commandId": frame.CommandID, "ok": false, "error": resultErr.Error()}) != nil {
					return
				}
			} else {
				if c.write(map[string]any{"type": "ack", "commandId": frame.CommandID, "ok": true}) != nil {
					return
				}
			}
		}
	}()
	c.wake <- struct{}{}
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-c.done:
			return
		case <-ticker.C:
			if conn.WriteControl(websocket.PingMessage, nil, time.Now().Add(5*time.Second)) != nil {
				return
			}
		case <-c.wake:
			for {
				s.mu.Lock()
				if s.sockets[id] != c || s.disk.Devices[id].Revoked {
					s.mu.Unlock()
					return
				}
				cmd := s.next(id)
				// Marshal under the state lock; results/revocation cannot race JSON encoding.
				var frame json.RawMessage
				if cmd != nil {
					frame, _ = json.Marshal(map[string]any{"type": "command", "command": cmd})
				}
				s.mu.Unlock()
				if cmd == nil {
					break
				}
				if c.write(frame) != nil {
					return
				}
			}
		}
	}
}
