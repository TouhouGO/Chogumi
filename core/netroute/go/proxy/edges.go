package proxy

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

// 连上游边缘地址的规则（只在这里）：
//
//   - 错开并行：一次拨号按健康顺序先起第一个地址，之后每隔 stagger（= 每个地址的预算 / 4）再起一个，前一个失败了就立刻起下一个；
//     先握手成功的那条胜出，其余中止。每个地址的拨号加握手最多 edgeTimeout。
//   - 健康顺序：首选（最近成功、没在冷却的那个）排第一，其余没在冷却的按列表顺序，冷却中的排最后（仍会试，只是不先试）。
//   - 一次失败不改变首选：失败只计数，同一地址连续失败 failThreshold 次才冷却 coolDown；成功清零、解除冷却。首选只在没有首选或
//     首选在冷却时才换成这次成功的地址。被我们中止的（别的地址先胜出、调用方放弃）不算失败。
//   - Cloudflare 回 1034（这个地址不服务这个站）的地址直接冷却 restrictedCoolDown。
//   - 每个主机同时进行的拨号加握手最多 maxHandshakesPerHost 个，其余排队（一页几十张图不会一下子起几十个握手）。
//   - 换了网络（ResetNetwork）：健康表清空。
const (
	failThreshold        = 2
	coolDown             = 30 * time.Second
	restrictedCoolDown   = 10 * time.Minute
	maxHandshakesPerHost = 3
)

type upstream struct {
	addrs       []string
	dialer      net.Dialer
	edgeTimeout time.Duration
	stagger     time.Duration
	health      *health
	slotsMu     sync.Mutex
	slots       map[string]chan struct{}
	ech         atomic.Pointer[[]byte]
	roots       *x509.CertPool
	transport   *http.Transport
	onConfig    func([]byte)
	logf        func(string, ...any)
}

type addrConn struct {
	*tls.Conn
	addr string
}

// ---------- 健康表 ----------

type health struct {
	mu        sync.Mutex
	now       func() time.Time
	preferred int
	fails     []int
	coolUntil []time.Time
}

func newHealth(n int, now func() time.Time) *health {
	return &health{now: now, preferred: -1, fails: make([]int, n), coolUntil: make([]time.Time, n)}
}

func (h *health) coolingLocked(i int) bool { return h.now().Before(h.coolUntil[i]) }

// order 是这一次拨号试地址的顺序（下标）。
func (h *health) order() []int {
	h.mu.Lock()
	defer h.mu.Unlock()
	var first, rest, cooling []int
	for i := range h.fails {
		switch {
		case h.coolingLocked(i):
			cooling = append(cooling, i)
		case i == h.preferred:
			first = append(first, i)
		default:
			rest = append(rest, i)
		}
	}
	return append(append(first, rest...), cooling...)
}

func (h *health) success(i int) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.fails[i] = 0
	h.coolUntil[i] = time.Time{}
	if h.preferred < 0 || h.coolingLocked(h.preferred) {
		h.preferred = i
	}
}

func (h *health) failure(i int) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.fails[i]++
	if h.fails[i] >= failThreshold {
		h.fails[i] = 0
		h.coolUntil[i] = h.now().Add(coolDown)
	}
}

func (h *health) restricted(i int) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.coolUntil[i] = h.now().Add(restrictedCoolDown)
}

func (h *health) reset() {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.preferred = -1
	for i := range h.fails {
		h.fails[i] = 0
		h.coolUntil[i] = time.Time{}
	}
}

func (h *health) preferredIndex() int {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.preferred
}

func (u *upstream) restricted(addr string) {
	for i, a := range u.addrs {
		if a == addr {
			u.health.restricted(i)
		}
	}
	u.transport.CloseIdleConnections()
}

// ---------- 拨号 ----------

type attemptResult struct {
	conn net.Conn
	idx  int
	err  error
}

