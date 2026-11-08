"""按 Gradle 登记的测试结果目录计数。"""

from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

SCRIPT = Path(__file__).resolve().parent / "count_tests.py"


class CountTestsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.graph = (
            "chogumi-module\t:core:model\tcore/model\ttest\n"
            "chogumi-module\t:feature:a\tfeature/a\ttest\n"
            "chogumi-test-xml\t:core:model\tcore/model/build/test-results/test\n"
            "chogumi-test-xml\t:feature:a\tfeature/a/build/test-results/testDebugUnitTest\n"
            "chogumi-test-xml\t:feature:a\tfeature/a/build/test-results/testReleaseUnitTest\n"
        )

    def xml(self, directory, tests, failures=0, errors=0, skipped=0):
        path = self.root / directory / "TEST-Same.xml"
        path.parent.mkdir(parents=True, exist_ok=True)
        suite = ET.Element("testsuite", tests=str(tests), skipped=str(skipped), failures=str(failures), errors=str(errors))
        for index in range(tests):
            case = ET.SubElement(suite, "testcase", name=str(index))
            if index < skipped:
                ET.SubElement(case, "skipped")
            elif index < skipped + failures:
                ET.SubElement(case, "failure")
            elif index < skipped + failures + errors:
                ET.SubElement(case, "error")
        ET.ElementTree(suite).write(path, encoding="utf-8")

    def run_counter(self, *args, stdin):
        return subprocess.run([sys.executable, str(SCRIPT), *args], cwd=self.root,
                              input=stdin, capture_output=True, text=True)

    def test_登记的任务计数而旧任务和已移除模块不计数(self):
        self.xml("core/model/build/test-results/test", 3)
        self.xml("core/model/build/test-results/testDebugUnitTest", 7, failures=1)
        self.xml("removed/build/test-results/test", 9, failures=1)
        self.xml("feature/a/build/test-results/testDebugUnitTest", 2)
        self.xml("feature/a/build/test-results/testReleaseUnitTest", 2)
        result = self.run_counter(stdin=self.graph)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("core/model\t3", result.stdout)
        self.assertIn("feature/a\t4", result.stdout)
        self.assertIn("单元测试共 7 条，失败 0 条", result.stdout)

    def test_点名模块只统计该模块的结果(self):
        self.xml("core/model/build/test-results/test", 3)
        self.xml("feature/a/build/test-results/testDebugUnitTest", 2)
        result = self.run_counter(":core:model", stdin=self.graph)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("单元测试共 3 条，失败 0 条", result.stdout)
        self.assertNotIn("feature/a", result.stdout)

    def test_有测试源码的模块一份结果都没有时不能由其他模块充绿(self):
        source = self.root / "feature/a/src/test/kotlin/ATest.kt"
        source.parent.mkdir(parents=True)
        source.write_text("class ATest\n", encoding="utf-8")
        self.xml("core/model/build/test-results/test", 3)
        result = self.run_counter(stdin=self.graph)
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn(":feature:a 有单元测试源码", result.stderr)
        self.assertNotIn("单元测试共", result.stdout)

    def test_登记目录都不存在时报错而不报零条(self):
        result = self.run_counter(stdin=self.graph)
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn("没有读到任何测试", result.stderr)
        self.assertNotIn("单元测试共", result.stdout)

    def test_登记目录为空时报错而不报零条(self):
        (self.root / "core/model/build/test-results/test").mkdir(parents=True)
        result = self.run_counter(":core:model", stdin=self.graph)
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn("没有读到任何测试", result.stderr)
        self.assertNotIn("单元测试共", result.stdout)

    def test_结果文件只有零条测试时报错(self):
        self.xml("core/model/build/test-results/test", 0)
        result = self.run_counter(":core:model", stdin=self.graph)
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn("0 条测试", result.stderr)
        self.assertNotIn("单元测试共", result.stdout)

    def test_点名模块没有结果时不能用其他模块充数(self):
        self.xml("feature/a/build/test-results/testDebugUnitTest", 2)
        result = self.run_counter(":core:model", stdin=self.graph)
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn("没有读到任何测试", result.stderr)
        self.assertNotIn("单元测试共", result.stdout)

    def test_点名模块没有登记时报错(self):
        self.xml("core/model/build/test-results/test", 3)
        result = self.run_counter(":feature:missing", stdin=self.graph)
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn("没有读到任何测试", result.stderr)
        self.assertNotIn("单元测试共", result.stdout)

    def test_有有效结果时允许其他无源码任务没有报告(self):
        self.xml("feature/a/build/test-results/testDebugUnitTest", 2)
        result = self.run_counter(":feature:a", stdin=self.graph)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("单元测试共 2 条，失败 0 条", result.stdout)

    def test_登记任务失败会返回非零(self):
        self.xml("core/model/build/test-results/test", 3, failures=1)
        result = self.run_counter(stdin=self.graph)
        self.assertEqual(1, result.returncode)
        self.assertIn("单元测试共 3 条，失败 1 条", result.stdout)

    def test_部分跳过仍按实际执行结果成功并报告跳过数(self):
        self.xml("core/model/build/test-results/test", 3, skipped=1)
        result = self.run_counter(":core:model", stdin=self.graph)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("core/model\t3\t跳过 1", result.stdout)
        self.assertIn("单元测试共 3 条，失败 0 条", result.stdout)

    def test_错误数也计入失败(self):
        self.xml("core/model/build/test-results/test", 2, errors=1)
        result = self.run_counter(":core:model", stdin=self.graph)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("单元测试共 2 条，失败 1 条", result.stdout)

    def test_全部跳过不能算测试成功(self):
        self.xml("core/model/build/test-results/test", 3, skipped=3)
        result = self.run_counter(":core:model", stdin=self.graph)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("全部跳过", result.stderr)
        self.assertNotIn("单元测试共", result.stdout)

    def test_损坏的结果文件不能由其他有效模块充绿(self):
        self.xml("core/model/build/test-results/test", 3)
        broken = self.root / "feature/a/build/test-results/testDebugUnitTest/TEST-Broken.xml"
        broken.parent.mkdir(parents=True)
        broken.write_text('<testsuite tests="2" skipped="0">', encoding="utf-8")
        result = self.run_counter(stdin=self.graph)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("TEST-Broken.xml", result.stderr)
        self.assertNotIn("单元测试共", result.stdout)

    def test_缺少计数属性的结果文件不能静默略过(self):
        path = self.root / "core/model/build/test-results/test/TEST-Missing.xml"
        path.parent.mkdir(parents=True)
        path.write_text('<testsuite tests="2" skipped="0" failures="0"></testsuite>', encoding="utf-8")
        result = self.run_counter(":core:model", stdin=self.graph)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("errors", result.stderr)
        self.assertNotIn("单元测试共", result.stdout)

    def test_汇总属性不能掩盖用例里的失败错误或跳过(self):
        path = self.root / "core/model/build/test-results/test/TEST-Contradiction.xml"
        path.parent.mkdir(parents=True)
        for status in ("failure", "error", "skipped"):
            with self.subTest(status=status):
                path.write_text(
                    '<testsuite tests="1" skipped="0" failures="0" errors="0">'
                    f'<testcase name="case"><{status}/></testcase></testsuite>',
                    encoding="utf-8",
                )
                result = self.run_counter(":core:model", stdin=self.graph)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("汇总与用例", result.stderr)

    def test_汇总测试数必须等于真实用例数(self):
        path = self.root / "core/model/build/test-results/test/TEST-Inflated.xml"
        path.parent.mkdir(parents=True)
        path.write_text('<testsuite tests="3" skipped="0" failures="0" errors="0"><testcase/></testsuite>', encoding="utf-8")
        result = self.run_counter(":core:model", stdin=self.graph)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("汇总与用例", result.stderr)

    def test_零测试结果文件不能由其他有效模块充绿(self):
        self.xml("core/model/build/test-results/test", 3)
        self.xml("feature/a/build/test-results/testDebugUnitTest", 0)
        result = self.run_counter(stdin=self.graph)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("0 条测试", result.stderr)
        self.assertNotIn("单元测试共", result.stdout)

    def test_testsuites并列子项累加(self):
        path = self.root / "core/model/build/test-results/test/TEST-Many.xml"
        path.parent.mkdir(parents=True)
        path.write_text(
            '<testsuites>'
            '<testsuite tests="2" skipped="0" failures="0" errors="0"><testcase/><testcase/></testsuite>'
            '<testsuite tests="3" skipped="1" failures="0" errors="0"><testcase><skipped/></testcase><testcase/><testcase/></testsuite>'
            '</testsuites>',
            encoding="utf-8",
        )
        result = self.run_counter(":core:model", stdin=self.graph)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("core/model\t5\t跳过 1", result.stdout)
        self.assertIn("单元测试共 5 条，失败 0 条", result.stdout)

    def test_嵌套testsuite拒绝而不是父子双计(self):
        path = self.root / "core/model/build/test-results/test/TEST-Nested.xml"
        path.parent.mkdir(parents=True)
        path.write_text(
            '<testsuites><testsuite tests="99" skipped="0" failures="0" errors="0">'
            '<testsuite tests="2" skipped="0" failures="0" errors="0"></testsuite>'
            '</testsuite></testsuites>',
            encoding="utf-8",
        )
        result = self.run_counter(":core:model", stdin=self.graph)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("不能嵌套", result.stderr)
        self.assertNotIn("单元测试共", result.stdout)

    def test_图里没有测试结果目录就报错而不报零条(self):
        self.xml("core/model/build/test-results/test", 3, failures=1)
        graph = "".join(line + "\n" for line in self.graph.splitlines() if not line.startswith("chogumi-test-xml"))
        result = self.run_counter(stdin=graph)
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn("chogumi-test-xml", result.stderr)
        self.assertNotIn("单元测试共", result.stdout)


if __name__ == "__main__":
    unittest.main()
