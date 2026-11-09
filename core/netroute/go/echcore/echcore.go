// Package echcore 是给 Kotlin 的接口（gomobile bind）：Start 起直连核心，Stop 关掉。只有 core:netroute 调用它。
package echcore

import (
	"fmt"
	"strings"
	"sync"
	"time"

	"echcore/proxy"
)

// Endpoint 是核心的端口与令牌：请求发到 http://127.0.0.1:Port/<主机>/<路径>，带 TokenHeader: Token。
type Endpoint struct {
	Port  int
	Token string
}

// ConfigSink 收服务端给出的新 ECH 配置，由 Kotlin 实现、存起来，下次 Start 时传回。
type ConfigSink interface {
	Save(config []byte)
}

// Logger 收核心的运行记录（每次上游握手一行），由 Kotlin 实现；只在调试包里传，正式包传 nil。
type Logger interface {
	Log(line string)
}

// TokenHeader 是令牌头的名字。
const TokenHeader = proxy.TokenHeader

var (
	mu  sync.Mutex
	cur *proxy.Server
)

// Start 起核心；已经在跑就返回同一个。addrs 是逗号分隔的边缘地址（"ip" 或 "ip:端口"），config 是上次存下的 ECH 配置
// （没有就传空，用内置的），edgeTimeoutMillis 是每个地址的拨号加握手预算（见 proxy.Options.EdgeTimeout），sink 收之后服务端给的新配置，
// log 收运行记录（可以是 nil）。
func Start(addrs string, config []byte, edgeTimeoutMillis int, sink ConfigSink, log Logger) (*Endpoint, error) {
	mu.Lock()
	defer mu.Unlock()
	if cur == nil {
		var list []string
		for _, a := range strings.Split(addrs, ",") {
			if a = strings.TrimSpace(a); a != "" {
				list = append(list, a)
			}
		}
		var logf func(string, ...any)
		if log != nil {
			logf = func(f string, a ...any) { log.Log(fmt.Sprintf(f, a...)) }
		}
		s, err := proxy.Start(proxy.Options{
			Addrs:       list,
			ECHConfig:   config,
			EdgeTimeout: time.Duration(edgeTimeoutMillis) * time.Millisecond,
			OnECHConfig: sink.Save,
			Logf:        logf,
		})
		if err != nil {
			return nil, err
		}
		cur = s
	}
	return &Endpoint{Port: cur.Port(), Token: cur.Token()}, nil
}

// ResetNetwork 在设备换了网络时调：忘掉各边缘地址的好坏、关掉旧网络上的空闲连接。没在跑什么也不做。
func ResetNetwork() {
	mu.Lock()
	defer mu.Unlock()
	if cur != nil {
		cur.ResetNetwork()
	}
}

// Stop 关掉核心；没在跑什么也不做。
func Stop() error {
	mu.Lock()
	defer mu.Unlock()
	if cur == nil {
		return nil
	}
	err := cur.Close()
	cur = nil
	return err
}
