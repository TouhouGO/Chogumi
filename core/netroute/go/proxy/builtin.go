package proxy

// BuiltinAddrs 是 bgm.tv 在 Cloudflare 上的三个地址（2026-11-09 实测）；App 经 core:netroute 的地址来源传入，这里是命令行与测试的默认。
var BuiltinAddrs = []string{"104.26.8.23", "104.26.9.23", "172.67.73.67"}

// BuiltinECHConfig 是一份结构合法、公钥随意的 ECHConfigList（public_name = cloudflare-ech.com）：没有存下的配置时用它，
// 第一次握手必被拒，服务端随拒绝给出真配置，之后用那一份（并经 Options.OnECHConfig 存起来）。
var BuiltinECHConfig = []byte{
	0x00, 0x45, 0xfe, 0x0d, 0x00, 0x41, 0x07, 0x00, 0x20, 0x00, 0x20,
	0x5c, 0x2a, 0x91, 0x0e, 0x6b, 0x77, 0x3d, 0xa4, 0x18, 0x52, 0xc9, 0x0f, 0x8e, 0x41, 0x26, 0xb3,
	0x70, 0x9d, 0x14, 0xe2, 0x5a, 0x3b, 0x88, 0xc6, 0x01, 0xf7, 0x6e, 0x29, 0xd0, 0x93, 0x4b, 0x1c,
	0x00, 0x04, 0x00, 0x01, 0x00, 0x01, 0x00,
	18, 'c', 'l', 'o', 'u', 'd', 'f', 'l', 'a', 'r', 'e', '-', 'e', 'c', 'h', '.', 'c', 'o', 'm',
	0x00, 0x00,
}

// validECHConfigList 看 [b] 在结构上是不是一份 ECHConfigList（RFC 9849 §4）：总长对得上，每份 ECHConfig 的各段长度都在范围内、
// 正好用完。版本不是 0xfe0d 的那份只看外层长度（crypto/tls 会跳过它）。
func validECHConfigList(b []byte) bool {
	r := reader(b)
	list, ok := r.vec16()
	if !ok || len(r) != 0 || len(list) == 0 {
		return false
	}
	for len(list) > 0 {
		version, ok1 := list.u16()
		body, ok2 := list.vec16()
		if !ok1 || !ok2 {
			return false
		}
		if version != 0xfe0d {
			continue
		}
		_, ok1 = body.u8()       // config_id
		_, ok2 = body.u16()      // kem_id
		key, ok3 := body.vec16() // public_key
		suites, ok4 := body.vec16()
		_, ok5 := body.u8() // maximum_name_length
		name, ok6 := body.vec8()
		_, ok7 := body.vec16() // extensions
		if !(ok1 && ok2 && ok3 && ok4 && ok5 && ok6 && ok7) || len(body) != 0 ||
			len(key) == 0 || len(suites) == 0 || len(suites)%4 != 0 || len(name) == 0 {
			return false
		}
	}
	return true
}

type reader []byte

func (r *reader) u8() (byte, bool) {
	if len(*r) < 1 {
		return 0, false
	}
	v := (*r)[0]
	*r = (*r)[1:]
	return v, true
}

func (r *reader) u16() (uint16, bool) {
	if len(*r) < 2 {
		return 0, false
	}
	v := uint16((*r)[0])<<8 | uint16((*r)[1])
	*r = (*r)[2:]
	return v, true
}

func (r *reader) take(n int) (reader, bool) {
	if len(*r) < n {
		return nil, false
	}
	v := (*r)[:n]
	*r = (*r)[n:]
	return v, true
}

func (r *reader) vec8() (reader, bool) {
	n, ok := r.u8()
	if !ok {
		return nil, false
	}
	return r.take(int(n))
}

func (r *reader) vec16() (reader, bool) {
	n, ok := r.u16()
	if !ok {
		return nil, false
	}
	return r.take(int(n))
}
