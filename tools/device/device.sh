#!/usr/bin/env bash
# 本机模拟器池与设备锁（唯一实现处）。设备名、AVD 名、锁路径只写在这里，文档与别的脚本都经它取。
#
#   tools/device/device.sh status              每台设备：AVD、在不在线、谁占着
#   tools/device/device.sh claim "<线程，分支>" [设备]
#                                              拿一台在线且空闲的设备（或指定那台），把设备名打到标准输出；
#                                              没有空闲设备时退出码 1，标准错误里写谁占着哪台
#   tools/device/device.sh release <设备> "<线程，分支>"
#                                              释放自己拿到的那台（失败时也要释放）；占用者和 claim 时写的不一样就拒绝，
#                                              别人的锁放不掉
#   tools/device/device.sh start <设备>         启动这台设备的 AVD 并等它开机完成；已在线就什么都不做
#
# 拿到设备后，整个设备步骤（装包、adb、仪器测试、截图、录屏）都带 ANDROID_SERIAL=<设备>：Gradle 与 adb 都认它，
# 本机常同时挂着不该碰的设备，不钉设备会装错、测错。
#
# 锁是 mkdir 出来的目录（原子），每台一把，里面的 owner 文件写占用者。
set -euo pipefail

# 设备池：设备名 → AVD 名。两台同一配置（同系统镜像、分辨率、内存），各自登录同一个测试账号。
POOL=(
    "emulator-5554 Pixel_10_Pro_XL"
    "emulator-5556 Pixel_10_Pro_XL_2"
)

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="${CHOGUMI_ADB:-$SDK/platform-tools/adb}"
EMULATOR="$SDK/emulator/emulator"
LOCK_ROOT="${CHOGUMI_LOCK_ROOT:-/tmp}"
BOOT_TIMEOUT_SECONDS=300

die() { echo "$*" >&2; exit 2; }

avd_of() {
    local entry wanted="$1"
    for entry in "${POOL[@]}"; do
        set -- $entry
        if [[ "$1" == "$wanted" ]]; then echo "$2"; return 0; fi
    done
    return 1
}

serials() {
    local entry
    for entry in "${POOL[@]}"; do set -- $entry; echo "$1"; done
}

lock_dir() { echo "$LOCK_ROOT/chogumi-device-$1.lock"; }

# owner 文件的开头：占用者与设备。release 按它核对是不是自己的锁。
owner_prefix() { printf 'owner: %s, 设备 %s, ' "$1" "$2"; }

online() {
    [[ "$("$ADB" -s "$1" get-state 2>/dev/null)" == "device" ]] &&
        [[ "$("$ADB" -s "$1" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]
}

holder() {
    local dir
    dir="$(lock_dir "$1")"
    [[ -d "$dir" ]] || return 1
    cat "$dir/owner" 2>/dev/null || echo "（$dir 没有 owner 文件）"
}

try_lock() {
    local serial="$1" owner="$2" dir
    dir="$(lock_dir "$serial")"
    mkdir "$dir" 2>/dev/null || return 1
    printf '%s%s\n' "$(owner_prefix "$owner" "$serial")" "$(date '+%Y-%m-%d %H:%M:%S %z')" > "$dir/owner"
}

in_pool() { avd_of "$1" > /dev/null; }

cmd_status() {
    local s avd state who
    for s in $(serials); do
        avd="$(avd_of "$s")"
        if online "$s"; then state="在线"; else state="离线"; fi
        if who="$(holder "$s")"; then :; else who="空闲"; fi
        printf '%s\t%s\t%s\t%s\n' "$s" "$avd" "$state" "$who"
    done
}

cmd_claim() {
    local owner="${1:-}" wanted="${2:-}" s candidates
    [[ -n "$owner" ]] || die "用法：$0 claim \"<线程，分支>\" [设备]"
    if [[ -n "$wanted" ]]; then
        in_pool "$wanted" || die "$wanted 不在设备池里（$(serials | tr '\n' ' ')）"
        candidates="$wanted"
    else
        candidates="$(serials)"
    fi
    for s in $candidates; do
        online "$s" || continue
        if try_lock "$s" "$owner"; then echo "$s"; return 0; fi
    done
    echo "没有在线且空闲的设备：" >&2
    cmd_status >&2
    return 1
}

cmd_release() {
    local serial="${1:-}" owner="${2:-}" dir held
    [[ -n "$serial" && -n "$owner" ]] || die "用法：$0 release <设备> \"<线程，分支>\"（与 claim 时写的一样）"
    in_pool "$serial" || die "$serial 不在设备池里"
    dir="$(lock_dir "$serial")"
    [[ -d "$dir" ]] || return 0
    held="$(cat "$dir/owner" 2>/dev/null || true)"
    if [[ "$held" != "$(owner_prefix "$owner" "$serial")"* ]]; then
        echo "$serial 不是「${owner}」占着的，不放：${held:-（没有 owner 文件）}" >&2
        return 1
    fi
    rm -rf "$dir"
}

cmd_start() {
    local serial="${1:-}" avd waited=0
    [[ -n "$serial" ]] || die "用法：$0 start <设备>"
    avd="$(avd_of "$serial")" || die "$serial 不在设备池里"
    if online "$serial"; then echo "$serial 已在线"; return 0; fi
    # 在 bash 里放到后台：zsh 默认的 BG_NICE 会让后台的 qemu 降优先级，宿主一忙它就分不到 CPU，adb 全挂。
    # 不给 -dns-server：模拟器用宿主的解析，查询跟着宿主的代理走；指定外部 DNS 会绕开代理，解析被篡改。
    # 每次冷启动（-no-snapshot-load）：从快照恢复会沿用快照里的网络状态与解析缓存，启动参数改了也不生效。
    nohup "$EMULATOR" -avd "$avd" -port "${serial#emulator-}" -no-snapshot-load \
        < /dev/null > "$LOCK_ROOT/chogumi-$serial.log" 2>&1 &
    until online "$serial"; do
        if (( waited >= BOOT_TIMEOUT_SECONDS )); then
            die "$serial 等了 ${BOOT_TIMEOUT_SECONDS} 秒还没开机完成，日志：$LOCK_ROOT/chogumi-$serial.log"
        fi
        sleep 3
        waited=$((waited + 3))
    done
    echo "$serial 已开机"
}

case "${1:-}" in
    status) cmd_status ;;
    claim) shift; cmd_claim "$@" ;;
    release) shift; cmd_release "$@" ;;
    start) shift; cmd_start "$@" ;;
    *) die "用法：$0 status | claim \"<线程，分支>\" [设备] | release <设备> \"<线程，分支>\" | start <设备>" ;;
esac
