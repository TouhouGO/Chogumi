"""设备名门禁（唯一实现处；tools/ci/check.sh lint 运行，单跑：python3 tools/guards/check_device_names.py）。

本机有一个模拟器池，设备名、AVD 名和设备锁路径只写在 tools/device/device.sh。文档或脚本里写死一台设备
（「在 emulator-5554 上跑」「mkdir 某把锁」），读到的会话就会全挤到那一台上排队，另一台闲着；锁路径写死一份，
拿锁的规矩就有了第二种写法，两边互不认账。文档只说「用 tools/device/device.sh claim 拿到的那台」。

检查项（仓库里被跟踪的源码、构建脚本、工作流、工具与说明文档，后缀见 SUFFIXES）：
1. 模拟器设备名（emulator- 加端口号）、池里的 AVD 名、设备锁路径。AVD 名与锁路径从 device.sh 的设备池与 lock_dir 里读，
   池子换机型时这里不用跟着改。
2. 脚本（.sh / .py / .kts / .mjs / .js）里的 adb 命令行必须钉设备（`-s`，列表参数里的 `"-s"` 也算，或 `ANDROID_SERIAL`）：
   本机常同时挂着不该碰的设备，不钉就装错、截错。经变量调用的 adb（`"$ADB"`、`"$adb_bin"`）同样算命令；shell 脚本
   先用 `: "${ANDROID_SERIAL:?…}"` 要求设备已设的，整份脚本的 adb 都从环境取到那台，不再逐行查。
   说明文档里的 adb 示例由上下文交代先 claim 再带 ANDROID_SERIAL，不按行查。

不查：符号链接（目标文件本身已经在查）、tools/device（池本身与它的自测）。
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

SUFFIXES = {".kt", ".kts", ".py", ".mjs", ".js", ".md", ".xml", ".toml", ".properties", ".sh", ".pro", ".yml", ".yaml"}
EXCLUDED = ("tools/device/",)
SELF = "tools/guards/check_device_names.py"
SELF_TEST = "tools/guards/test_check_device_names.py"

POOL_SCRIPT = "tools/device/device.sh"
POOL_ENTRY = re.compile(r'^\s*"(emulator-[0-9]+)\s+(\w+)"', re.M)
LOCK_PATH = re.compile(r'^lock_dir\(\)\s*\{\s*echo\s+"\$LOCK_ROOT/([\w-]+)\$', re.M)
SCRIPT_SUFFIXES = {".sh", ".py", ".kts", ".mjs", ".js"}
# adb 本身，或经变量调用（`"$ADB"`、`${ADB}`、`"$adb_bin"`）
ADB = re.compile(r"(?<![\w.-])adb\b(?!\.)|\$\{?(?:\w*_)?(?i:adb)(?:_\w*)?\}?(?!\w)")
URL = re.compile(r"[A-Za-z][A-Za-z0-9+.-]*://[^\s;&|`'\"]+")
PINNED = re.compile(r"(?<![\w-])-s(?![\w-])|ANDROID_SERIAL")
# shell 脚本先要求 ANDROID_SERIAL 已设（`: "${ANDROID_SERIAL:?…}"`）：之后每条 adb 都从环境里取到这台设备
SCRIPT_REQUIRES_SERIAL = re.compile(r"\$\{ANDROID_SERIAL:\?")
HINT = "设备名与锁路径只在 tools/device/device.sh；文档写「tools/device/device.sh claim 拿到的那台」，命令带 ANDROID_SERIAL"


def device_pattern(root: Path) -> re.Pattern[str] | None:
    """设备名（emulator- 加端口号）、池里的 AVD 名与锁路径前缀，从 device.sh 读；读不到为 None。"""
    pool = root / POOL_SCRIPT
    if not pool.is_file():
        return None
    text = pool.read_text(encoding="utf-8")
    avds = [m.group(2) for m in POOL_ENTRY.finditer(text)]
    lock = LOCK_PATH.search(text)
    if not avds or not lock:
        return None
    names = [r"emulator-[0-9]{4}", re.escape(lock.group(1)), *(re.escape(a) + r"\b" for a in sorted(avds, key=len, reverse=True))]
    return re.compile("|".join(names))


def tracked_files(root: Path) -> list[str]:
    return subprocess.run(["git", "ls-files"], cwd=root, capture_output=True, text=True, check=True).stdout.splitlines()


def find_violations(root: Path, files: list[str]) -> list[str]:
    pattern = device_pattern(root)
    if pattern is None:
        return [f"{POOL_SCRIPT}: 读不出设备池（POOL 里的「设备名 AVD 名」）或 lock_dir 的锁路径——门禁靠它们认设备名"]
    violations: list[str] = []
    for rel in files:
        if rel.startswith(EXCLUDED) or rel in (SELF, SELF_TEST) or Path(rel).suffix not in SUFFIXES:
            continue
        path = root / rel
        if path.is_symlink() or not path.is_file():
            continue
        text = path.read_text(encoding="utf-8")
        lines = text.splitlines()
        pinned_script = rel.endswith(".sh") and SCRIPT_REQUIRES_SERIAL.search(text) is not None
        for i, line in enumerate(lines):
            for m in pattern.finditer(line):
                violations.append(f"{rel}:{i + 1}: 写死了「{m.group(0)}」——{HINT}")
            command_line = URL.sub(lambda match: " " * len(match.group()), line)
            if pinned_script or Path(rel).suffix not in SCRIPT_SUFFIXES or not ADB.search(command_line):
                continue
            start = i
            while start > 0 and lines[start - 1].rstrip().endswith("\\"):
                start -= 1
            end = i + 1
            while end < len(lines) and lines[end - 1].rstrip().endswith("\\"):
                end += 1
            command = "\n".join(lines[start:end])
            if not PINNED.search(command):
                violations.append(
                    f"{rel}:{i + 1}: adb 没钉设备——先 tools/device/device.sh claim，命令带 ANDROID_SERIAL=$SERIAL（或 -s）"
                )
    return violations


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    violations = find_violations(root, tracked_files(root))
    for v in violations:
        print(v, file=sys.stderr)
    if violations:
        print(f"\n设备名门禁未通过：{len(violations)} 处", file=sys.stderr)
        return 1
    print("设备名门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
