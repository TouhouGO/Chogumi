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
	"sort"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

// 连上游边缘地址的规则（只在这里）：
//
//   - 协议：ALPN 先 h2、再 http/1.1。每个主机只开一条连接（Transport.MaxConnsPerHost = 1）：h2 时所有请求在这一条上并发，
//     取消一个请求只重置它那一条流；万一只谈到 http/1.1，请求在这一条上排队（日志的 proto= 看得见）。
//     这一条连接悄悄不通了（中途被掐、不再回包）：edgeTimeout 收不到任何帧就发 ping，再过 edgeTimeout/2 没回就断开，
//     在途的请求失败、下一个请求重新拨号，不会一直挂到调用方超时。
//   - 错开并行：一次拨号按健康顺序先起第一个地址，之后每隔 stagger 再起一个，前一个失败了就立刻起下一个；先握手成功的胜出，
//     其余中止。stagger 是排第一的地址实测握手耗时（EWMA）的 1.5 倍，夹在 [minStagger, edgeTimeout/2]；还没量过时是 edgeTimeout/4。
//     每个地址的拨号加握手最多 edgeTimeout。
//   - 健康顺序：没在冷却的按实测握手耗时（EWMA，只由成功更新）从快到慢，没量过的排在量过的后面、按列表顺序；冷却中的排最后
//     （仍会试，只是不先试）。所以一次失败不改变顺序。
//   - 冷却：同一地址连续失败 failThreshold 次进入冷却，冷却时长按它累计进冷却的次数逐次加长（coolDowns：30 秒、2 分钟、10 分钟封顶）；
//     成功一次清零。被我们中止的（别的地址先胜出、调用方放弃）不算失败。Cloudflare 回 1034（这个地址不服务这个站）直接按最长冷却。
//   - 每个主机同时进行的拨号加握手最多 maxHandshakesPerHost 个，其余排队。
//   - 换了网络（ResetNetwork）：健康表清空（耗时、失败计数、冷却都忘掉）。
//   - 换地址（1034）与换网络都让当前的连接池退役（retire）：换上一个新池，旧池不再接请求，新请求与重放重新拨号、按健康顺序选地址；
//     旧池里在途的流照常跑完，之后连接空闲，按 IdleConnTimeout 关掉。h2 的一条连接只要还有流在跑就不算空闲，只关空闲连接挡不住
//     新请求接着复用它。冷却不用退役：冷却只来自拨号失败与 1034，拨号失败时这个主机没有连接。
const (
	failThreshold        = 2
	maxHandshakesPerHost = 3
	minStagger           = 250 * time.Millisecond
	ewmaWeight           = 0.3 // 新一次握手耗时在 EWMA 里的权重
)

// coolDowns 是第 1、2、3 次及以后进冷却的时长。
var coolDowns = []time.Duration{30 * time.Second, 2 * time.Minute, 10 * time.Minute}

type upstream struct {
	addrs       []string
	dialer      net.Dialer
	edgeTimeout time.Duration
	health      *health
	slotsMu     sync.Mutex
	slots       map[string]chan struct{}
	ech         atomic.Pointer[[]byte]
	roots       *x509.CertPool
	pool        atomic.Pointer[http.Transport]
	onConfig    func([]byte)
	logf        func(string, ...any)
}

// ---------- 健康表 ----------

type health struct {
	mu        sync.Mutex
	now       func() time.Time
	ewma      []time.Duration // 实测握手耗时；0 是还没量过
	fails     []int           // 连续失败次数
	strikes   []int           // 累计进冷却的次数
	coolUntil []time.Time
}

func newHealth(n int, now func() time.Time) *health {
	return &health{now: now, ewma: make([]time.Duration, n), fails: make([]int, n), strikes: make([]int, n), coolUntil: make([]time.Time, n)}
}

func (h *health) coolingLocked(i int) bool { return h.now().Before(h.coolUntil[i]) }

// order 是这一次拨号试地址的顺序（下标）。
func (h *health) order() []int {
	h.mu.Lock()
	defer h.mu.Unlock()
	idx := make([]int, len(h.fails))
	for i := range idx {
		idx[i] = i
	}
	rank := func(i int) (int, time.Duration) {
		switch {
		case h.coolingLocked(i):
			return 2, 0
		case h.ewma[i] == 0:
			return 1, 0
		}
		return 0, h.ewma[i]
	}
	sort.SliceStable(idx, func(a, b int) bool {
		ga, ea := rank(idx[a])
		gb, eb := rank(idx[b])
		if ga != gb {
			return ga < gb
		}
		return ea < eb
	})
	return idx
}

