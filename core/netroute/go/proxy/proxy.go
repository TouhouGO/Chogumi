// Package proxy 是进程内的直连核心：只听 127.0.0.1，收 http://127.0.0.1:端口/<主机>/<路径> 形式的请求（主机只能是
// bgm.tv 或它的子域名），带着启动时生成的令牌头才转发到 https://<主机>/<路径>。上游一律用 ECH（内层 SNI = 原主机，
// 外层 = 配置里的 public_name）连给定的 Cloudflare 边缘地址。没有普通 SNI 的回退：ECH 被拒且拿不到重试配置时，这次连接就失败。
package proxy

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/subtle"
	"crypto/x509"
	"encoding/hex"
	"io"
	"net"
	"net/http"
	"net/http/httptrace"
	"net/http/httputil"
	"net/url"
	"strings"
	"time"
)

// TokenHeader 是每个请求必须带的令牌头；值在 Start 时随机生成，转发前摘掉。
const TokenHeader = "X-Chogumi-Proxy-Token"

// ErrorHeader 出现在核心自己生成的 502 上，值是上游失败的原因。
const ErrorHeader = "X-Chogumi-Proxy-Error"

// OriginalLocationHeader 带着上游原样的 Location：核心把 Location 改成本机形式（让 OkHttp 自己跟随的跳转也回到核心），
// 调用方据此还原。
const OriginalLocationHeader = "X-Chogumi-Original-Location"

// DefaultEdgeTimeout 是没给 Options.EdgeTimeout 时每个边缘地址的拨号加握手预算（命令行与测试用；App 由客户端的单次超时推出）。
const DefaultEdgeTimeout = 4 * time.Second

// Options 里空着的项用内置值。
type Options struct {
	// Addrs 是上游边缘地址，"ip" 或 "ip:端口"（不写端口是 443），按顺序轮换；空着用 BuiltinAddrs。
	Addrs []string
	// ECHConfig 是初始 ECHConfigList（上次存下的那份）；空着用 BuiltinECHConfig。
	ECHConfig []byte
	// EdgeTimeout 是一个边缘地址的拨号加 ECH 握手的预算；地址错开 EdgeTimeout/4 依次起拨（见 edges.go 的规则），
	// 最坏要 (地址数-1)×EdgeTimeout/4 + EdgeTimeout 才知道全都不行，这要小于调用方一次请求的超时。空着用 DefaultEdgeTimeout。
	EdgeTimeout time.Duration
	// OnECHConfig 在服务端给出新配置（握手被拒时附带的重试配置）时调用，调用方把它存起来，下次 Start 时传回。
	OnECHConfig func(config []byte)
	// Logf 收运行记录：每次上游握手一行 handshake host= edge= ms= result=，见 edges.go。
	Logf func(format string, args ...any)

	rootCAs *x509.CertPool // 验证上游证书用的根；nil 是系统的根（只有本包的测试换成自签的）
}

type Server struct {
	ln    net.Listener
	srv   *http.Server
	token []byte
	up    *upstream
	rp    *httputil.ReverseProxy
}

type targetKey struct{}

func Start(o Options) (*Server, error) {
	addrs := o.Addrs
	if len(addrs) == 0 {
		addrs = BuiltinAddrs
	}
	cfg := o.ECHConfig
	if len(cfg) == 0 {
		cfg = BuiltinECHConfig
	}
	logf := o.Logf
	if logf == nil {
		logf = func(string, ...any) {}
	}
	edgeTimeout := o.EdgeTimeout
	if edgeTimeout <= 0 {
		edgeTimeout = DefaultEdgeTimeout
	}
	onConfig := o.OnECHConfig
	if onConfig == nil {
		onConfig = func([]byte) {}
	}
	tok := make([]byte, 32)
	if _, err := rand.Read(tok); err != nil {
		return nil, err
	}
	list := withPort(addrs)
	up := &upstream{
		addrs:       list,
		dialer:      net.Dialer{},
		edgeTimeout: edgeTimeout,
		stagger:     edgeTimeout / 4,
		health:      newHealth(len(list), time.Now),
		slots:       map[string]chan struct{}{},
		roots:       o.rootCAs,
		onConfig:    onConfig,
		logf:        logf,
	}
	up.ech.Store(&cfg)
	up.transport = &http.Transport{
		DialTLSContext:        up.dialTLS,
		MaxIdleConnsPerHost:   16, // HTTP/1.1：一页几十张图，留够空闲连接复用，少握手
		IdleConnTimeout:       90 * time.Second,
		ExpectContinueTimeout: time.Second,
		DisableCompression:    true, // Accept-Encoding 原样转发，解压留给 OkHttp
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	s := &Server{ln: ln, token: []byte(hex.EncodeToString(tok)), up: up}
	s.rp = &httputil.ReverseProxy{
		Rewrite: func(pr *httputil.ProxyRequest) {
			t := pr.In.Context().Value(targetKey{}).(*url.URL)
			pr.Out.URL = t
			pr.Out.Host = t.Host
			pr.Out.Header.Del(TokenHeader)
		},
		Transport:      up,
		ModifyResponse: rewriteLocation,
		ErrorHandler: func(w http.ResponseWriter, r *http.Request, err error) {
			logf("upstream %s %s: %v", r.Method, r.URL, err)
			w.Header().Set(ErrorHeader, err.Error())
			w.WriteHeader(http.StatusBadGateway)
		},
	}
	s.srv = &http.Server{Handler: s, ReadHeaderTimeout: 10 * time.Second, IdleTimeout: 120 * time.Second}
	go s.srv.Serve(ln)
	return s, nil
}

func (s *Server) Port() int     { return s.ln.Addr().(*net.TCPAddr).Port }
func (s *Server) Token() string { return string(s.token) }
func (s *Server) Close() error  { s.up.transport.CloseIdleConnections(); return s.srv.Close() }

// ResetNetwork 在设备换了网络时调：健康表清空（换了网络，哪个地址好坏要重新看），空闲连接关掉（属于旧网络）。
func (s *Server) ResetNetwork() {
	s.up.health.reset()
	s.up.transport.CloseIdleConnections()
}

// AllowedHost 是核心肯转发的主机：bgm.tv 与它的子域名。
func AllowedHost(h string) bool { return h == "bgm.tv" || strings.HasSuffix(h, ".bgm.tv") }

func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if subtle.ConstantTimeCompare([]byte(r.Header.Get(TokenHeader)), s.token) != 1 {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	t, ok := targetOf(r.RequestURI)
	if r.Method == http.MethodConnect || !ok {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	s.rp.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), targetKey{}, t)))
}

