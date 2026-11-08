"""CI 发版落 Secrets：写出的那套文件被发布管线接受、只由当前用户可读，缺项只报名字不回显值。"""
from __future__ import annotations

import base64
from pathlib import Path
import importlib.util
import stat
import sys
import tempfile
import unittest

RELEASE = Path(__file__).resolve().parents[1] / "release"
sys.path.insert(0, str(RELEASE))
SPEC = importlib.util.spec_from_file_location("ci_release_config", RELEASE / "ci_release_config.py")
ci = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ci)
from new_release_key import CONFIG, KEY_PASS, KEYSTORE, STORE_PASS, load_config  # noqa: E402

DIGEST = "D4:C0:E3:51:84:AB:E5:54:F6:6C:0A:52:D5:08:B0:F0:85:68:1C:AA:61:2D:10:64:81:FA:59:8D:60:46:AA:9D"
SECRETS = {
    "CHOGUMI_RELEASE_KEYSTORE": base64.b64encode(b"keystore-bytes").decode(),
    "CHOGUMI_RELEASE_PASSWORD": "Release-Pass-123\n",
    "CHOGUMI_RELEASE_CERT_SHA256": DIGEST,
    "CHOGUMI_OAUTH_CLIENT_ID": "bgm1234",
    "CHOGUMI_OAUTH_CLIENT_SECRET": "secret5678",
}


class CiReleaseConfigTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.repo = self.root / "repo"
        self.repo.mkdir()
        self.build_tools = self.root / "build-tools/36.0.0"
        self.build_tools.mkdir(parents=True)
        for name in ("aapt2", "apksigner", "zipalign"):
            (self.build_tools / name).write_text("#!/bin/sh\n", encoding="utf-8")
            (self.build_tools / name).chmod(0o755)
        self.dir = self.root / "release"

    def tearDown(self):
        self.temp.cleanup()

    def write(self, **overrides):
        return ci.write_ci_release(self.dir, self.repo, {**SECRETS, **overrides}, self.build_tools, [self.repo])

    def test_写出的配置被发布管线接受(self):
        config = load_config(str(self.write()), [self.repo])
        self.assertEqual(DIGEST.replace(":", "").lower(), config["certificateSha256"])
        self.assertEqual(b"keystore-bytes", (self.dir / KEYSTORE).read_bytes())
        for name in (STORE_PASS, KEY_PASS):
            self.assertEqual("Release-Pass-123\n", (self.dir / name).read_text(encoding="utf-8"))
        self.assertEqual("CLIENT_ID=bgm1234\nCLIENT_SECRET=secret5678\n",
                         (self.repo / ci.OAUTH_FILE).read_text(encoding="utf-8"))

    def test_密钥口令配置与应用凭据只由当前用户可读(self):
        self.write()
        for path in [self.dir / name for name in (KEYSTORE, STORE_PASS, KEY_PASS, CONFIG)] + [self.repo / ci.OAUTH_FILE]:
            with self.subTest(path=path.name):
                self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))

    def test_缺一项只报它的名字(self):
        for name in SECRETS:
            with self.subTest(name=name):
                self.temp.cleanup()
                self.setUp()
                with self.assertRaises(ci.ReleaseError) as raised:
                    self.write(**{name: " "})
                self.assertIn(name, str(raised.exception))

    def test_错误信息不回显值(self):
        with self.assertRaises(ci.ReleaseError) as raised:
            self.write(CHOGUMI_OAUTH_CLIENT_SECRET="has space")
        self.assertNotIn("has space", str(raised.exception))

    def test_密钥不是base64就拒绝(self):
        with self.assertRaises(ci.ReleaseError):
            self.write(CHOGUMI_RELEASE_KEYSTORE="不是base64")

    def test_指纹格式不对就拒绝(self):
        with self.assertRaises(ci.ReleaseError):
            self.write(CHOGUMI_RELEASE_CERT_SHA256="D4:C0")

    def test_目录里已有东西就拒绝(self):
        self.dir.mkdir()
        (self.dir / "旧文件").touch()
        with self.assertRaises(ci.ReleaseError):
            self.write()
        self.assertFalse((self.repo / ci.OAUTH_FILE).exists())


if __name__ == "__main__":
    unittest.main()
