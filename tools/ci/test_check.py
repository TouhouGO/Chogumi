"""check.sh 的自测（tools/ci/check.sh lint 运行）：在临时目录里用假门禁、假 npm / node、假 gradlew 跑脚本。"""

from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
# 假门禁：check.sh 跑 tools/guards 下全部 check_*.py，按文件名排序
GUARDS = ["a_first", "b_second"]

# 假 gradlew：带 -I 时打出依赖图（:app 依赖 :core:ui），否则只记下参数
FAKE_GRADLEW = """#!/usr/bin/env bash
echo "gradlew $*" >> "$LOG"
case " $* " in *" -I "*)
    printf 'chogumi-module\\t:app\\t%s/app\\ttest\\tassembleDebug\\n' "$PWD"
    printf 'chogumi-module\\t:core:ui\\t%s/core/ui\\ttest\\tcompileDebugAndroidTestKotlin\\n' "$PWD"
    printf 'chogumi-module\\t:feature:a\\t%s/feature/a\\ttest\\n' "$PWD"
    printf 'chogumi-test-xml\\t:app\\tapp/build/test-results/testDebugUnitTest\\n'
    printf 'chogumi-test-xml\\t:core:ui\\tcore/ui/build/test-results/testDebugUnitTest\\n'
    printf 'chogumi-test-xml\\t:feature:a\\tfeature/a/build/test-results/test\\n'
    printf 'chogumi-edge\\t:app\\t:core:ui\\n' ;;
esac
"""
FAKE_TOOL = '#!/usr/bin/env bash\necho "$(basename "$0") $*" >> "$LOG"\n'


