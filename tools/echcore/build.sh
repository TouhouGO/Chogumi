#!/usr/bin/env bash
# 重新生成直连核心 core/netroute/libs/echcore.aar（gomobile bind，只含 arm64-v8a），打印它的 sha256。
# 需要：Go（版本见 core/netroute/go/go.mod）、ANDROID_HOME（Android SDK）、ANDROID_NDK_HOME（NDK r28 及以上：.so 按 16 KB 页对齐）。
# gomobile 与 gobind 的版本钉在 go.mod 的 tool 里，由这里从模块缓存编出来，不用全局安装。
set -euo pipefail

root=$(cd "$(dirname "$0")/../.." && pwd)
src="$root/core/netroute/go"
out="$root/core/netroute/libs/echcore.aar"
: "${ANDROID_HOME:?需要 ANDROID_HOME}" "${ANDROID_NDK_HOME:?需要 ANDROID_NDK_HOME}"

bin=$(mktemp -d)
trap 'rm -rf "$bin"' EXIT
cd "$src"
go build -o "$bin/" golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind
PATH="$bin:$PATH" gomobile bind \
    -target=android/arm64 -androidapi 24 \
    -javapkg io.github.touhougo.chogumi.ech \
    -trimpath -ldflags="-s -w" \
    -o "$out" ./echcore
rm -f "${out%.aar}-sources.jar"
shasum -a 256 "$out"
