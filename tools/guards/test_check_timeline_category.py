"""时间线大类门禁的自测：每种裸比较各给一个违规样例，确认门禁拦得住（tools/ci/check.sh lint 运行）。"""

from pathlib import Path
import tempfile
import unittest

from check_timeline_category import find_violations


class CheckTimelineCategoryTest(unittest.TestCase):

    def violations(self, files: dict[str, str]) -> list[str]:
        root = Path(tempfile.mkdtemp())
        for rel, text in files.items():
            path = root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        return find_violations(root)

    def one(self, line: str) -> list[str]:
        return self.violations({"core/reader/src/main/Merge.kt": f"fun f(cat: Int) {{\n    {line}\n}}\n"})

    def test_经枚举比放行(self):
        self.assertEqual([], self.one("if (timelineCategoryOf(cat) != TimelineCategory.PROGRESS) return"))

    def test_等于裸数字拦下(self):
        self.assertEqual(1, len(self.one("if (cat == 5) return")))

    def test_不等于裸数字拦下(self):
        self.assertEqual(1, len(self.one("if (item.cat != 4) return")))

    def test_数字在左边拦下(self):
        self.assertEqual(1, len(self.one("if (5 == cat) return")))

    def test_区间拦下(self):
        self.assertEqual(1, len(self.one("if (cat in 3..4) return")))

    def test_when_cat_拦下(self):
        self.assertEqual(1, len(self.one("val x = when (cat) { 1 -> 1 else -> 0 }")))

    def test_集合与大小比较拦下(self):
        self.assertEqual(1, len(self.one("if (cat in setOf(1, 2)) return")))
        self.assertEqual(1, len(self.one("if (cat in listOf(1, 2)) return")))
        self.assertEqual(1, len(self.one("if (cat > 3) return")))
        self.assertEqual(1, len(self.one("if (item.cat <= 3) return")))
        self.assertEqual(1, len(self.one("if (3 < cat) return")))
        self.assertEqual(1, len(self.one("if (cat.equals(3)) return")))

    def test_改写的比较与分支同样拦下(self):
        for line in (
            "if (cat !in 1..3) return",
            "if (cat in arrayOf(1, 2)) return",
            "if (cat in hashSetOf(1)) return",
            "if (item.cat\n        .equals(3)) return",
            "if (cat == CAT_STATUS) return",
            "if (CAT_STATUS != item.cat) return",
            "val x = when (a.b.cat) { 1 -> 1 else -> 0 }",
            "val x = when (item?.cat) { 1 -> 1 else -> 0 }",
            "val x = when (item.cat!!) { 1 -> 1 else -> 0 }",
            "val x = when (val c = item.cat) { 1 -> 1 else -> 0 }",
        ):
            with self.subTest(line=line):
                self.assertEqual(1, len(self.one(line)))

    def test_比枚举与比另一个大类不算(self):
        for line in (
            "val x = when (timelineCategoryOf(item.cat)) { TimelineCategory.STATUS -> 1 else -> 0 }",
            "if (cat == TimelineCategory.PROGRESS || TimelineCategory.STATUS == cat) return",
            "if (cat == other.cat || item5 == cat) return",
        ):
            with self.subTest(line=line):
                self.assertEqual([], self.one(line))

    def test_注释里提到不算(self):
        self.assertEqual([], self.one("// cat == 5 才是状态"))

    def test_别的标识符不算(self):
        self.assertEqual([], self.one("if (category == 5 || concat == 3) return"))

    def test_测试源码不归它管(self):
        text = "fun f(cat: Int) { if (cat == 5) return }\n"
        self.assertEqual([], self.violations({"core/reader/src/test/MergeTest.kt": text}))


if __name__ == "__main__":
    unittest.main()