// targetOf 把 /<主机>/<路径>?<查询> 还原成 https://<主机>/<路径>?<查询>；主机不是 bgm.tv（含子域名）就不认。
func targetOf(requestURI string) (*url.URL, bool) {
	rest, ok := strings.CutPrefix(requestURI, "/")
	if !ok {
		return nil, false
	}
	host, path := rest, "/"
	if i := strings.IndexAny(rest, "/?"); i >= 0 {
		host, path = rest[:i], rest[i:]
		if path[0] == '?' {
			path = "/" + path
		}
	}
	if !AllowedHost(host) {
		return nil, false
	}
	u, err := url.Parse("https://" + host + path)
	if err != nil || u.Host != host {
		return nil, false
	}
	return u, true
}

// 指向 bgm.tv（含子域名）的跳转（绝对或相对）改写成本机形式 /<主机>/<路径>，让 OkHttp 自己跟随的跳转也回到核心
// （应用拦截器只看到第一个请求）；原样的值放在 OriginalLocationHeader 里。
func rewriteLocation(resp *http.Response) error {
	loc := resp.Header.Get("Location")
	if loc == "" {
		return nil
	}
	ref, err := url.Parse(loc)
	if err != nil {
		return nil
	}
	abs := resp.Request.URL.ResolveReference(ref)
	if abs.Scheme != "https" || !AllowedHost(abs.Host) {
		return nil
	}
	resp.Header.Set(OriginalLocationHeader, loc)
	resp.Header.Set("Location", "/"+abs.Host+abs.RequestURI())
	return nil
}

func withPort(addrs []string) []string {
	out := make([]string, 0, len(addrs))
	for _, a := range addrs {
		if _, _, err := net.SplitHostPort(a); err != nil {
			a = net.JoinHostPort(a, "443")
		}
		out = append(out, a)
	}
	return out
}

// RoundTrip 转给连接池；Cloudflare 回「error code: 1034」（这个边缘地址不服务这个站）时降级该地址，
// 请求能重放（没有请求体）就换下一个地址再发。
func (u *upstream) RoundTrip(req *http.Request) (*http.Response, error) {
	replayable := req.Body == nil || req.Body == http.NoBody
	for attempt := 0; ; attempt++ {
		var addr string
		trace := &httptrace.ClientTrace{GotConn: func(info httptrace.GotConnInfo) {
			if c, ok := info.Conn.(*addrConn); ok {
				addr = c.addr
			}
		}}
		resp, err := u.transport.RoundTrip(req.WithContext(httptrace.WithClientTrace(req.Context(), trace)))
		if err != nil || !edgeRestricted(resp) {
			return resp, err
		}
		u.logf("edge-restricted host=%s edge=%s", req.URL.Host, addr)
		u.restricted(addr)
		if !replayable || attempt+1 >= len(u.addrs) {
			return resp, nil
		}
		resp.Body.Close()
	}
}

// edgeRestricted 认 Cloudflare 的 1034 错误页；读过的几个字节放回响应体。
func edgeRestricted(resp *http.Response) bool {
	if resp.StatusCode != http.StatusForbidden || resp.ContentLength < 0 || resp.ContentLength > 64 {
		return false
	}
	b, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	resp.Body = io.NopCloser(bytes.NewReader(b))
	return bytes.HasPrefix(b, []byte("error code: 1034"))
}
