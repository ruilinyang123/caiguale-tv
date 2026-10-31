package main

import (
	"context"
	"embed"
	"flag"
	"io/fs"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"
)

//go:embed web/*
var webFiles embed.FS

func (s *service) serveStatic(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" && r.Method != "HEAD" {
		fail(w, apiError{405, "method_not_allowed"})
		return
	}
	if r.URL.Path != "/" && r.URL.Path != "/app.js" && r.URL.Path != "/style.css" {
		fail(w, apiError{404, "not_found"})
		return
	}
	sub, _ := fs.Sub(webFiles, "web")
	http.FileServer(http.FS(sub)).ServeHTTP(w, r)
}
func main() {
	listen := flag.String("listen", "127.0.0.1:8787", "loopback bind address")
	state := flag.String("state", "data/identities.json", "persistent identity file")
	origin := flag.String("origin", "http://127.0.0.1:8787", "exact public HTTPS origin (HTTP only for loopback development)")
	proxy := flag.String("trusted-proxy", "", "CIDR allowed to supply X-Real-IP; nginx must overwrite this header")
	flag.Parse()
	host, _, err := net.SplitHostPort(*listen)
	if err != nil || !net.ParseIP(host).IsLoopback() {
		log.Fatal("listen must be a loopback IP:port; use an HTTPS reverse proxy")
	}
	u, err := url.Parse(*origin)
	if err != nil || u.Host == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" || (u.Path != "" && u.Path != "/") || (u.Scheme != "https" && !(u.Scheme == "http" && (u.Hostname() == "localhost" || net.ParseIP(u.Hostname()).IsLoopback()))) {
		log.Fatal("origin must be HTTPS, or loopback HTTP for development")
	}
	s, err := newService(*state, strings.TrimRight(*origin, "/"))
	if err != nil {
		log.Fatal("cannot load identity state; fix state file/permissions before restarting")
	}
	if *proxy != "" {
		_, s.trustedProxy, err = net.ParseCIDR(*proxy)
		if err != nil {
			log.Fatal("invalid trusted proxy CIDR")
		}
	}
	server := &http.Server{Addr: *listen, Handler: s, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 15 * time.Second, WriteTimeout: 15 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 8 << 10, ErrorLog: log.New(redactedLog{}, "", 0)}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go func() {
		<-ctx.Done()
		s.mu.Lock()
		for _, c := range s.sockets {
			c.close()
		}
		s.mu.Unlock()
		timeout, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		_ = server.Shutdown(timeout)
	}()
	log.Print("WebTV remote started (loopback only; no request/token logging)")
	if err = server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Fatal("HTTP listener failed")
	}
}

// net/http errors can contain request material; emit only a fixed diagnostic.
type redactedLog struct{}

func (redactedLog) Write(p []byte) (int, error) {
	log.Print("HTTP transport error")
	return len(p), nil
}
