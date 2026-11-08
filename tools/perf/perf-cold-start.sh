#!/usr/bin/env bash
# 冷启动测量：N 次 `am start -W` 取 TotalTime，另抓一次 atrace 文本供 perf-atrace-split.py 拆主线程各段。
# 设备只从环境变量 ANDROID_SERIAL 取（先用 tools/device/device.sh claim 占一台，用完 release）。
# 用法：ANDROID_SERIAL=<设备> perf-cold-start.sh [输出目录，默认临时目录下的 chogumi-cold-start] [次数，默认 6]
#       [包名，默认 benchmark 包] [启动 Activity，默认取包的桌面入口]
# 启动快不快只看 benchmark 包（:app:assembleBenchmarkRelease，docs/dev/guide.md §4.2.1）：debug 包没有编译代码，量出来的是解释执行。
set -euo pipefail

: "${ANDROID_SERIAL:?先设置 ANDROID_SERIAL（设备由 tools/device/device.sh claim 给出）}"
out="${1:-${TMPDIR:-/tmp}/chogumi-cold-start}"
runs="${2:-6}"
pkg="${3:-io.github.touhougo.chogumi}"
adb_bin="${ADB:-adb}"
mkdir -p "$out"

adb_() { "$adb_bin" "$@"; }

activity="${4:-}"
if [ -z "$activity" ]; then
  component=$(adb_ shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$pkg" | tr -d '\r' | tail -1)
  activity="${component#*/}"
fi

echo "设备 $ANDROID_SERIAL 已运行：$(adb_ shell uptime | tr -d '\r')"
echo "客户机负载：$(adb_ shell cat /proc/loadavg | tr -d '\r')，宿主负载：$(sysctl -n vm.loadavg 2>/dev/null || uptime)"
echo "dexopt 状态：$(adb_ shell "dumpsys package $pkg | grep -A3 -i 'dexopt state' | grep -o 'status=[^]]*'" | tr -d '\r' | head -1)"

# 每次先回桌面、杀进程、等系统静下来，再计一次冷启动；TotalTime 到首帧画完为止
adb_ shell input keyevent KEYCODE_HOME
: > "$out/total-time.txt"
for i in $(seq 1 "$runs"); do
    adb_ shell am force-stop "$pkg"
    sleep 6
    t=$(adb_ shell am start -W -n "$pkg/$activity" | tr -d '\r' | awk '/TotalTime/ {print $2}')
    echo "$t" | tee -a "$out/total-time.txt" >/dev/null
    echo "第 $i 次 TotalTime ${t} ms"
    sleep 6
    adb_ shell input keyevent KEYCODE_HOME
done
python3 - "$out/total-time.txt" <<'PY'
import statistics, sys
v = [int(x) for x in open(sys.argv[1]).read().split()]
print(f"共 {len(v)} 次，中位数 {statistics.median(v):g} ms，均值 {statistics.mean(v):.0f} ms，最小 {min(v)}，最大 {max(v)}")
PY

# 再抓一次 atrace 文本（冷启动前 3 秒开始记，共 9 秒）
adb_ shell am force-stop "$pkg"
adb_ shell input keyevent KEYCODE_HOME
sleep 5
adb_ shell "atrace -b 65536 -t 9 -a $pkg am wm view gfx input res dalvik pm binder_driver sched freq -o /data/local/tmp/cold-start-atrace.txt" >/dev/null 2>&1 &
sleep 3
adb_ shell am start -W -n "$pkg/$activity" | tr -d '\r' | awk '/TotalTime/ {print "atrace 那一次 TotalTime " $2 " ms"}'
wait
adb_ pull /data/local/tmp/cold-start-atrace.txt "$out/atrace.txt" >/dev/null
adb_ shell rm -f /data/local/tmp/cold-start-atrace.txt
echo "atrace 文本：$out/atrace.txt（用 perf-atrace-split.py 拆主线程各段）"
