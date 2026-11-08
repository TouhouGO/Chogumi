"""按模块检查范围的自测（tools/ci/check.sh lint 运行）。"""

from pathlib import Path
import re
import shutil
import sys
import tempfile
import unittest

from affected import REPO_SCANS, affected, normalize, parse_graph

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools/guards"))
from sources import strip_comments, test_sources
REPO_FILES = REPO / "core/foundation/src/testFixtures/kotlin/io/github/touhougo/chogumi/core/foundation/testing/RepoFiles.kt"


class AffectedTest(unittest.TestCase):

    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root)

    def graph(self, edges, scanning=()):
        """每个模块一行（目录建在临时根下），scanning 里的模块放一个扫整仓的测试。"""
        names = sorted({name for edge in edges for name in edge})
        lines = []
        for name in names:
            directory = self.root / name.strip(":").replace(":", "/")
            tests = directory / "src/test/kotlin"
            tests.mkdir(parents=True)
            body = 'RepoFiles.offenders("x(", "a.kt")' if name in scanning else 'RepoFiles.read("a.kt")'
            (tests / "SomeTest.kt").write_text(body, encoding="utf-8")
            lines.append(f"chogumi-module\t{name}\t{directory}\ttest\tcompileDebugAndroidTestKotlin")
        lines += [f"chogumi-edge\t{user}\t{used}" for user, used in edges]
        lines.append("Gradle 自己的输出")
        return parse_graph(lines)

    def test_点名模块带上直接与间接依赖它的模块(self):
        modules, users = self.graph([(":app", ":feature:a"), (":feature:a", ":core:ui"), (":core:ui", ":core:model"),
                                     (":feature:b", ":core:model"), (":app", ":feature:b")])
        self.assertEqual([":app", ":core:ui", ":feature:a"], affected([":core:ui"], modules, users))

    def test_扫整仓源码的模块总是带上(self):
        modules, users = self.graph([(":app", ":feature:a"), (":feature:a", ":core:ui")], scanning={":core:ui"})
        self.assertEqual([":app", ":core:ui", ":feature:a"], affected([":feature:a"], modules, users))

    def test_自环与测试夹具依赖不重复(self):
        modules, users = self.graph([(":core:ui", ":core:ui"), (":feature:a", ":core:ui"), (":feature:a", ":core:ui")])
        self.assertEqual([":core:ui", ":feature:a"], affected([":core:ui"], modules, users))

    def test_不认识的模块报错并列出可选的(self):
        modules, users = self.graph([(":app", ":core:ui")])
        with self.assertRaisesRegex(ValueError, ":core:uii.*:app"):
            affected([":core:uii"], modules, users)

    def test_目录写法与路径写法等价(self):
        self.assertEqual(":core:ui", normalize("core/ui"))
        self.assertEqual(":core:ui", normalize("core/ui/"))
        self.assertEqual(":core:ui", normalize(":core:ui"))

    def test_RepoFiles的扫整仓入口都登记了(self):
        """RepoFiles 加了新的公开函数，要判断它扫不扫整仓：扫就登记进 REPO_SCANS，不扫就登记进这里的单文件入口。"""
        functions = set(re.findall(r"(?<!private )\bfun (\w+)\(", REPO_FILES.read_text(encoding="utf-8")))
        scans = {scan.removeprefix("RepoFiles.").removesuffix("(") for scan in REPO_SCANS}
        self.assertEqual({"file", "read", "relativePath", "codeLines"} | scans, functions)

    def test_单元测试自己遍历的目录不出本模块(self):
        """自己 walkTopDown 的测试，affected.py 认不出它扫了别的模块：遍历别的模块要经 REPO_SCANS 里的入口。"""
        self.assertEqual([], walks_outside_module(REPO))

    def test_遍历别的模块的写法被认出(self):
        tests = self.root / "core/res/src/test/kotlin"
        tests.mkdir(parents=True)
        (tests / "A.kt").write_text('listOf("app", "core").map(RepoFiles::file).forEach { it.walkTopDown() }', encoding="utf-8")
        (tests / "B.kt").write_text('RepoFiles.file("feature/home/src").walkTopDown()', encoding="utf-8")
        (tests / "C.kt").write_text('RepoFiles.file("core/res/src/main").walkTopDown()', encoding="utf-8")
        self.assertEqual(["core/res/src/test/kotlin/A.kt", "core/res/src/test/kotlin/B.kt"], walks_outside_module(self.root))

    def test_隐藏目录与构建产物不参与单元测试扫描(self):
        for directory in (".claude/worktrees/copy/core/res", "core/res/build/copy/core/res"):
            tests = self.root / directory / "src/test/kotlin"
            tests.mkdir(parents=True)
            (tests / "Copy.kt").write_text('RepoFiles.file("core/res/src").walkTopDown()', encoding="utf-8")
        self.assertEqual([], walks_outside_module(self.root))

    def test_注释中的遍历不算代码(self):
        tests = self.root / "core/res/src/test/kotlin"
        tests.mkdir(parents=True)
        (tests / "A.kt").write_text('/* RepoFiles.file("app/src").walkTopDown() */', encoding="utf-8")
        self.assertEqual([], walks_outside_module(self.root))

    def test_baselineprofile主源码不当作单元测试目录(self):
        journey = self.root / "baselineprofile/src/main/kotlin/StartupJourney.kt"
        journey.parent.mkdir(parents=True)
        journey.write_text('RepoFiles.file("app/src").walkTopDown()', encoding="utf-8")
        self.assertEqual([], walks_outside_module(self.root))


def walks_outside_module(root):
    """单元测试里自己遍历目录、而目录不在本模块下的文件（相对 root）。路径不是字面量（`RepoFiles::file`）也算。"""
    found = []
    for path in test_sources(root):
        if "/src/test/" not in path.relative_to(root).as_posix():
            continue
        text = strip_comments(path.read_text(encoding="utf-8"))
        if "walkTopDown" not in text or "RepoFiles" not in text:
            continue
        module = path.relative_to(root).as_posix().split("/src/test/")[0] + "/"
        paths = re.findall(r'RepoFiles\.file\("([^"]+)"\)', text)
        if "RepoFiles::file" in text or any(not p.startswith(module) for p in paths):
            found.append(path.relative_to(root).as_posix())
    return found


if __name__ == "__main__":
    unittest.main()
