"""正式发布的失败边界：不使用真实密钥，不把 benchmark/debug 当正式产物，不混用 R8 mapping。"""
from pathlib import Path
import importlib.util
import json
import os
import subprocess
import tempfile
import unittest
import zipfile


SCRIPT = Path(__file__).resolve().parents[1] / "release/formal_release.py"
SPEC = importlib.util.spec_from_file_location("formal_release", SCRIPT)
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)


class FormalReleaseTest(unittest.TestCase):
    def metadata(self, variant="release"):
        return {"applicationId": "io.github.touhougo.chogumi", "variantName": variant,
                "artifactType": {"type": "APK"},
                "elements": [{"filters": [], "versionName": "0.1.0", "versionCode": 100,
                              "outputFile": "chogumi-0.1.0-release-unsigned.apk"}]}

    def test_正式元数据保留唯一版本(self):
        value = release.release_metadata(self.metadata())
        self.assertEqual("0.1.0", value["versionName"])
        self.assertEqual(100, value["versionCode"])
        self.assertEqual("io.github.touhougo.chogumi", value["applicationId"])

    def test_测量包与调试包不能伪装正式版本(self):
        for variant in ("benchmarkRelease", "debug"):
            with self.subTest(variant=variant), self.assertRaises(release.ReleaseError):
                release.release_metadata(self.metadata(variant))

    def test_输出文件不能逃离正式产物目录(self):
        metadata = self.metadata()
        metadata["elements"][0]["outputFile"] = "../benchmark/app.apk"
        with self.assertRaises(release.ReleaseError):
            release.release_metadata(metadata)

    def test_发布证书指纹不一致就拒绝(self):
        digest = "a" * 64
        self.assertEqual(digest, release.certificate_digest(
            f"Signer #1 certificate SHA-256 digest: {digest.upper()}", digest))
        with self.assertRaises(release.ReleaseError):
            release.certificate_digest(f"Signer #1 certificate SHA-256 digest: {'b' * 64}", digest)

    def test_新版apksigner按签名方案分行输出也认得出指纹(self):
        digest = "a" * 64
        output = (f"V3.0 Signer: certificate DN: CN=Chogumi, O=TouhouGO\n"
                  f"V3.0 Signer: certificate SHA-256 digest: {digest}\n"
                  f"V3.0 Signer: certificate SHA-1 digest: {'c' * 40}\n")
        self.assertEqual(digest, release.certificate_digest(output, digest))
        with self.assertRaises(release.ReleaseError):
            release.certificate_digest(output, "b" * 64)

    def test_多个签名证书不同就拒绝(self):
        digest = "a" * 64
        output = (f"V2 Signer: certificate SHA-256 digest: {digest}\n"
                  f"V3.0 Signer: certificate SHA-256 digest: {'b' * 64}\n")
        with self.assertRaises(release.ReleaseError):
            release.certificate_digest(output, digest)

    def test_即使指纹匹配也拒绝调试证书(self):
        digest = "a" * 64
        with self.assertRaises(release.ReleaseError):
            release.certificate_digest(
                f"Signer #1 certificate DN: CN=Android Debug,O=Android,C=US\n"
                f"Signer #1 certificate SHA-256 digest: {digest}", digest)
        with self.assertRaises(release.ReleaseError):
            release.certificate_digest(
                f"V3.0 Signer: certificate DN: CN=Android Debug, O=Android, C=US\n"
                f"V3.0 Signer: certificate SHA-256 digest: {digest}", digest)

    def test_归档映射必须属于APK中的同次R8构建(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            apk, mapping = root / "app.apk", root / "mapping.txt"
            marker = json.dumps({"pg-map-id": "matching", "compilation-mode": "release"})
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("classes.dex", b"dex\x00~~R8" + marker.encode() + b"\x00")
            mapping.write_text("# compiler: R8\n# pg_map_id: matching\n", encoding="utf-8")
            self.assertEqual("matching", release.verify_mapping(apk, mapping))
            mapping.write_text("# compiler: R8\n# pg_map_id: stale\n", encoding="utf-8")
            with self.assertRaises(release.ReleaseError):
                release.verify_mapping(apk, mapping)

    def test_受控配置不能写入口令值(self):
        with tempfile.TemporaryDirectory() as temp:
            config = Path(temp) / "release.json"
            config.write_text(json.dumps({"storePassword": "禁止内联"}), encoding="utf-8")
            config.chmod(0o600)
            with self.assertRaises(release.ReleaseError):
                release.load_config(str(config), [Path(temp) / "repository"])

    def test_配置与安全文件须仓库外且只由当前用户读取(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            config = root / "release.json"
            config.write_text("{}", encoding="utf-8")
            config.chmod(0o644)
            with self.assertRaises(release.ReleaseError):
                release.private_file(str(config), [root / "repo"], "配置")
            config.chmod(0o600)
            with self.assertRaises(release.ReleaseError):
                release.private_file(str(config), [root], "配置")
            self.assertEqual(config.resolve(), release.private_file(str(config), [root / "repo"], "配置"))

    def test_缺少材料时在构建之前明确拒绝(self):
        env = dict(os.environ)
        env.pop("CHOGUMI_RELEASE_CONFIG", None)
        result = subprocess.run(["python3", "-I", str(SCRIPT)], env=env, capture_output=True, text=True)
        self.assertEqual(2, result.returncode)
        self.assertIn("缺少 CHOGUMI_RELEASE_CONFIG", result.stderr)
        self.assertNotIn("gradlew", result.stdout)


if __name__ == "__main__":
    unittest.main()
