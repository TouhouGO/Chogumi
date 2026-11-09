package proxy

import (
	"context"
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/binary"
	"io"
	"math/big"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// echConfig 拼一份 ECHConfig（不带外层的列表长度）：X25519，HKDF-SHA256 + AES-128-GCM。
func echConfig(id byte, pub []byte, publicName string) []byte {
	var c []byte
	c = append(c, id, 0x00, 0x20, 0x00, byte(len(pub)))
	c = append(c, pub...)
	c = append(c, 0x00, 0x04, 0x00, 0x01, 0x00, 0x01, 0x00, byte(len(publicName)))
	c = append(c, publicName...)
	c = append(c, 0x00, 0x00)
	out := []byte{0xfe, 0x0d, 0, 0}
	binary.BigEndian.PutUint16(out[2:], uint16(len(c)))
	return append(out, c...)
}

func echList(configs ...[]byte) []byte {
	var body []byte
	for _, c := range configs {
		body = append(body, c...)
	}
	out := []byte{0, 0}
	binary.BigEndian.PutUint16(out, uint16(len(body)))
	return append(out, body...)
}

// selfSigned 是一张自签证书（覆盖 api.bgm.tv 与 cloudflare-ech.com），连同只信任它的根。
func selfSigned(t *testing.T) (tls.Certificate, *x509.CertPool) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: "test"},
		DNSNames:              []string{"api.bgm.tv", "cloudflare-ech.com"},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(time.Hour),
		IsCA:                  true,
		BasicConstraintsValid: true,
		KeyUsage:              x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	cert, _ := x509.ParseCertificate(der)
	pool := x509.NewCertPool()
	pool.AddCert(cert)
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}, pool
}

// echUpstream 是本机的一个支持 ECH 的 HTTPS 上游，回 200「ok」。返回它的地址、它真正的配置列表、只信任它证书的根。
func echUpstream(t *testing.T) (addr string, config []byte, roots *x509.CertPool) {
	t.Helper()
	key, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	cfg := echConfig(1, key.PublicKey().Bytes(), "cloudflare-ech.com")
	cert, roots := selfSigned(t)
	ln, err := tls.Listen("tcp", "127.0.0.1:0", &tls.Config{
		Certificates:             []tls.Certificate{cert},
		EncryptedClientHelloKeys: []tls.EncryptedClientHelloKey{{Config: cfg, PrivateKey: key.Bytes(), SendAsRetry: true}},
		NextProtos:               []string{"http/1.1"},
	})
	if err != nil {
		t.Fatal(err)
	}
	srv := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, "ok") })}
	go srv.Serve(ln)
	t.Cleanup(func() { srv.Close() })
	return ln.Addr().String(), echList(cfg), roots
}

func get(t *testing.T, s *Server, path string, token string) *http.Response {
	t.Helper()
	req, _ := http.NewRequest("GET", "http://"+net.JoinHostPort("127.0.0.1", strconv.Itoa(s.Port()))+path, nil)
	if token != "" {
		req.Header.Set(TokenHeader, token)
	}
	resp, err := http.DefaultTransport.RoundTrip(req)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { resp.Body.Close() })
	return resp
}

func start(t *testing.T, o Options) *Server {
	t.Helper()
	s, err := Start(o)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	return s
}

func TestUpstreamWithUntrustedCertificateFails(t *testing.T) {
	addr, cfg, _ := echUpstream(t)
	s := start(t, Options{Addrs: []string{addr}, ECHConfig: cfg})
	resp := get(t, s, "/api.bgm.tv/v0/subjects/12", s.Token())
	if resp.StatusCode != http.StatusBadGateway {
		t.Fatalf("status %d, want 502", resp.StatusCode)
	}
	if e := resp.Header.Get(ErrorHeader); !strings.Contains(e, "certificate") {
		t.Fatalf("error %q, want a certificate failure", e)
	}
}

func TestUpstreamWithTrustedCertificateServes(t *testing.T) {
	addr, cfg, roots := echUpstream(t)
	s := start(t, Options{Addrs: []string{addr}, ECHConfig: cfg, rootCAs: roots})
	resp := get(t, s, "/api.bgm.tv/v0/subjects/12", s.Token())
	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK || string(body) != "ok" {
		t.Fatalf("status %d body %q, want 200 ok (%s)", resp.StatusCode, body, resp.Header.Get(ErrorHeader))
	}
}

func TestRequestsWithoutTheTokenAreRefused(t *testing.T) {
	addr, cfg, roots := echUpstream(t)
	s := start(t, Options{Addrs: []string{addr}, ECHConfig: cfg, rootCAs: roots})
	for name, token := range map[string]string{"none": "", "wrong": strings.Repeat("0", len(s.Token()))} {
		if resp := get(t, s, "/api.bgm.tv/v0/subjects/12", token); resp.StatusCode != http.StatusForbidden {
			t.Errorf("%s token: status %d, want 403", name, resp.StatusCode)
		}
	}
	if resp := get(t, s, "/api.bgm.tv/v0/subjects/12", s.Token()); resp.StatusCode != http.StatusOK {
		t.Errorf("right token: status %d, want 200", resp.StatusCode)
	}
}

