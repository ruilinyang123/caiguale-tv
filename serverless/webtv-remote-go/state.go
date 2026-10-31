package main

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"sync"
	"time"
)

type device struct {
	ID         string `json:"deviceId"`
	TokenHash  string `json:"tokenHash"`
	Name       string `json:"name"`
	AppVersion string `json:"appVersion"`
	Revoked    bool   `json:"revoked"`
}
type group struct {
	ID        string          `json:"groupId"`
	TokenHash string          `json:"tokenHash"`
	Devices   map[string]bool `json:"devices"`
}
type identities struct {
	Version int                `json:"version"`
	Devices map[string]*device `json:"devices"`
	Groups  map[string]*group  `json:"groups"`
}
type binding struct {
	DeviceID  string
	ExpiresAt int64
}
type Command struct {
	ID             string         `json:"id"`
	GroupID        string         `json:"groupId"`
	TargetDeviceID string         `json:"targetDeviceId"`
	Type           string         `json:"type"`
	Payload        map[string]any `json:"payload"`
	Status         string         `json:"status"`
	CreatedAt      int64          `json:"createdAt"`
	ExpiresAt      int64          `json:"expiresAt"`
	Result         map[string]any `json:"result,omitempty"`
	FinishedAt     int64          `json:"finishedAt,omitempty"`
	Key            string         `json:"-"`
	Fingerprint    string         `json:"-"`
}
type bucket struct {
	Count int
	Until time.Time
}
type service struct {
	mu           sync.Mutex
	path         string
	origin       string
	trustedProxy *net.IPNet
	disk         identities
	codes        map[string]binding
	commands     map[string]*Command
	idempotency  map[string]string
	seen         map[string]int64
	limits       map[string]bucket
	sockets      map[string]*socket
	now          func() time.Time
}

func newService(path, origin string) (*service, error) {
	s := &service{path: path, origin: origin, now: time.Now, codes: map[string]binding{}, commands: map[string]*Command{}, idempotency: map[string]string{}, seen: map[string]int64{}, limits: map[string]bucket{}, sockets: map[string]*socket{}, disk: identities{Version: 1, Devices: map[string]*device{}, Groups: map[string]*group{}}}
	if path == "" {
		return nil, errors.New("state path required")
	}
	b, err := os.ReadFile(path)
	if err == nil {
		if err = json.Unmarshal(b, &s.disk); err != nil {
			return nil, errors.New("invalid identity state")
		}
		if s.disk.Version != 1 || s.disk.Devices == nil || s.disk.Groups == nil {
			return nil, errors.New("unsupported identity state")
		}
		for id, d := range s.disk.Devices {
			if d == nil || id != d.ID || len(d.TokenHash) != 64 {
				return nil, errors.New("invalid device state")
			}
		}
		for id, g := range s.disk.Groups {
			if g == nil || id != g.ID || len(g.TokenHash) != 64 || g.Devices == nil {
				return nil, errors.New("invalid group state")
			}
			for did := range g.Devices {
				if s.disk.Devices[did] == nil {
					return nil, errors.New("invalid membership state")
				}
			}
		}
		if err = os.Chmod(path, 0600); err != nil {
			return nil, err
		}
	} else if !os.IsNotExist(err) {
		return nil, err
	}
	return s, nil
}
func hash(v string) string { h := sha256.Sum256([]byte(v)); return hex.EncodeToString(h[:]) }
func random(prefix string) string {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		panic("secure randomness unavailable")
	}
	return prefix + hex.EncodeToString(b)
}