// dialTLS 按上面的规则拨一条到 host 的 ECH 连接。
func (u *upstream) dialTLS(ctx context.Context, _, hostport string) (net.Conn, error) {
	host, _, err := net.SplitHostPort(hostport)
	if err != nil {
		return nil, err
	}
	order := u.health.order()
	race, cancel := context.WithCancel(ctx)
	defer cancel()
	results := make(chan attemptResult, len(order))
	launched, finished := 0, 0
	launch := func() {
		i := order[launched]
		launched++
		go func() {
			c, err := u.attempt(race, host, i)
			results <- attemptResult{c, i, err}
		}()
	}
	// 输了的、晚到的连接关掉
	drain := func() {
		pending := launched - finished
		go func() {
			for ; pending > 0; pending-- {
				if r := <-results; r.conn != nil {
					r.conn.Close()
				}
			}
		}()
	}
	launch()
	stagger := time.NewTimer(u.stagger)
	defer stagger.Stop()
	var errs []error
	for finished < len(order) {
		select {
		case r := <-results:
			finished++
			if r.err == nil {
				cancel()
				drain()
				return r.conn, nil
			}
			errs = append(errs, fmt.Errorf("%s: %w", u.addrs[r.idx], r.err))
			if launched < len(order) {
				launch()
				stagger.Reset(u.stagger)
			}
		case <-stagger.C:
			if launched < len(order) {
				launch()
				stagger.Reset(u.stagger)
			}
		case <-ctx.Done():
			cancel()
			drain()
			return nil, ctx.Err()
		}
	}
	return nil, errors.Join(errs...)
}

// attempt 是在一个地址上的一次拨号加握手：先排到这个主机的握手名额，再在 edgeTimeout 之内做完；记一行日志、记进健康表。
func (u *upstream) attempt(race context.Context, host string, i int) (net.Conn, error) {
	addr := u.addrs[i]
	release, err := u.acquire(race, host)
	if err != nil {
		return nil, err
	}
	defer release()
	ctx, cancel := context.WithTimeout(race, u.edgeTimeout)
	defer cancel()
	start := time.Now()
	c, err := u.handshake(ctx, host, addr)
	result := "ok"
	switch {
	case err == nil:
		u.health.success(i)
	case race.Err() != nil:
		result = "canceled" // 别的地址先胜出，或调用方放弃：不算这个地址的失败
	default:
		result = classify(ctx, err)
		u.health.failure(i)
	}
	u.logf("handshake host=%s edge=%s ms=%d result=%s", host, addr, time.Since(start).Milliseconds(), result)
	return c, err
}

func (u *upstream) acquire(ctx context.Context, host string) (func(), error) {
	u.slotsMu.Lock()
	slot, ok := u.slots[host]
	if !ok {
		slot = make(chan struct{}, maxHandshakesPerHost)
		u.slots[host] = slot
	}
	u.slotsMu.Unlock()
	select {
	case slot <- struct{}{}:
		return func() { <-slot }, nil
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

// classify 把一次握手失败归成日志里的一类。
func classify(ctx context.Context, err error) string {
	var rej *tls.ECHRejectionError
	var ne net.Error
	switch {
	case errors.As(err, &rej):
		return "ech-rejected"
	case errors.Is(ctx.Err(), context.DeadlineExceeded), errors.As(err, &ne) && ne.Timeout():
		return "timeout"
	case errors.Is(err, syscall.ECONNRESET), errors.Is(err, syscall.EPIPE), errors.Is(err, io.EOF), errors.Is(err, io.ErrUnexpectedEOF):
		return "reset"
	case errors.Is(err, syscall.ECONNREFUSED):
		return "refused"
	}
	return "error"
}

// handshake 是一个地址上的 ECH 握手：被拒时换上服务端给的重试配置再握一次；新配置留在内存给之后的连接用，并交给 onConfig 存起来。
func (u *upstream) handshake(ctx context.Context, host, addr string) (net.Conn, error) {
	for retried := false; ; retried = true {
		raw, err := u.dialer.DialContext(ctx, "tcp", addr)
		if err != nil {
			return nil, err
		}
		tc := tls.Client(raw, &tls.Config{
			ServerName:                     host,
			RootCAs:                        u.roots,
			EncryptedClientHelloConfigList: *u.ech.Load(),
			MinVersion:                     tls.VersionTLS13,
			NextProtos:                     []string{"http/1.1"},
		})
		err = tc.HandshakeContext(ctx)
		if err == nil {
			return &addrConn{tc, addr}, nil
		}
		raw.Close()
		var rej *tls.ECHRejectionError
		if !retried && errors.As(err, &rej) && len(rej.RetryConfigList) > 0 {
			u.logf("ech-retry host=%s edge=%s bytes=%d", host, addr, len(rej.RetryConfigList))
			cfg := append([]byte(nil), rej.RetryConfigList...)
			u.ech.Store(&cfg)
			u.onConfig(cfg)
			continue
		}
		return nil, err
	}
}
