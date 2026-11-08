"""GitHub Actions 外部引用门禁的违规与放行样例。"""

from __future__ import annotations

from pathlib import Path
import tempfile
import unittest

from check_action_pins import find_violations


SHA = "fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09"
DOCKER_SHA = "a" * 64


class CheckActionPinsTest(unittest.TestCase):

    def violations(self, files: dict[str, str]) -> list[str]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name, content in files.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")
            return find_violations(root)

    def workflow(self, steps: str) -> str:
        return (
            "name: 测试\non: push\npermissions:\n  contents: read\njobs:\n"
            "  test:\n    runs-on: ubuntu-latest\n    steps:\n" + steps
        )

    def test外部的Action必须是四十位提交SHA(self):
        found = self.violations({
            ".github/workflows/test.yml": self.workflow(
                "      - uses: actions/checkout@v5\n"
                "        with:\n"
                "          persist-credentials: false\n"
                "      - uses: owner/repo/path@0123456\n"
                f"      - uses: owner/repo@{'b' * 40}\n"),
        })
        self.assertEqual(2, len(found), found)
        self.assertTrue(any("actions/checkout@v5" in item for item in found), found)
        self.assertTrue(all("四十位" in item for item in found), found)
        self.assertTrue(any("0123456" in item for item in found), found)

    def test本地引用放行(self):
        found = self.violations({
            ".github/workflows/test.yml": self.workflow("      - uses: ./.github/actions/setup-build\n"),
            ".github/actions/setup-build/action.yml": "name: 构建\nruns:\n  using: composite\n  steps: []\n",
        })
        self.assertEqual([], found)

    def testcomposite_action也扫描(self):
        found = self.violations({
            ".github/workflows/test.yml": self.workflow("      - uses: ./.github/actions/setup-build\n"),
            ".github/actions/setup-build/action.yml": (
                "name: 构建\nruns:\n  using: composite\n  steps:\n"
                "    - uses: actions/setup-java@v5\n"
            ),
        })
        self.assertEqual(1, len(found), found)
        self.assertTrue(found[0].startswith(".github/actions/setup-build/action.yml:"), found)

    def testcheckout必须明确关闭凭据持久化(self):
        found = self.violations({
            ".github/workflows/test.yml": self.workflow(
                f"      - uses: actions/checkout@{SHA}\n"
                f"      - uses: actions/checkout@{SHA}\n"
                "        with:\n"
                "          persist-credentials: true\n"
                f"      - uses: actions/checkout@{SHA}\n"
                "        id: first\n"
                "        with:\n"
                "          persist-credentials: false\n"),
        })
        self.assertEqual(2, len(found), found)
        self.assertTrue(all("persist-credentials: false" in item for item in found))

    def testcheckout的false可加引号和注释(self):
        found = self.violations({
            ".github/workflows/test.yml": self.workflow(
                f"      - uses: actions/checkout@{SHA}\n"
                "        with:\n"
                "          persist-credentials: \"false\" # 明文只是为了注释也不落盘\n"),
        })
        self.assertEqual([], found)

    def test引号与块标量里的uses仍按真实值检查(self):
        found = self.violations({
            ".github/workflows/test.yml": self.workflow(
                "      - uses: \"actions/checkout@v5\" # 引号不能绕过\n"
                "        with:\n"
                "          persist-credentials: false\n"
                "      - uses: >-\n"
                "          owner/repo@v1\n"
                "      - 'uses': |\n"
                "          owner/other@0123456\n"),
        })
        self.assertEqual(3, len(found), found)

    def test注释与run块里的uses不是Action(self):
        found = self.violations({
            ".github/workflows/test.yml": self.workflow(
                "      # - uses: actions/checkout@v5\n"
                "      - name: 打印\n"
                "        run: |\n"
                "          echo uses: actions/checkout@v5\n"
                "          - uses: owner/repo@v1\n"
                "      - name: 单行\n"
                "        run: echo 'uses: actions/checkout@v5'\n"),
        })
        self.assertEqual([], found)

    def test流式写法不能用uses键绕过(self):
        found = self.violations({
            ".github/workflows/test.yml": self.workflow(
                "      - {uses: actions/checkout@v5}\n"
                "      - {\"uses\": actions/setup-node@v5}\n"
            ),
        })
        self.assertEqual(2, len(found), found)
        self.assertTrue(all("块结构" in item for item in found), found)

    def testreusable_workflow与docker引用按不可变引用检查(self):
        found = self.violations({
            ".github/workflows/test.yml": (
                "name: 测试\non: push\npermissions:\n  contents: read\njobs:\n"
                "  pinned:\n"
                f"    uses: owner/repo/.github/workflows/a.yml@{SHA}\n"
                "  floating:\n"
                "    uses: owner/repo/.github/workflows/a.yml@main\n"
            ),
            ".github/workflows/docker.yml": self.workflow(
                f"      - uses: docker://alpine@sha256:{DOCKER_SHA}\n"
                "      - uses: docker://alpine:3\n"),
        })
        self.assertEqual(2, len(found), found)
        self.assertTrue(any("reusable" in item for item in found), found)
        self.assertTrue(any("digest" in item for item in found), found)

    def test缺少引用与非法引用都拒绝(self):
        found = self.violations({
            ".github/workflows/test.yml": self.workflow(
                "      - uses: owner/repo\n"
                "      - uses: http://example.test/action@v1\n"
                f"      - uses: owner/repo@{SHA.upper()}\n"),
        })
        self.assertEqual(2, len(found), found)
        self.assertTrue(any("缺少" in item for item in found), found)
        self.assertTrue(any("协议" in item for item in found), found)

    def test默认只读且只有release_job可写(self):
        allowed = self.violations({
            ".github/workflows/release.yml": (
                "name: Release\non: push\npermissions:\n  contents: read\njobs:\n"
                "  build:\n    permissions: {}\n    runs-on: ubuntu-latest\n    steps: []\n"
                "  test:\n    permissions:\n      contents: read\n    runs-on: ubuntu-latest\n    steps: []\n"
                "  release:\n    permissions:\n      contents: write\n    runs-on: ubuntu-latest\n    steps: []\n"
            ),
        })
        self.assertEqual([], allowed)
        found = self.violations({
            ".github/workflows/ci.yml": (
                "name: CI\non: push\npermissions:\n  contents: write\njobs:\n"
                "  test:\n    runs-on: ubuntu-latest\n    steps: []\n"
            ),
            ".github/workflows/other.yml": (
                "name: 其他\non: push\npermissions:\n  contents: read\njobs:\n"
                "  publish:\n    permissions:\n      contents: write\n    runs-on: ubuntu-latest\n    steps: []\n"
            ),
        })
        self.assertEqual(2, len(found), found)

    def test仓内local_action沿workflow实际引用递归检查(self):
        found = self.violations({
            ".github/workflows/test.yml": self.workflow("      - uses: ./tools/actions/build\n"),
            "tools/actions/build/action.yml": (
                "name: 构建\ndescription: 本地构建\nruns:\n  using: composite\n  steps:\n"
                "    - uses: ./tools/actions/checkout-wrapper\n"
            ),
            "tools/actions/checkout-wrapper/action.yml": (
                "name: checkout\ndescription: 本地 checkout\nruns:\n  using: composite\n  steps:\n"
                "    - uses: actions/checkout@v5\n"
            ),
        })
        self.assertEqual(2, len(found), found)
        self.assertTrue(all(item.startswith("tools/actions/checkout-wrapper/action.yml:") for item in found), found)

    def testdocker_local_action的镜像同样要钉digest(self):
        workflow = self.workflow("      - uses: ./.github/actions/tool\n")
        for image, expected in (
            ("docker://alpine:3", 1),
            (f"docker://alpine@sha256:{DOCKER_SHA}", 0),
            ("Dockerfile", 0),
        ):
            with self.subTest(image=image):
                found = self.violations({
                    ".github/workflows/test.yml": workflow,
                    ".github/actions/tool/action.yml": f"name: 工具\nruns:\n  using: docker\n  image: '{image}'\n",
                })
                self.assertEqual(expected, len(found), found)
                if expected:
                    self.assertTrue(found[0].startswith(".github/actions/tool/action.yml:4:"), found)

    def testsame_repository_dollar_local_action被解析(self):
        found = self.violations({
            ".github/workflows/test.yml": self.workflow("      - uses: $/.github/actions/setup-build\n"),
            ".github/actions/setup-build/action.yml": (
                "name: 构建\ndescription: 本地构建\nruns:\n  using: composite\n  steps: []\n"
            ),
        })
        self.assertEqual([], found)

    def teststandalone_dash是独立step边界(self):
        found = self.violations({
            ".github/workflows/test.yml": self.workflow(
                "      -\n"
                "        name: first\n"
                f"        uses: actions/checkout@{SHA}\n"
                "      -\n"
                "        name: second\n"
                f"        uses: actions/checkout@{SHA}\n"
                "        with:\n"
                "          persist-credentials: false\n"
            ),
        })
        self.assertEqual(1, len(found), found)
        self.assertIn("persist-credentials: false", found[0])

    def testflow_job必须明确拒绝不能隐藏job权限(self):
        found = self.violations({
            ".github/workflows/test.yml": (
                "name: 测试\non: push\npermissions:\n  contents: read\njobs:\n"
                "  test: {runs-on: ubuntu-latest, permissions: {contents: write}, steps: [{run: echo ok}]}\n"
            ),
        })
        self.assertEqual(1, len(found), found)
        self.assertIn("必须使用块结构", found[0])

    def testenv与with里的uses不是step_reference(self):
        found = self.violations({
            ".github/workflows/test.yml": (
                "name: 测试\non: push\npermissions:\n  contents: read\nenv:\n  uses: production\njobs:\n"
                "  test:\n    runs-on: ubuntu-latest\n    steps:\n"
                "      - uses: actions/upload-artifact@330a01c490aca151604b8cf639adc76d48f6c5d4\n"
                "        with:\n          uses: production\n"
            ),
        })
        self.assertEqual([], found)

    def test动态uses拒绝且未引用local_action不扫描(self):
        found = self.violations({
            ".github/workflows/test.yml": self.workflow(
                "      - uses: ${{ inputs.action }}\n"
            ),
            "tools/actions/unused/action.yml": (
                "name: 未使用\ndescription: 未使用\nruns:\n  using: composite\n  steps:\n"
                "    - uses: actions/checkout@v5\n"
            ),
        })
        self.assertEqual(1, len(found), found)
        self.assertIn("动态 uses", found[0])

    def testlocal_action路径逃逸与缺失metadata都拒绝(self):
        found = self.violations({
            ".github/workflows/test.yml": self.workflow(
                "      - uses: ./../outside/action\n"
                "      - uses: ./tools/actions/missing\n"
            ),
        })
        self.assertEqual(2, len(found), found)
        self.assertTrue(any("逃出仓库" in item for item in found), found)
        self.assertTrue(any("缺少 action.yml" in item for item in found), found)


if __name__ == "__main__":
    unittest.main()