// Call only under mu. Failed writes restore the in-memory identity snapshot.
func (s *service) persistChange(change func()) error {
	old, _ := json.Marshal(s.disk)
	change()
	b, err := json.Marshal(s.disk)
	if err == nil {
		err = atomicWrite(s.path, b)
	}
	if err != nil {
		s.disk = identities{}
		_ = json.Unmarshal(old, &s.disk)
		return apiError{500, "storage_unavailable"}
	}
	return nil
}
func atomicWrite(path string, b []byte) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0700); err != nil {
		return err
	}
	f, err := os.CreateTemp(dir, ".identity-*")
	if err != nil {
		return err
	}
	tmp := f.Name()
	defer os.Remove(tmp)
	if err = f.Chmod(0600); err == nil {
		_, err = f.Write(b)
	}
	if err == nil {
		err = f.Sync()
	}
	closeErr := f.Close()
	if err == nil {
		err = closeErr
	}
	if err != nil {
		return err
	}
	if err = os.Rename(tmp, path); err != nil {
		return err
	}
	// Directory fsync is supported on the production Linux deployment. A failed
	// post-rename sync must not roll back memory after the file was committed.
	if runtime.GOOS != "windows" {
		if d, e := os.Open(dir); e == nil {
			_ = d.Sync()
			_ = d.Close()
		}
	}
	return nil
}
func (s *service) groupIDs(id string) []string {
	ids := []string{}
	for gid, g := range s.disk.Groups {
		if g.Devices[id] {
			ids = append(ids, gid)
		}
	}
	sort.Strings(ids)
	return ids
}
func (s *service) publicDevice(d *device) map[string]any {
	return map[string]any{"deviceId": d.ID, "name": d.Name, "appVersion": d.AppVersion, "groupIds": s.groupIDs(d.ID), "lastSeen": s.seen[d.ID], "online": s.seen[d.ID] > 0 && s.now().UnixMilli()-s.seen[d.ID] < 75000}
}
func (s *service) allow(key string, n int, window time.Duration) bool {
	now := s.now()
	b := s.limits[key]
	if !now.Before(b.Until) {
		b = bucket{Until: now.Add(window)}
	}
	if b.Count >= n {
		return false
	}
	b.Count++
	s.limits[key] = b
	return true
}
func (s *service) peer(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	if s.trustedProxy != nil && s.trustedProxy.Contains(net.ParseIP(host)) {
		if ip := net.ParseIP(r.Header.Get("X-Real-IP")); ip != nil {
			return ip.String()
		}
	}
	return host
}
func (s *service) cleanup() {
	now := s.now()
	for k, v := range s.codes {
		if v.ExpiresAt <= now.UnixMilli() {
			delete(s.codes, k)
		}
	}
	for k, v := range s.limits {
		if !now.Before(v.Until) {
			delete(s.limits, k)
		}
	}
	for id, c := range s.commands {
		if (c.Status == "queued" || c.Status == "delivered") && now.UnixMilli() >= c.ExpiresAt {
			c.Status = "expired"
		}
		if now.UnixMilli()-c.CreatedAt > int64(10*time.Minute/time.Millisecond) {
			delete(s.idempotency, c.Key)
			delete(s.commands, id)
		}
	}
}
func (s *service) next(id string) *Command {
	s.cleanup()
	var next *Command
	for _, c := range s.commands {
		if c.TargetDeviceID == id && c.Status == "queued" {
			g := s.disk.Groups[c.GroupID]
			if g == nil || !g.Devices[id] {
				c.Status = "revoked"
				continue
			}
			if next == nil || c.CreatedAt < next.CreatedAt || c.CreatedAt == next.CreatedAt && c.ID < next.ID {
				next = c
			}
		}
	}
	if next != nil {
		next.Status = "delivered"
	}
	return next
}
func (s *service) invalidate(id, gid string) {
	for _, c := range s.commands {
		if c.TargetDeviceID == id && (gid == "" || c.GroupID == gid) && (c.Status == "queued" || c.Status == "delivered") {
			c.Status = "revoked"
		}
	}
	for code, b := range s.codes {
		if b.DeviceID == id {
			delete(s.codes, code)
		}
	}
	if ws := s.sockets[id]; ws != nil {
		ws.close()
	}
}
func (s *service) wake(id string) {
	if ws := s.sockets[id]; ws != nil {
		select {
		case ws.wake <- struct{}{}:
		default:
		}
	}
}
func (s *service) finish(id, cid string, ok *bool, result map[string]any) error {
	c := s.commands[cid]
	if c == nil || c.TargetDeviceID != id {
		return apiError{404, "command_not_found"}
	}
	if ok == nil || result == nil {
		return apiError{400, "invalid_result"}
	}
	if c.Status == "done" || c.Status == "failed" {
		return nil
	}
	if s.now().UnixMilli() >= c.ExpiresAt {
		c.Status = "expired"
		return apiError{409, "command_expired"}
	}
	if c.Status != "delivered" {
		return apiError{409, "command_not_deliverable"}
	}
	g := s.disk.Groups[c.GroupID]
	if g == nil || !g.Devices[id] {
		return apiError{403, "device_not_authorized"}
	}
	c.Status = "failed"
	if *ok {
		c.Status = "done"
	}
	c.Result = result
	c.FinishedAt = s.now().UnixMilli()
	return nil
}
func bindNumber() string {
	b := make([]byte, 8)
	for i := range b {
		var one [1]byte
		for {
			if _, err := rand.Read(one[:]); err != nil {
				panic("secure randomness unavailable")
			}
			if one[0] < 250 {
				b[i] = '0' + one[0]%10
				break
			}
		}
	}
	return string(b)
}
func capabilities() map[string]any {
	return map[string]any{"protocolVersion": 1, "commands": []string{"action.search", "action.push", "action.control", "device.status"}, "websocket": true, "poll": true}
}
func (s *service) String() string { return fmt.Sprintf("remote protocol %d", 1) }
