"""设备锁脚本的自测（tools/ci/check.sh lint 运行）：用假 adb 与临时锁目录跑 device.sh。"""

from pathlib import Path
import os
import re
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parent / "device.sh"
TOOLS = SCRIPT.parent.parent

# 变量名后直接跟中文：macOS 自带的 bash 3.2 在 UTF-8 环境里把高位字节也当变量名的字母，
# "「$owner」" 读成变量 owner\xe3，set -u 下报未定义，错误信息里还夹着半个字符。
BARE_VAR_BEFORE_NON_ASCII = re.compile(r"\$[A-Za-z_][A-Za-z0-9_]*[^\x00-\x7f]")

# 假 adb：FAKE_ONLINE 里列出的设备在线且开机完成，其余离线。
FAKE_ADB = """#!/usr/bin/env bash
serial="$2"; shift 2
case " $FAKE_ONLINE " in *" $serial "*) ;; *) exit 1 ;; esac
case "$*" in
    get-state) echo device ;;
    "shell getprop sys.boot_completed") echo 1 ;;
esac
"""


class DeviceScriptTest(unittest.TestCase):

    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        adb = self.root / "adb"
        adb.write_text(FAKE_ADB, encoding="utf-8")
        adb.chmod(0o755)
        self.locks = self.root / "locks"
        self.locks.mkdir()
        self.env = {
            **os.environ,
            "CHOGUMI_ADB": str(adb),
            "CHOGUMI_LOCK_ROOT": str(self.locks),
            "FAKE_ONLINE": "emulator-5554 emulator-5556",
        }

    def run_script(self, *args: str) -> subprocess.CompletedProcess:
        return subprocess.run(["bash", str(SCRIPT), *args], env=self.env, capture_output=True, text=True)

    def lock_names(self) -> set[str]:
        return {p.name for p in self.locks.iterdir() if p.is_dir()}

    def test_依次拿到空闲设备_都占满后拿不到(self):
        first = self.run_script("claim", "线程甲，分支 a")
        second = self.run_script("claim", "线程乙，分支 b")
        third = self.run_script("claim", "线程丙，分支 c")
        self.assertEqual(("emulator-5554", 0), (first.stdout.strip(), first.returncode))
        self.assertEqual(("emulator-5556", 0), (second.stdout.strip(), second.returncode))
        self.assertEqual(1, third.returncode)
        self.assertEqual("", third.stdout)
        self.assertIn("线程乙，分支 b", third.stderr)

    def test_一台一把锁_自己释放后可再拿(self):
        self.assertEqual("emulator-5554", self.run_script("claim", "线程甲").stdout.strip())
        self.assertEqual({"chogumi-device-emulator-5554.lock"}, self.lock_names())
        self.assertEqual(0, self.run_script("release", "emulator-5554", "线程甲").returncode)
        self.assertEqual(set(), self.lock_names())
        self.assertEqual("emulator-5554", self.run_script("claim", "线程乙").stdout.strip())

    def test_释放别人的锁失败且锁还在(self):
        self.assertEqual("emulator-5554", self.run_script("claim", "线程甲，分支 a").stdout.strip())
        other = self.run_script("release", "emulator-5554", "线程乙，分支 b")
        self.assertEqual(1, other.returncode)
        self.assertIn("线程甲，分支 a", other.stderr)
        prefix = self.run_script("release", "emulator-5554", "线程甲")
        self.assertEqual(1, prefix.returncode, "占用者名字只是前缀相同也不算同一个")
        self.assertEqual({"chogumi-device-emulator-5554.lock"}, self.lock_names())

    def test_占用记录的时间与语言环境无关(self):
        self.env["LC_ALL"] = "zh_CN.UTF-8"
        self.assertEqual("emulator-5554", self.run_script("claim", "线程甲").stdout.strip())
        owner = (self.locks / "chogumi-device-emulator-5554.lock" / "owner").read_text(encoding="utf-8")
        self.assertRegex(owner, r"^owner: 线程甲, 设备 emulator-5554, \d{4}-\d\d-\d\d \d\d:\d\d:\d\d [+-]\d{4}\n$")

    def test_脚本里紧跟非ASCII字符的变量都带花括号(self):
        bare = [f"{path.relative_to(TOOLS)}:{number}: {line.strip()}"
                for path in sorted(TOOLS.rglob("*.sh"))
                for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1)
                if BARE_VAR_BEFORE_NON_ASCII.search(line)]
        self.assertEqual([], bare)

    def test_释放没占着的设备什么都不做(self):
        self.assertEqual(0, self.run_script("release", "emulator-5556", "线程甲").returncode)

    def test_离线设备不发(self):
        self.env["FAKE_ONLINE"] = "emulator-5556"
        self.assertEqual("emulator-5556", self.run_script("claim", "线程甲").stdout.strip())
        self.assertEqual(1, self.run_script("claim", "线程乙").returncode)

    def test_指定设备只拿那一台(self):
        self.assertEqual("emulator-5556", self.run_script("claim", "线程甲", "emulator-5556").stdout.strip())
        busy = self.run_script("claim", "线程乙", "emulator-5556")
        self.assertEqual(1, busy.returncode)
        self.assertEqual({"chogumi-device-emulator-5556.lock"}, self.lock_names())

    def test_池外设备与缺占用者都拒绝(self):
        self.assertEqual(2, self.run_script("claim", "线程甲", "emulator-5560").returncode)
        self.assertEqual(2, self.run_script("claim").returncode)
        self.assertEqual(2, self.run_script("release", "emulator-5560", "线程甲").returncode)
        self.assertEqual(2, self.run_script("release", "emulator-5554").returncode)
        self.assertEqual(set(), self.lock_names())


if __name__ == "__main__":
    unittest.main()
