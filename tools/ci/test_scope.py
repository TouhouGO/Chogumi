"""按改动选检查范围的自测（tools/ci/check.sh lint 运行）。"""

from pathlib import Path
import shutil
import tempfile
import unittest

from scope import FULL, REPO, scope, test_literals_of

DIRECTORIES = {":app": "app", ":core:ui": "core/ui", ":feature:profile": "feature/profile"}


class ScopeTest(unittest.TestCase):

    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root)
        for directory in [*DIRECTORIES.values(), "baselineprofile"]:
            (self.root / directory).mkdir(parents=True)
            (self.root / directory / "build.gradle.kts").touch()

    def level(self, *changed, literals=None):
        return scope(list(changed), DIRECTORIES, literals or {}, self.root)[:2]

    def test_只改模块里的文件就点名这些模块(self):
        self.assertEqual(("modules", [":core:ui", ":feature:profile"]),
                         self.level("feature/profile/src/main/kotlin/A.kt", "core/ui/src/main/kotlin/B.kt"))

    def test_目录同名前缀不算在模块里(self):
        self.assertEqual(("lint", []), self.level("core/uikit.md"))

    def test_改到构建本身就跑整仓(self):
        for path in ("gradle/libs.versions.toml", "build-logic/src/main/kotlin/A.kt", "settings.gradle.kts",
                     "gradle.properties", "core/data/schemas/x/7.json", ".github/workflows/ci.yml",
                     ".github/actions/setup-build/action.yml", "tools/ci/check.sh",
                     "core/ui/src/main/kotlin/io/github/touhougo/chogumi/core/ui/theme/Motion.kt"):
            with self.subTest(path=path):
                self.assertEqual(("full", []), self.level("docs/a.md", path, "core/ui/src/main/kotlin/B.kt"))

    def test_只进正式包的改动跑整仓(self):
        for path in ("app/build.gradle.kts", "app/proguard-rules.pro", "feature/profile/consumer-rules.pro",
                     "app/src/release/generated/baselineProfiles/baseline-prof.txt"):
            with self.subTest(path=path):
                self.assertEqual(("full", []), self.level(path))

    def test_库模块的构建脚本按模块跑(self):
        self.assertEqual(("modules", [":feature:profile"]), self.level("feature/profile/build.gradle.kts"))

    def test_整仓清单里的路径在仓库里都存在(self):
        # 目录改名后旧条目只会悄悄失效：改到新位置的文件不再触发整仓
        self.assertEqual([], [entry for entry in FULL if not (REPO / entry).exists()])

    def test_没有单元测试的Gradle模块跑整仓(self):
        self.assertEqual(("full", []), self.level("baselineprofile/src/main/kotlin/Journey.kt"))

    def test_只改文档且没人读就只跑门禁与合同(self):
        self.assertEqual(("lint", []), self.level("docs/dev/guide.md", "AGENTS.md", "tools/guards/check_menus.py"))

    def test_只改issue与PR模板就只跑门禁与合同(self):
        # 模板只给 GitHub 网页用，不进构建也不改 CI 的跑法
        self.assertEqual(("lint", []), self.level(".github/ISSUE_TEMPLATE/bug_report.yml",
                                                  ".github/ISSUE_TEMPLATE/config.yml", ".github/PULL_REQUEST_TEMPLATE.md"))

    def test_单元测试按路径读到的文件带上那个模块(self):
        literals = {":app": {"LICENSE", "docs/audits/"}, ":feature:profile": {"docs/third-party-notices.md"}}
        self.assertEqual(("modules", [":app"]), self.level("docs/audits/2026.md", literals=literals))
        self.assertEqual(("modules", [":app"]), self.level("LICENSE", literals=literals))
        self.assertEqual(("modules", [":feature:profile"]), self.level("docs/third-party-notices.md", literals=literals))

    def test_模块内文件也带上按路径读取它的其他模块(self):
        path = "core/ui/src/main/kotlin/Shared.kt"
        literals = {":feature:profile": {path}}
        self.assertEqual(("modules", [":core:ui", ":feature:profile"]), self.level(path, literals=literals))

    def test_删除旧路径也保留旧路径的读取方(self):
        old = "core/ui/src/main/kotlin/Old.kt"
        new = "core/ui/src/main/kotlin/New.kt"
        literals = {":feature:profile": {old}}
        self.assertEqual(("modules", [":core:ui", ":feature:profile"]), self.level(old, new, literals=literals))

    def test_目录读取没有尾斜杠也带上读取方且不匹配同名前缀(self):
        (self.root / "core/ui/src/main").mkdir(parents=True)
        literals = {":feature:profile": {"core/ui/src/main"}}
        self.assertEqual(("modules", [":core:ui", ":feature:profile"]),
                         self.level("core/ui/src/main/kotlin/Shared.kt", literals=literals))
        self.assertEqual(("modules", [":core:ui"]),
                         self.level("core/ui/src/mainland/Other.kt", literals=literals))

    def test_路径字面量不是目录时不按前缀算(self):
        self.assertEqual(("lint", []), self.level("LICENSE.old", literals={":app": {"LICENSE"}}))

    def test_测试字面量在模板处截断且不算注释里的(self):
        tests = self.root / "app/src/test/kotlin"
        tests.mkdir(parents=True)
        (tests / "ATest.kt").write_text(
            '// RepoFiles.read("README.md")\nval a = RepoFiles.file("docs/audits/$name")\nval b = RepoFiles.read("LICENSE")\n',
            encoding="utf-8")
        literals = test_literals_of(DIRECTORIES, self.root)
        self.assertEqual({"docs/audits/", "LICENSE"}, literals[":app"])
        self.assertEqual(set(), literals[":core:ui"])


if __name__ == "__main__":
    unittest.main()