// staggerFor 是起下一个地址之前等多久：排第一的地址实测耗时的 1.5 倍，夹在 [minStagger, edgeTimeout/2]；没量过时 edgeTimeout/4。
func (h *health) staggerFor(first int, edgeTimeout time.Duration) time.Duration {
	h.mu.Lock()
	measured := h.ewma[first]
	h.mu.Unlock()
	if measured == 0 {
		return edgeTimeout / 4
	}
	return min(max(measured*3/2, minStagger), edgeTimeout/2)
}

func (h *health) success(i int, took time.Duration) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.fails[i] = 0
	h.strikes[i] = 0
	h.coolUntil[i] = time.Time{}
	if h.ewma[i] == 0 {
		h.ewma[i] = took
	} else {
		h.ewma[i] = time.Duration(float64(h.ewma[i])*(1-ewmaWeight) + float64(took)*ewmaWeight)
	}
}

func (h *health) failure(i int) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.fails[i]++
	if h.fails[i] >= failThreshold {
		h.fails[i] = 0
		h.coolLocked(i, coolDowns[min(h.strikes[i], len(coolDowns)-1)])
		h.strikes[i]++
	}
}

func (h *health) coolLocked(i int, d time.Duration) { h.coolUntil[i] = h.now().Add(d) }

func (h *health) restricted(i int) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.coolLocked(i, coolDowns[len(coolDowns)-1])
}

func (h *health) reset() {
	h.mu.Lock()
	defer h.mu.Unlock()
	for i := range h.fails {
		h.ewma[i], h.fails[i], h.strikes[i], h.coolUntil[i] = 0, 0, 0, time.Time{}
	}
}

// restricted 记下 [addr] 回了 1034，并让送出那个回答的连接池 [served] 退役。
func (u *upstream) restricted(addr string, served *http.Transport) {
	for i, a := range u.addrs {
		if a == addr {
			u.health.restricted(i)
		}
	}
	u.retire(served)
}

// newPool 是一个新的连接池（规则见上）。
func (u *upstream) newPool() *http.Transport {
	return &http.Transport{
		DialTLSContext:    u.dialTLS,
		ForceAttemptHTTP2: true,
		MaxConnsPerHost:   1, // 每个主机一条连接，h2 上并发
		// 那一条连接悄悄不通了要尽快发现：edgeTimeout 没收到任何帧就发 ping，再过 edgeTimeout/2 没回就断开
		HTTP2:                 &http.HTTP2Config{SendPingTimeout: u.edgeTimeout, PingTimeout: u.edgeTimeout / 2},
		IdleConnTimeout:       90 * time.Second,
		ExpectContinueTimeout: time.Second,
		DisableCompression:    true, // Accept-Encoding 原样转发，解压留给 OkHttp
	}
}

// retire 让 [old] 退役：它还是当前的池就换上新池、关掉它的空闲连接。同时几个请求都要它退役时只换一次。
func (u *upstream) retire(old *http.Transport) {
	if u.pool.CompareAndSwap(old, u.newPool()) {
		old.CloseIdleConnections()
	}
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
	step := u.health.staggerFor(order[0], u.edgeTimeout)
	stagger := time.NewTimer(step)
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
				stagger.Reset(step)
			}
		case <-stagger.C:
			if launched < len(order) {
				launch()
				stagger.Reset(step)
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
	took := time.Since(start)
	result := "ok"
	switch {
	case err == nil:
		u.health.success(i, took)
		result = "ok proto=" + protoOf(c)
	case race.Err() != nil:
		result = "canceled" // 别的地址先胜出，或调用方放弃：不算这个地址的失败
	default:
		result = classify(ctx, err)
		u.health.failure(i)
	}
	u.logf("handshake host=%s edge=%s ms=%d result=%s", host, addr, took.Milliseconds(), result)
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

// protoOf 是谈下来的协议：h2 或 h1。
func protoOf(c net.Conn) string {
	if tc, ok := c.(*tls.Conn); ok && tc.ConnectionState().NegotiatedProtocol == "h2" {
		return "h2"
	}
	return "h1"
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
			NextProtos:                     []string{"h2", "http/1.1"},
		})
		err = tc.HandshakeContext(ctx)
		if err == nil {
			return tc, nil // 原样的 *tls.Conn：net/http 只对它谈 h2
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
