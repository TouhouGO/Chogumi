"""固有尺寸门禁的自测：每条规矩各给一个违规样例，确认门禁真的拦得住（tools/ci/check.sh lint 运行）。"""

from pathlib import Path
import tempfile
import unittest

from check_intrinsic_size import READER, find_violations

BAR = "feature/a/src/main/Bar.kt"
ALLOWED = {(BAR, "QuoteBar"): "引用条"}

READER_FILE = """
@Composable
fun BBCodeText(ast: BBCodeAst) {
    SubcomposeLayout { }
}
"""

QUOTE_BAR = """
@Composable
private fun QuoteBar(text: String) {
    Row(Modifier.height(IntrinsicSize.Min)) {
        Box(Modifier.fillMaxHeight())
        Text(text)
    }
}
"""


class CheckIntrinsicSizeTest(unittest.TestCase):

    def violations(self, files: dict[str, str], allowed: dict[tuple[str, str], str] = ALLOWED) -> list[str]:
        root = Path(tempfile.mkdtemp())
        for rel, text in {f"{READER}/BBCodeText.kt": READER_FILE, **files}.items():
            path = root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        return find_violations(root, allowed)

    def test_白名单里只有纯文字的固有尺寸放行(self):
        self.assertEqual([], self.violations({BAR: QUOTE_BAR}))

    def test_白名单外的固有尺寸拦下(self):
        found = self.violations({
            BAR: QUOTE_BAR,
            "feature/a/src/main/Floor.kt": QUOTE_BAR.replace("QuoteBar", "Floor"),
        })
        self.assertEqual(1, len(found))
        self.assertIn("Floor", found[0])

    def test_白名单函数里调用富文本拦下(self):
        rich = QUOTE_BAR.replace("Text(text)", "BBCodeText(ast)")
        found = self.violations({BAR: rich})
        self.assertEqual(1, len(found))
        self.assertIn("BBCodeText", found[0])

    def test_注释里提到的不算(self):
        commented = QUOTE_BAR.replace("Text(text)", "// 不用 BBCodeText(ast)\n        Text(text)") + \
            "// Modifier.height(IntrinsicSize.Min) 不能包富文本\n"
        self.assertEqual([], self.violations({BAR: commented}))

    def test_字符串和行尾注释不算_导入别名仍拦(self):
        text_only = QUOTE_BAR.replace("Text(text)", 'val text = "IntrinsicSize.Min" // IntrinsicSize.Max')
        self.assertEqual([], self.violations({BAR: text_only}))
        aliased = 'import androidx.compose.ui.layout.IntrinsicSize as Measure\nfun f() { Row(Modifier.height(Measure.Min)) }\n'
        self.assertEqual(1, len(self.violations({BAR: aliased}, {})))

    def test_白名单按仓库路径_同名文件不能冒用(self):
        found = self.violations({"feature/b/src/main/Bar.kt": QUOTE_BAR})
        self.assertEqual(2, len(found), found)
        self.assertTrue(any("feature/b/src/main/Bar.kt:4" in v for v in found))
        self.assertTrue(any("死配置" in v for v in found))

    def test_拆行的固有尺寸也拦下(self):
        found = self.violations({BAR: QUOTE_BAR, "feature/a/src/main/Floor.kt": "fun Floor() { Row(Modifier.height(IntrinsicSize\n    .Min)) {} }\n"})
        self.assertEqual(1, len(found), found)
        self.assertIn("Floor", found[0])

    def test_白名单函数里有局部函数仍按外层函数认(self):
        local = QUOTE_BAR.replace("    Row(", "    fun gap() = 4.dp\n    Row(")
        self.assertEqual([], self.violations({BAR: local}))
        rich = local.replace("Text(text)", "BBCodeText(ast)")
        found = self.violations({BAR: rich})
        self.assertEqual(1, len(found), found)
        self.assertIn("BBCodeText", found[0])

    def test_函数体只到它自己的右括号_下一个成员里的富文本不算(self):
        member = (
            "class Holder {\n    @Composable\n    fun QuoteBar(text: String) {\n"
            "        Row(Modifier.height(IntrinsicSize.Min)) { Text(text) }\n    }\n\n"
            "    @Composable\n    fun Other() { BBCodeText(ast) }\n}\n"
        )
        self.assertEqual([], self.violations({BAR: member}))

    def test_带别的注解或修饰的富文本组件也认得(self):
        reader = "@Composable\n@NonRestartableComposable\nfun RichX() {}\n@Composable internal inline fun RichY() {}\n"
        for call in ("RichX()", "RichY()"):
            with self.subTest(call=call):
                found = self.violations({f"{READER}/Rich.kt": reader, BAR: QUOTE_BAR.replace("Text(text)", call)})
                self.assertEqual(1, len(found), found)
                self.assertIn(call.rstrip("()"), found[0])

    def test_白名单死配置拦下(self):
        found = self.violations({BAR: QUOTE_BAR.replace("IntrinsicSize.Min", "48.dp")})
        self.assertEqual(1, len(found))
        self.assertIn("死配置", found[0])


if __name__ == "__main__":
    unittest.main()
