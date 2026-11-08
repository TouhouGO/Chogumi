"""设备名门禁的自测（tools/ci/check.sh lint 运行）。"""

from pathlib import Path
import tempfile
import unittest

from check_device_names import find_violations

POOL = """POOL=(
    "emulator-5554 Pixel_10_Pro_XL"
    "emulator-5556 Pixel_10_Pro_XL_2"
)

lock_dir() { echo "$LOCK_ROOT/chogumi-device-$1.lock"; }
"""


class CheckDeviceNamesTest(unittest.TestCase):

    def violations(self, files: dict[str, str]) -> list[str]:
        root = Path(tempfile.mkdtemp())
        for rel, text in {"tools/device/device.sh": POOL, **files}.items():
            path = root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        return find_violations(root, list(files))

    def test_文档写死设备名拦下(self):
        found = self.violations({"AGENTS.md": "跑仪器测试：`ANDROID_SERIAL=emulator-5554 ./gradlew …`\n"})
        self.assertEqual(1, len(found))
        self.assertIn("AGENTS.md:1", found[0])
        self.assertIn("tools/device/device.sh", found[0])

    def test_锁路径与_AVD_名拦下(self):
        doc = "先开 Pixel_10_Pro_XL\n再开 Pixel_10_Pro_XL_2\n锁 /tmp/chogumi-device-x.lock\n"
        self.assertEqual(3, len(self.violations({"docs/dev/guide.md": doc})))

    def test_AVD_名与锁路径从设备池读(self):
        pool = POOL.replace("Pixel_10_Pro_XL_2", "Medium_Phone").replace("chogumi-device-", "pool-lock-")
        found = self.violations({"tools/device/device.sh": pool, "docs/dev/guide.md": "开 Medium_Phone，锁 /tmp/pool-lock-a\n"})
        self.assertEqual(2, len(found))

    def test_读不出设备池拦下(self):
        found = self.violations({"tools/device/device.sh": "POOL=()\n"})
        self.assertEqual(1, len(found))
        self.assertIn("设备池", found[0])

    def test_脚本里没钉设备的_adb_拦下(self):
        script = 'ADB="adb"\nadb shell screencap -p /sdcard/a.png\n'
        self.assertEqual(2, len(self.violations({"tools/shot/shot.sh": script})))

    def test_钉了设备的_adb_与说明文档里的示例放行(self):
        files = {
            "tools/shot/shot.sh": 'ANDROID_SERIAL="$SERIAL" adb shell wm size\n"$ADB" -s "$SERIAL" install -r app.apk\n',
            "AGENTS.md": "在拿到的设备上：`adb shell wm size reset`\n",
            "core/a/src/main/A.kt": 'val tool = "adb.exe"\n',
        }
        self.assertEqual([], self.violations(files))

    def test_adb完整路径同样拦下(self):
        found = self.violations({"tools/shot/shot.sh": "/opt/android/platform-tools/adb install app.apk\n"})
        self.assertEqual(1, len(found))
        self.assertIn("adb 没钉设备", found[0])

    def test_跨续行的设备绑定放行(self):
        files = {
            "tools/shot/shot.sh": 'ANDROID_SERIAL="$SERIAL" \\\n  adb shell wm size\n',
            "tools/shot/pinned.sh": "adb \\\n  -s \"$SERIAL\" install app.apk\n",
        }
        self.assertEqual([], self.violations(files))

    def test_URL路径里的_adb_不算命令(self):
        files = {
            "tools/shot/curl.sh": "curl https://example.invalid/adb\n",
            "tools/shot/path.sh": "echo https://example.invalid/files/adb?mode=check\n",
        }
        self.assertEqual([], self.violations(files))

    def test_列表参数里的_s_算钉了设备(self):
        files = {
            "tools/shot/shot.py": 'subprocess.run(["adb", "-s", serial, "shell", "ls"], check=True)\n',
            "build-logic/x.kts": 'commandLine("adb", "-s", serial, "install", apk)\n',
        }
        self.assertEqual([], self.violations(files))

    def test_经变量调用的_adb_同样要钉设备(self):
        self.assertEqual([], self.violations({"tools/shot/shot.sh": 'echo "$LOADBALANCER"\n'}))
        for line in ('"$ADB" shell rm -r /sdcard/a\n', "${ADB} install app.apk\n", '"$adb_bin" pull /sdcard/a.png\n'):
            with self.subTest(line=line):
                found = self.violations({"tools/shot/shot.sh": line})
                self.assertEqual(1, len(found), found)
                self.assertIn("adb 没钉设备", found[0])

    def test_脚本开头要求_ANDROID_SERIAL_时整份脚本算钉了设备(self):
        script = (
            ': "${ANDROID_SERIAL:?先 claim 一台}"\n'
            'adb_bin="${ADB:-adb}"\n'
            'adb_() { "$adb_bin" "$@"; }\n'
            "adb shell input keyevent KEYCODE_HOME\n"
        )
        self.assertEqual([], self.violations({"tools/perf/cold.sh": script}))

    def test_工作流里写死设备名拦下(self):
        found = self.violations({".github/workflows/ci.yml": "      - run: adb -s emulator-5554 wait-for-device\n"})
        self.assertEqual(1, len(found), found)

    def test_代码注释同样拦下(self):
        self.assertEqual(1, len(self.violations({"feature/a/src/androidTest/ATest.kt": "// emulator-5556 实跑踩过\n"})))

    def test_设备池目录不拦(self):
        files = {
            "tools/device/notes.md": "持 /tmp/chogumi-emulator.lock 实测\n",
        }
        self.assertEqual([], self.violations(files))

    def test_泛称模拟器不拦(self):
        self.assertEqual([], self.violations({"AGENTS.md": "在模拟器上跑；颜色 0xFF555555\n"}))


if __name__ == "__main__":
    unittest.main()
