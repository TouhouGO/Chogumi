"""字阶与圆角门禁的自测（tools/ci/check.sh lint 运行）。"""

from pathlib import Path
import tempfile
import unittest

from check_type_shape import find_violations


class CheckTypeShapeTest(unittest.TestCase):

    def violations(self, text: str) -> list[str]:
        root = Path(tempfile.mkdtemp())
        path = root / "feature/a/src/main/Page.kt"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return find_violations(root)

    def test_分角圆角等于阶梯拦下_拆行与CornerSize同样_非阶梯与token改角放行(self):
        arch = "val Arch = RoundedCornerShape(\n    topStart = W / 2,\n    topEnd = W / 2,\n    bottomStart = 16.dp,\n    bottomEnd = CornerSize(16.0.dp),\n)\n"
        found = self.violations(arch)
        self.assertEqual(2, len(found), found)
        self.assertTrue(all("large" in f for f in found), found)
        self.assertEqual([], self.violations("val Arch = RoundedCornerShape(topStart = W / 2, bottomEnd = 10.dp)\n"))
        self.assertEqual([], self.violations("val Arch = ShapeDefaults.Large.copy(topStart = CornerSize(W / 2))\n"))

    def test_写死字号拦下(self):
        self.assertEqual(1, len(self.violations('Text("a", fontSize = 14.sp)\n')))

    def test_字阶上自造字重拦下(self):
        found = self.violations('val s = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)\n')
        self.assertEqual(1, len(found))
        self.assertIn("Emphasized", found[0])

    def test_Text单传字重拦下(self):
        page = 'Text(\n    text = title,\n    style = MaterialTheme.typography.titleMedium,\n    fontWeight = FontWeight.Bold,\n)\n'
        self.assertEqual(1, len(self.violations(page)))

    def test_正文局部加粗与只改阴影的copy放行(self):
        page = (
            'val name = SpanStyle(fontWeight = FontWeight.Medium)\n'
            'val s = MaterialTheme.typography.headlineMediumEmphasized.copy(shadow = Shadow(Color.Black))\n'
            'Text("a", style = MaterialTheme.typography.labelLargeEmphasized)\n'
        )
        self.assertEqual([], self.violations(page))

    def test_换写法的字号字重圆角同样拦下(self):
        for text in (
            'Text("a", fontSize = 14f.sp)\n',
            'val s = x.copy(color = Color(0xFF000000), fontWeight = FontWeight.Bold)\n',
            'val s = TextStyle(fontWeight = FontWeight.Bold)\n',
            'val s = RoundedCornerShape(size = 8.dp)\n',
            'val s = RoundedCornerShape(CornerSize(12.dp))\n',
        ):
            self.assertEqual(1, len(self.violations(text)), text)

    def test_参数里嵌套两层括号之后的字重也拦下(self):
        for text in (
            'Text(text = stringResource(R.string.x, count(a)), fontWeight = FontWeight.Bold)\n',
            'val s = style.copy(color = c.copy(alpha = f(0.5f)), fontWeight = FontWeight.Bold)\n',
            'val s = style.merge(fontWeight = FontWeight.Bold)\n',
        ):
            with self.subTest(text=text):
                found = self.violations(text)
                self.assertEqual(1, len(found), found)
                self.assertIn("Emphasized", found[0])

    def test_嵌在参数里的局部加粗与不改字重的merge放行(self):
        page = (
            'Text(buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(name) } }, style = s)\n'
            'val a = inherited.merge(SpanStyle(fontWeight = FontWeight.Bold))\n'
            'val b = style.merge(color = color.takeOrElse { style.color })\n'
        )
        self.assertEqual([], self.violations(page))

    def test_em字号与先放进常量的字号也拦下(self):
        for text in (
            'Text("a", fontSize = 1.2.em)\n',
            'private val Big = 20.sp\nText("a", fontSize = Big)\n',
            'Text("a", fontSize = TextUnit(14f, TextUnitType.Sp))\n',
        ):
            with self.subTest(text=text):
                self.assertEqual(1, len(self.violations(text)), text)

    def test_随内容的字号放行(self):
        page = 'val a = SpanStyle(fontSize = em.em)\nval b = style.copy(fontSize = TextUnit.Unspecified)\nval Gap = 0.75.em\n'
        self.assertEqual([], self.violations(page))

    def test_圆角的小数写法与常量的其他传法也拦下(self):
        for text in (
            'val s = RoundedCornerShape(8.0.dp)\n',
            'val s = RoundedCornerShape(8f.dp)\n',
            'val s = AbsoluteRoundedCornerShape(8.dp)\n',
            'val Corner = 12.dp\nval s = RoundedCornerShape(size = Corner)\n',
            'val Corner = 12.dp\nval s = RoundedCornerShape(CornerSize(Corner))\n',
        ):
            with self.subTest(text=text):
                self.assertEqual(1, len(self.violations(text)), text)

    def test_相近但不在阶梯上的圆角放行(self):
        page = 'val a = RoundedCornerShape(80.dp)\nval b = RoundedCornerShape(8.5.dp)\nval c = RoundedCornerShape(percent = 50)\n'
        self.assertEqual([], self.violations(page))

    def test_阶梯圆角字面量拦下并提示token(self):
        found = self.violations('val shape = RoundedCornerShape(12.dp)\n')
        self.assertEqual(1, len(found))
        self.assertIn("medium", found[0])

    def test_Expressive加的阶梯同样拦下(self):
        found = self.violations('val a = RoundedCornerShape(20.dp)\nval b = RoundedCornerShape(32.dp)\n')
        self.assertEqual(2, len(found))
        self.assertIn("largeIncreased", found[0])
        self.assertIn("extraLargeIncreased", found[1])

    def test_不在阶梯上的圆角与注释放行(self):
        page = '// RoundedCornerShape(8.dp) 写在注释里\nval chip = RoundedCornerShape(6.dp)\nval card = RoundedCornerShape(18.dp)\n'
        self.assertEqual([], self.violations(page))

    def test_阶梯值放进常量再画形状拦下(self):
        found = self.violations('private val HeroCorner = 28.dp\nval shape = RoundedCornerShape(HeroCorner)\n')
        self.assertEqual(1, len(found))
        self.assertIn("extraLarge", found[0])

    def test_阶梯常量只当Dp用与不在阶梯上的常量放行(self):
        page = (
            'private val HeroCorner: Dp = 28.dp\nval cover = posterCoverShape(mark, interaction, HeroCorner)\n'
            'private val FrameCorner = 18.dp\nval frame = RoundedCornerShape(FrameCorner)\n'
        )
        self.assertEqual([], self.violations(page))

    def test_Text与RoundedCornerShape的导入别名仍拦下(self):
        page = (
            "import androidx.compose.material3.Text as Label\n"
            "import androidx.compose.foundation.shape.RoundedCornerShape as Rounded\n"
            "val label = Label(\"a\", fontWeight = FontWeight.Bold)\n"
            "val shape = Rounded(8.dp)\n"
        )
        self.assertEqual(2, len(self.violations(page)))


if __name__ == "__main__":
    unittest.main()