class CheckScriptTest(unittest.TestCase):

    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        ci = self.root / "tools/ci"
        ci.mkdir(parents=True)
        for name in ("check.sh", "affected.py", "count_tests.py", "scope.py"):
            shutil.copy(HERE / name, ci / name)
        for suite in ("device", "ci", "guards"):
            (self.root / "tools" / suite).mkdir(parents=True, exist_ok=True)
            (self.root / "tools" / suite / "test_ok.py").write_text(
                "import unittest\nclass T(unittest.TestCase):\n    def test_ok(self): pass\n", encoding="utf-8")
        (self.root / "tools/openapi").mkdir()
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.executable(self.root / "gradlew", FAKE_GRADLEW)
        for tool in ("npm", "node"):
            self.executable(self.bin / tool, FAKE_TOOL)
        self.log = self.root / "log"
        self.log.touch()
        self.guards(failing=None)
        shutil.copy(HERE.parent / "guards/sources.py", self.root / "tools/guards/sources.py")
        # 与真实仓库一样，构建产物与脚本自己的运行记录不进版本库，也就不算改动
        (self.root / ".gitignore").write_text("build/\n__pycache__/\n/log\n/bin/\n", encoding="utf-8")

    def executable(self, path, text):
        path.write_text(text, encoding="utf-8")
        path.chmod(0o755)

    def guards(self, failing):
        for guard in GUARDS:
            code = 1 if guard == failing else 0
            (self.root / f"tools/guards/check_{guard}.py").write_text(f"raise SystemExit({code})\n", encoding="utf-8")

    def run_check(self, *args):
        env = dict(os.environ, LOG=str(self.log), PATH=f"{self.bin}:{os.environ['PATH']}")
        return subprocess.run(["bash", str(self.root / "tools/ci/check.sh"), *args],
                              env=env, capture_output=True, text=True)

    def calls(self):
        return self.log.read_text(encoding="utf-8").splitlines()

    def results(self):
        for directory, tests in (("testDebugUnitTest", 3), ("testOldUnitTest", 9)):
            path = self.root / "core/ui/build/test-results" / directory / "TEST-Example.xml"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(
                f'<testsuite tests="{tests}" skipped="0" failures="0" errors="0">'
                + '<testcase/>' * tests + '</testsuite>',
                encoding="utf-8",
            )

    def test_整仓检查里前面一条门禁失败就停下(self):
        self.guards(failing=GUARDS[0])
        result = self.run_check()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], self.calls(), "门禁失败后不该再跑合同与构建")

    def test_按模块检查里前面一条门禁失败就停下(self):
        self.guards(failing=GUARDS[0])
        result = self.run_check("modules", ":core:ui")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], self.calls())

    def test_按模块检查跑点名模块与依赖它的模块(self):
        self.results()
        result = self.run_check("modules", "core/ui")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("按模块检查：:app :core:ui", result.stdout)
        self.assertIn("单元测试共 3 条，失败 0 条", result.stdout)
        self.assertEqual(
            "gradlew --max-workers=4 :app:test :app:assembleDebug :core:ui:test :core:ui:compileDebugAndroidTestKotlin",
            self.calls()[-1])

    def test_整仓构建使用登记的测试结果计数(self):
        self.results()
        result = self.run_check("build")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("单元测试共 3 条，失败 0 条", result.stdout)
        self.assertEqual([
            "gradlew -q --no-configuration-cache -I tools/ci/project-graph.init.gradle.kts help",
            "gradlew --max-workers=4 test :app:assembleDebug assembleDebugAndroidTest :app:assembleBenchmarkRelease :app:lintDebug :baselineprofile:compileBenchmarkReleaseKotlin",
        ], self.calls())

    def test_整仓单测一份只跑单测并计数_不跑门禁与合同(self):
        self.guards(failing=GUARDS[0])
        self.results()
        result = self.run_check("--part", "tests")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("单元测试共 3 条，失败 0 条", result.stdout)
        self.assertEqual("gradlew --max-workers=4 test", self.calls()[-1])
        self.assertNotIn("npm ci --silent", self.calls())

    def test_整仓构建一份跑单测以外的任务且不计数(self):
        result = self.run_check("--part", "builds")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("单元测试共", result.stdout)
        self.assertEqual(
            "gradlew --max-workers=4 :app:assembleDebug assembleDebugAndroidTest :app:assembleBenchmarkRelease :app:lintDebug :baselineprofile:compileBenchmarkReleaseKotlin",
            self.calls()[-1])

    def test_按模块分两份跑合起来就是不分份时的任务(self):
        self.results()
        tests = self.run_check("--part", "tests", "modules", "core/ui")
        self.assertEqual(0, tests.returncode, tests.stderr)
        self.assertIn("单元测试共 3 条，失败 0 条", tests.stdout)
        self.assertEqual("gradlew --max-workers=4 :app:test :core:ui:test", self.calls()[-1])
        builds = self.run_check("--part", "builds", "modules", "core/ui")
        self.assertEqual(0, builds.returncode, builds.stderr)
        self.assertEqual("gradlew --max-workers=4 :app:assembleDebug :core:ui:compileDebugAndroidTestKotlin", self.calls()[-1])

    def test_按改动只改文档时分份也不跑Gradle(self):
        base = self.commit_change("docs/说明.md")
        result = self.run_check("--part", "builds", "changed", base)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("按改动选范围：lint", result.stdout)
        self.assertNotIn("--max-workers", "\n".join(self.calls()))

    def test_分份只认单测与构建两份(self):
        for args in (("--part", "lint"), ("--part",), ("--part", "tests", "lint"), ("--part", "builds", "warm")):
            result = self.run_check(*args)
            self.assertEqual(2, result.returncode, args)
            self.assertIn("用法", result.stderr)
        self.assertEqual([], self.calls())

    def test_预热编译全部模块且单测只演不跑(self):
        result = self.run_check("warm")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(
            "gradlew --max-workers=4 -Pchogumi.testDryRun=true :app:test :app:assembleDebug :core:ui:test "
            ":core:ui:compileDebugAndroidTestKotlin :feature:a:test",
            self.calls()[-1])
        self.assertNotIn("npm ci --silent", self.calls())

    def git(self, *args):
        return subprocess.run(["git", "-C", str(self.root), "-c", "user.name=t", "-c", "user.email=t@t", *args],
                              capture_output=True, text=True, check=True).stdout.strip()

    def test_按改动检查把改名的旧路径也算上(self):
        tests = self.root / "core/ui/src/test/kotlin"
        tests.mkdir(parents=True)
        (tests / "LicenseTest.kt").write_text('val text = RepoFiles.read("LICENSE")\n', encoding="utf-8")
        (self.root / "LICENSE").write_text("许可原文\n" * 20, encoding="utf-8")
        self.git("init", "-q")
        self.git("add", "-A")
        self.git("commit", "-qm", "基准")
        base = self.git("rev-parse", "HEAD")
        self.git("mv", "LICENSE", "LICENSE.old")
        self.git("commit", "-qm", "改名")
        self.results()
        result = self.run_check("changed", base)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("按改动选范围：modules :core:ui", result.stdout)

    def commit_change(self, path):
        """在临时仓库里提交一次基准，再提交对 path 的改动；返回基准提交。"""
        def git(*args):
            return subprocess.run(["git", "-C", str(self.root), "-c", "user.name=t", "-c", "user.email=t@t", *args],
                                  capture_output=True, text=True, check=True).stdout.strip()
        git("init", "-q")
        git("add", "-A")
        git("commit", "-qm", "基准")
        base = git("rev-parse", "HEAD")
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("改动\n", encoding="utf-8")
        git("add", "-A")
        git("commit", "-qm", "改动")
        return base

    def test_按改动检查改到模块就按模块跑(self):
        base = self.commit_change("core/ui/src/main/kotlin/A.kt")
        self.results()
        result = self.run_check("changed", base)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("按改动选范围：modules :core:ui", result.stdout)
        self.assertIn("按模块检查：:app :core:ui", result.stdout)
        self.assertEqual(
            "gradlew --max-workers=4 :app:test :app:assembleDebug :core:ui:test :core:ui:compileDebugAndroidTestKotlin",
            self.calls()[-1])

    def test_按改动检查算上没提交的改动(self):
        base = self.commit_change("docs/说明.md")
        (self.root / "gradle").mkdir()
        (self.root / "gradle/libs.versions.toml").write_text("没提交\n", encoding="utf-8")
        self.results()
        result = self.run_check("changed", base)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("按改动选范围：full", result.stdout)

    def test_按改动检查算上没加进版本库的新文件(self):
        base = self.commit_change("docs/说明.md")
        source = self.root / "core/ui/src/main/kotlin/New.kt"
        source.parent.mkdir(parents=True)
        source.write_text("新文件\n", encoding="utf-8")
        self.results()
        result = self.run_check("changed", base)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("按改动选范围：modules :core:ui", result.stdout)

    def test_按改动检查只改文档就到门禁与合同为止(self):
        base = self.commit_change("docs/说明.md")
        result = self.run_check("changed", base)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("按改动选范围：lint", result.stdout)
        self.assertIn("docs/说明.md：模块以外", result.stderr)
        self.assertNotIn("--max-workers", "\n".join(self.calls()))

    def test_按改动检查改到构建就跑整仓(self):
        base = self.commit_change("gradle/libs.versions.toml")
        self.results()
        result = self.run_check("changed", base)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("按改动选范围：full", result.stdout)
        self.assertIn("gradlew --max-workers=4 test :app:assembleDebug", "\n".join(self.calls()))

    def test_按改动检查基准不存在就停下(self):
        self.commit_change("docs/a.md")
        result = self.run_check("changed", "没有这个提交")
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("--max-workers", "\n".join(self.calls()))

    def test_按模块检查不点名模块就报用法(self):
        result = self.run_check("modules")
        self.assertEqual(2, result.returncode)
        self.assertIn("用法", result.stderr)

    def test_按模块检查点错模块就停下(self):
        result = self.run_check("modules", ":core:uii")
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("--max-workers", "\n".join(self.calls()))


class GuardRegistryTest(unittest.TestCase):

    def test_每个门禁都有自测(self):
        guards = HERE.parent / "guards"
        missing = sorted(p.name for p in guards.glob("check_*.py") if not (guards / f"test_{p.name}").is_file())
        self.assertEqual([], missing, "门禁要带自测：每条规矩一个违规样例、一个放行样例，再加一种换写法绕过的样例")


if __name__ == "__main__":
    unittest.main()
