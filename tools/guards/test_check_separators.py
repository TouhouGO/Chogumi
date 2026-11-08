"""分隔号门禁的自测：每条规矩一个样例（tools/ci/check.sh lint 运行）。"""

from pathlib import Path
import tempfile
import unittest

from check_separators import find_violations



class CheckSeparatorsTest(unittest.TestCase):

    def violations(self, files: dict[str, str]) -> list[str]:
        root = Path(tempfile.mkdtemp())
        for rel, text in files.items():
            path = root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        return find_violations(root)

    def test_规范写法放行(self):
        page = 'fun Page() {\n    MetaText(parts = listOf("主角", "CV 田中真弓"))\n    Text("并列用、，句子用，")\n}\n'
        strings = '<resources>\n    <string name="a">可多选，同时满足</string>\n</resources>\n'
        found = self.violations({
            "feature/a/src/main/Page.kt": page,
            "core/res/src/main/res/values/strings.xml": strings,
        })
        self.assertEqual([], found)

    def test_Kotlin字面量拦下(self):
        found = self.violations({"feature/a/src/main/Page.kt": 'fun Page() {\n    Text("$a · $b")\n}\n'})
        self.assertEqual(1, len(found))
        self.assertIn("feature/a/src/main/Page.kt:2", found[0])
        self.assertIn("MetaText", found[0])

    def test_Kotlin转义拦下(self):
        found = self.violations({"feature/a/src/main/Page.kt": 'val s = list.joinToString(" \\u00b7 ")\n'})
        self.assertEqual(1, len(found))

    def test_同类圆点也拦下(self):
        for dot in ("•", "・", "⋅", "\\u2022"):
            found = self.violations({"feature/a/src/main/Page.kt": f'fun Page() {{\n    Text("$a {dot} $b")\n}}\n'})
            self.assertEqual(1, len(found), dot)
        strings = '<resources>\n    <string name="a">%1$s • %2$d</string>\n</resources>\n'
        self.assertEqual(1, len(self.violations({"core/res/src/main/res/values/strings.xml": strings})))

    def test_strings_xml拦下(self):
        strings = '<resources>\n    <string name="a">%1$s · %2$d 部</string>\n</resources>\n'
        found = self.violations({"core/res/src/main/res/values-en/strings.xml": strings})
        self.assertEqual(1, len(found))
        self.assertIn("values-en/strings.xml:2", found[0])

    def test_注释里的不拦(self):
        page = '/** 「主角 · CV」 */\nfun Page() {\n    // 拼法示例 a · b\n}\n'
        strings = '<resources>\n    <!-- 周几 · 共几部 -->\n    <string name="a">x</string>\n</resources>\n'
        found = self.violations({
            "feature/a/src/main/Page.kt": page,
            "core/res/src/main/res/values/strings.xml": strings,
        })
        self.assertEqual([], found)

    def test_MetaText自己也不放行(self):
        found = self.violations({"core/ui/src/main/MetaText.kt": 'fun f() { append("·") }\n'})
        self.assertEqual(1, len(found))


    def test_资源里的转义与带前导零的实体拦下(self):
        for dot in ("\\u00b7", "\\u00B7", "&#0183;", "&#x00B7;"):
            strings = f'<resources>\n    <string name="a">%1$s {dot} %2$d</string>\n</resources>\n'
            with self.subTest(dot):
                self.assertEqual(1, len(self.violations({"core/res/src/main/res/values/strings.xml": strings})))

    def test_更多同类圆点拦下(self):
        for dot in ("∙", "‧", "･", "\\u2219", "\\uFF65"):
            with self.subTest(dot):
                found = self.violations({"feature/a/src/main/Page.kt": f'fun Page() {{\n    Text("$a {dot} $b")\n}}\n'})
                self.assertEqual(1, len(found))

    def test_其他源集与资源文件也查(self):
        debug = '<resources>\n    <string name="a">调试 · 版</string>\n</resources>\n'
        plurals = '<resources>\n    <plurals name="a"><item quantity="other">%d 部 · 看过</item></plurals>\n</resources>\n'
        found = self.violations({
            "app/src/debug/res/values/strings.xml": debug,
            "feature/a/src/main/res/values/plurals.xml": plurals,
        })
        self.assertEqual(2, len(found))


if __name__ == "__main__":
    unittest.main()