func TestStaleConfigIsReplacedAndReported(t *testing.T) {
	addr, cfg, roots := echUpstream(t)
	other, _ := ecdh.X25519().GenerateKey(rand.Reader)
	stale := echList(echConfig(9, other.PublicKey().Bytes(), "cloudflare-ech.com"))
	var mu sync.Mutex
	var saved [][]byte
	s := start(t, Options{Addrs: []string{addr}, ECHConfig: stale, rootCAs: roots, OnECHConfig: func(c []byte) {
		mu.Lock()
		saved = append(saved, c)
		mu.Unlock()
	}})
	if resp := get(t, s, "/api.bgm.tv/a", s.Token()); resp.StatusCode != http.StatusOK {
		t.Fatalf("status %d, want 200 after retry (%s)", resp.StatusCode, resp.Header.Get(ErrorHeader))
	}
	mu.Lock()
	defer mu.Unlock()
	if len(saved) != 1 || string(saved[0]) != string(cfg) {
		t.Fatalf("saved %d configs, want exactly the server's one", len(saved))
	}
}

func TestAddressesWithoutPortUse443(t *testing.T) {
	got := withPort([]string{"104.26.8.23", "127.0.0.1:8443", "2606:4700::1"})
	want := []string{"104.26.8.23:443", "127.0.0.1:8443", net.JoinHostPort("2606:4700::1", "443")}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("%d: %s, want %s", i, got[i], want[i])
		}
	}
}

// stallingEdge 接受 TCP、从不回 TLS（TCP 通了而握手卡住的边缘）。返回它的地址与它接受过几条连接。
func stallingEdge(t *testing.T) (string, *atomic.Int32) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	var accepted atomic.Int32
	var held []net.Conn
	var mu sync.Mutex
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			accepted.Add(1)
			mu.Lock()
			held = append(held, c)
			mu.Unlock()
		}
	}()
	t.Cleanup(func() {
		ln.Close()
		mu.Lock()
		for _, c := range held {
			c.Close()
		}
		mu.Unlock()
	})
	return ln.Addr().String(), &accepted
}

// getWithin 发一个请求，客户端最多等 [limit]（模拟 App 一次请求的超时）。
func getWithin(t *testing.T, s *Server, limit time.Duration) (int, error) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), limit)
	defer cancel()
	req, _ := http.NewRequestWithContext(ctx, "GET", "http://"+net.JoinHostPort("127.0.0.1", strconv.Itoa(s.Port()))+"/api.bgm.tv/a", nil)
	req.Header.Set(TokenHeader, s.Token())
	resp, err := (&http.Transport{}).RoundTrip(req)
	if err != nil {
		return 0, err
	}
	resp.Body.Close()
	return resp.StatusCode, nil
}

func TestStalledEdgeHandsOverToTheNextWithinBudget(t *testing.T) {
	stalled, _ := stallingEdge(t)
	good, cfg, roots := echUpstream(t)
	s := start(t, Options{Addrs: []string{stalled, good}, ECHConfig: cfg, rootCAs: roots, EdgeTimeout: 300 * time.Millisecond})

	// 两个地址的预算加起来（600ms）小于客户端的超时（2s）：卡住的那个让过去，第二个接上
	if code, err := getWithin(t, s, 2*time.Second); err != nil || code != http.StatusOK {
		t.Fatalf("code %d err %v, want 200 via the second edge", code, err)
	}
}

func TestAStalledPreferredEdgeYieldsEvenWhenTheCallerGivesUpFirst(t *testing.T) {
	stalled, _ := stallingEdge(t)
	good, cfg, roots := echUpstream(t)
	s := start(t, Options{Addrs: []string{stalled, good}, ECHConfig: cfg, rootCAs: roots, EdgeTimeout: 300 * time.Millisecond})

	// 调用方在卡住的首选用完预算之前就放弃了：这一次没轮到第二个地址
	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	if _, err := s.up.dialTLS(ctx, "tcp", "api.bgm.tv:443"); err == nil {
		t.Fatal("dial should fail on the stalled edge")
	}
	if got := s.up.addrs[s.up.pick()]; got != good {
		t.Fatalf("next dial starts at %s, want the other edge %s", got, good)
	}
}

func TestHealthyPreferredEdgeIsTheOnlyOneContacted(t *testing.T) {
	stalled, accepted := stallingEdge(t)
	good, cfg, roots := echUpstream(t)
	s := start(t, Options{Addrs: []string{good, stalled}, ECHConfig: cfg, rootCAs: roots, EdgeTimeout: 300 * time.Millisecond})

	if code, err := getWithin(t, s, 2*time.Second); err != nil || code != http.StatusOK {
		t.Fatalf("code %d err %v, want 200", code, err)
	}
	if got := accepted.Load(); got != 0 {
		t.Fatalf("contacted the second edge %d times while the first was healthy", got)
	}
}
