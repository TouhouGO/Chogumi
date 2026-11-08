#!/usr/bin/env python3
"""把 atrace 文本（perf-cold-start.sh 的 atrace.txt）里应用主线程的 slice 按冷启动阶段拆开。

用法：perf-atrace-split.py atrace.txt [--min-ms 8] [--slow-ms 50] [--pid PID]

时间零点是目标进程的 activityStart（第一个 Activity 开始启动），负数是它之前。输出四段：
  1. 主线程深度 0 到 2、时长不小于 --min-ms 的 slice（阶段总览）
  2. 主线程所有时长不小于 --slow-ms 的 slice
  3. bindApplication 内部（去掉打开 dex 的子项）不小于 1ms 的 slice
  4. 同进程其他线程上不小于 40ms 的顶层 slice
应用主线程用「bindApplication」所在进程识别，也可以用 --pid 指定。
"""
import argparse
import collections
import re
import sys

LINE = re.compile(r'^\s*(.+?)-(\d+)\s+\(\s*(\d+|-+)\)\s+\[(\d+)\]\s+\S+\s+([\d.]+): tracing_mark_write: (.*)$')


def parse(path):
    stacks = collections.defaultdict(list)
    slices = collections.defaultdict(list)  # tid -> [(名称, 起, 止, 深度)]
    marks = []  # (时间, 文字, 线程)：启动起点候选
    processes = {}  # tid -> tgid / B|pid；归属缺失或冲突的线程不算应用线程。
    bind_pid = None
    for line in open(path, errors='replace'):
        m = LINE.match(line)
        if not m:
            continue
        _, tid, tgid, _, ts, msg = m.groups()
        tid, ts = int(tid), float(ts)
        parts = msg.split('|', 2) if msg.startswith('B|') else []
        owners = {int(value) for value in (tgid, parts[1] if len(parts) > 1 else '') if value.isdigit()}
        if owners:
            owner = owners.pop() if len(owners) == 1 else None
            if tid in processes and processes[tid] != owner:
                owner = None
            processes[tid] = owner
        if parts:
            name = parts[2] if len(parts) > 2 else ''
            stacks[tid].append((name, ts))
            if name == 'bindApplication' and bind_pid is None:
                bind_pid = processes.get(tid)
            if name == 'activityStart' or name.startswith('launching'):
                marks.append((ts, name, tid))
        elif msg.startswith('E') and stacks[tid]:
            name, start = stacks[tid].pop()
            slices[tid].append((name, start, ts, len(stacks[tid])))
    return slices, marks, bind_pid, processes


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('trace')
    ap.add_argument('--min-ms', type=float, default=8)
    ap.add_argument('--slow-ms', type=float, default=50)
    ap.add_argument('--pid', type=int)
    args = ap.parse_args()

    slices, marks, bind_pid, processes = parse(args.trace)
    pid = args.pid or bind_pid
    if pid is None or pid not in slices:
        sys.exit('找不到应用主线程：trace 里没有可归属的 bindApplication（进程在开始记录前就已经在跑？）')
    target_marks = [(t, name) for t, name, tid in marks if processes.get(tid) == pid]
    if not target_marks:
        sys.exit('trace 里没有目标进程可归属的 activityStart，无法定时间零点')
    t0 = min(t for t, _ in target_marks)
    main_slices = sorted(slices[pid], key=lambda s: (s[1], s[3]))

    def rel(t):
        return (t - t0) * 1000

    print(f'应用主线程 pid {pid}，时间零点 activityStart；首个 slice 在 {rel(main_slices[0][1]):.1f} ms')
    print(f'--- 主线程深度 0 到 2、不小于 {args.min_ms:g} ms 的 slice（起 / 时长 ms）')
    for name, s, e, d in main_slices:
        if d <= 2 and (e - s) * 1000 >= args.min_ms:
            print(f'{rel(s):8.1f} {(e - s) * 1000:7.1f} {"  " * d}{name[:100]}')

    print(f'--- 主线程所有不小于 {args.slow_ms:g} ms 的 slice')
    for name, s, e, d in main_slices:
        if (e - s) * 1000 >= args.slow_ms:
            print(f'{rel(s):8.1f} {(e - s) * 1000:7.1f} d{d} {name[:100]}')

    bind = next((x for x in main_slices if x[0] == 'bindApplication'), None)
    if bind:
        dex_spans = [(s, e) for n, s, e, d in main_slices if d == 1 and n.startswith('/data/app/') and n.endswith('base.apk')]
        print('--- bindApplication 内不小于 1ms 的 slice（去掉打开 dex 的子项）')
        for name, s, e, d in main_slices:
            inside = bind[1] <= s <= bind[2]
            in_dex = any(a <= s <= b for a, b in dex_spans) and d >= 1
            if inside and (e - s) * 1000 >= 1 and not in_dex:
                print(f'{rel(s):8.1f} {(e - s) * 1000:7.1f} d{d} {name[:110]}')

    print('--- 同进程其他线程上不小于 40ms 的顶层 slice')
    for tid, items in slices.items():
        if tid == pid or processes.get(tid) != pid:
            continue
        for name, s, e, d in items:
            if d == 0 and (e - s) * 1000 >= 40:
                print(f'tid {tid} {rel(s):8.1f} {(e - s) * 1000:7.1f} {name[:90]}')
    unknown = sorted(tid for tid in slices if processes.get(tid) is None)
    if unknown:
        print('归属未知的线程未计入应用热点：' + ', '.join(map(str, unknown)))


if __name__ == '__main__':
    main()
