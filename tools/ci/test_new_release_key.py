"""一次性生成正式密钥：配置能被发布管线接受、文件只由当前用户可读、绝不覆盖已有密钥、失败不留半套文件。"""
from __future__ import annotations

from pathlib import Path
import importlib.util
import json
import stat
import sys
import tempfile
import unittest

RELEASE = Path(__file__).resolve().parents[1] / "release"
sys.path.insert(0, str(RELEASE))
SPEC = importlib.util.spec_from_file_location("new_release_key", RELEASE / "new_release_key.py")
key = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(key)

KEYTOOL = key.find_keytool()


def fake_build_tools(root: Path) -> Path:
    tools = root / "build-tools/36.0.0"
    tools.mkdir(parents=True)
    for name in key.BUILD_TOOLS_REQUIRED:
        (tools / name).write_text("#!/bin/sh\n", encoding="utf-8")
        (tools / name).chmod(0o755)
    return tools


@unittest.skipIf(KEYTOOL is None, "没有 keytool")
class NewReleaseKeyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.repo = self.root / "repo"
        self.repo.mkdir()
        self.build_tools = fake_build_tools(self.root)
        self.dir = self.root / "release"

    def tearDown(self):
        self.temp.cleanup()

    def create(self, password="Release-Pass-123"):
        return key.create_release_key(self.dir, password, KEYTOOL, self.build_tools, [self.repo])

    def test_生成的配置被发布管线接受且指纹与密钥一致(self):
        config = self.create()
        written = json.loads((self.dir / key.CONFIG).read_text(encoding="utf-8"))
        self.assertEqual(config, written)
        loaded = key.load_config(str(self.dir / key.CONFIG), [self.repo])
        self.assertEqual(config["certificateSha256"].replace(":", "").lower(), loaded["certificateSha256"])
        self.assertEqual(key.KEY_ALIAS, loaded["keyAlias"])
        self.assertTrue((self.dir / key.ARCHIVE).is_dir())

    def test_密钥口令与配置只由当前用户可读(self):
        self.create()
        for name in (key.KEYSTORE, key.STORE_PASS, key.KEY_PASS, key.CONFIG):
            with self.subTest(name=name):
                self.assertEqual(0o600, stat.S_IMODE((self.dir / name).stat().st_mode))
        self.assertEqual("Release-Pass-123\n", (self.dir / key.STORE_PASS).read_text(encoding="utf-8"))
        self.assertNotIn("Release-Pass", (self.dir / key.CONFIG).read_text(encoding="utf-8"))

    def test_已有密钥时拒绝且不动旧文件(self):
        self.create()
        before = (self.dir / key.KEYSTORE).read_bytes()
        with self.assertRaises(key.ReleaseError):
            self.create("Another-Pass-456")
        self.assertEqual(before, (self.dir / key.KEYSTORE).read_bytes())
        self.assertEqual("Release-Pass-123\n", (self.dir / key.STORE_PASS).read_text(encoding="utf-8"))

    def test_口令太短或含非ASCII字符在生成之前拒绝(self):
        for password in ("12345", "正式口令-123", "has space1"):
            with self.subTest(password=password), self.assertRaises(key.ReleaseError):
                self.create(password)
        self.assertFalse(self.dir.exists())

    def test_放进仓库时拒绝并删掉这次生成的文件(self):
        self.dir = self.repo / "release"
        with self.assertRaises(key.ReleaseError):
            self.create()
        for name in (key.KEYSTORE, key.STORE_PASS, key.KEY_PASS, key.CONFIG):
            with self.subTest(name=name):
                self.assertFalse((self.dir / name).exists())

    def test_keytool写到一半失败时删掉残缺的密钥库再试能成功(self):
        broken = self.root / "broken-keytool"
        broken.write_text("#!/bin/sh\n"
                          "while [ \"$1\" != -keystore ]; do shift; done\n"
                          "printf partial > \"$2\"\n"
                          "exit 1\n", encoding="utf-8")
        broken.chmod(0o755)
        with self.assertRaises(key.ReleaseError):
            key.create_release_key(self.dir, "Release-Pass-123", broken, self.build_tools, [self.repo])
        for name in (key.KEYSTORE, key.STORE_PASS, key.KEY_PASS, key.CONFIG):
            with self.subTest(name=name):
                self.assertFalse((self.dir / name).exists())
        self.create()
        self.assertTrue((self.dir / key.CONFIG).is_file())


if __name__ == "__main__":
    unittest.main()
