package proxy

import (
	"io"
	"net/http"
	"net/url"
	"strings"
	"testing"
)

func TestTargetOf(t *testing.T) {
	for in, want := range map[string]string{
		"/api.bgm.tv/v0/subjects/12":         "https://api.bgm.tv/v0/subjects/12",
		"/bgm.tv/":                           "https://bgm.tv/",
		"/bgm.tv":                            "https://bgm.tv/",
		"/bgm.tv?a=1":                        "https://bgm.tv/?a=1",
		"/lain.bgm.tv/pic/a%20b.jpg?x=1&y=2": "https://lain.bgm.tv/pic/a%20b.jpg?x=1&y=2",
		"/example.com/":                      "",
		"/bgm.tv.example.com/":               "",
		"/bgm.tv@example.com/":               "",
		"/bgm.tv:8443/":                      "",
		"http://bgm.tv/":                     "",
		"/":                                  "",
	} {
		u, ok := targetOf(in)
		got := ""
		if ok {
			got = u.String()
		}
		if got != want {
			t.Errorf("%s -> %q, want %q", in, got, want)
		}
	}
}

func TestRewriteLocation(t *testing.T) {
	req, _ := url.Parse("https://bgm.tv/user/1")
	for in, want := range map[string]string{
		"https://bgm.tv/user/sai":       "/bgm.tv/user/sai",
		"/user/sai":                     "/bgm.tv/user/sai",
		"https://lain.bgm.tv/pic/a.jpg": "/lain.bgm.tv/pic/a.jpg",
		"https://example.com/":          "https://example.com/",
		"https://bgm.tv.example.com/":   "https://bgm.tv.example.com/",
	} {
		r := &http.Response{Header: http.Header{"Location": {in}}, Request: &http.Request{URL: req}}
		rewriteLocation(r)
		if got := r.Header.Get("Location"); got != want {
			t.Errorf("%s -> %s, want %s", in, got, want)
		}
		if want != in && r.Header.Get(OriginalLocationHeader) != in {
			t.Errorf("%s: original not kept", in)
		}
	}
}

func TestEdgeRestrictedKeepsBody(t *testing.T) {
	mk := func(code int, body string) *http.Response {
		return &http.Response{StatusCode: code, ContentLength: int64(len(body)), Body: io.NopCloser(strings.NewReader(body))}
	}
	r := mk(403, "error code: 1034\n")
	if !edgeRestricted(r) {
		t.Fatal("1034 not recognised")
	}
	b, _ := io.ReadAll(r.Body)
	if string(b) != "error code: 1034\n" {
		t.Fatalf("body lost: %q", b)
	}
	if edgeRestricted(mk(403, "forbidden")) {
		t.Fatal("plain 403 taken as 1034")
	}
}
