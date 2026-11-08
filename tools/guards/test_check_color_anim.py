"""动画颜色门禁的自测：违规样例要拦住，合规写法放行（tools/ci/check.sh lint 运行）。"""

from pathlib import Path
import tempfile
import unittest

from check_color_anim import find_violations

TRANSPARENT = """
@Composable
fun Button(waiting: Boolean) {
    val container by animateColorAsState(
        if (waiting) Color.Transparent else MaterialTheme.colorScheme.secondaryContainer,
        label = "c",
    )
}
"""

FADED = """
@Composable
fun Button(waiting: Boolean) {
    val container by animateColorAsState(
        if (waiting) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0f) else MaterialTheme.colorScheme.secondaryContainer,
        label = "c",
    )
}
"""

STATIC = """
@Composable
fun Box() {
    Modifier.background(Color.Transparent)
    // animateColorAsState(Color.Transparent)
}
"""


VIA_LOCAL = """
@Composable
fun Button(on: Boolean) {
    val target = if (on) MaterialTheme.colorScheme.primary else Color.Transparent
    val container by animateColorAsState(target, label = "c")
}
"""


class CheckColorAnimTest(unittest.TestCase):

    def violations(self, text: str) -> list[str]:
        root = Path(tempfile.mkdtemp())
        path = root / "feature/a/src/main/Button.kt"
        path.parent.mkdir(parents=True)
        path.write_text(text, encoding="utf-8")
        return find_violations(root)

    def test_transparent_target_is_rejected(self):
        found = self.violations(TRANSPARENT)
        self.assertEqual(1, len(found))
        self.assertIn("Button.kt:4", found[0])

    def test_transparent_local_passed_to_the_animation_is_rejected(self):
        self.assertEqual(1, len(self.violations(VIA_LOCAL)))

    def test_faded_neighbour_colour_passes(self):
        self.assertEqual([], self.violations(FADED))

    def test_static_transparent_and_comments_pass(self):
        self.assertEqual([], self.violations(STATIC))

    def test_其他颜色插值的起点用透明黑也拦下(self):
        for source in (
            "val c by transition.animateColor(transitionSpec = { s }) { if (it) Color.Transparent else colors.primary }",
            "val c = remember { Animatable(Color.Transparent) }",
            "suspend fun f() { c.animateTo(Color.Transparent, spec) }",
            "suspend fun f() { c.snapTo(Color.Transparent) }",
            "val c = lerp(Color.Transparent, colors.primary, f)",
            "val c by animateColorAsState(if (on) colors.primary else Color(0x00000000), spec)",
            "val c by animateColorAsState(if (on) colors.primary else Color.Black.copy(alpha = 0f), spec)",
        ):
            with self.subTest(source=source):
                self.assertEqual(1, len(self.violations(source + "\n")))

    def test_跨行或var的透明局部变量也拦下(self):
        for source in (
            "fun P() {\n    val target = if (on)\n        colors.primary\n    else Color.Transparent\n    val c by animateColorAsState(target, spec)\n}\n",
            "fun P() {\n    val target =\n        Color.Transparent\n    val c by animateColorAsState(target, spec)\n}\n",
            "fun P() {\n    var target: Color = Color.Transparent\n    val c by animateColorAsState(target, spec)\n}\n",
        ):
            with self.subTest(source=source):
                self.assertEqual(1, len(self.violations(source)))

    def test_同名但最近的声明不是透明色放行(self):
        source = (
            "fun A() { val target = Color.Transparent; Box(Modifier.background(target)) }\n"
            "fun B(target: Color) { val c by animateColorAsState(target, spec) }\n"
            "fun C() {\n    val tint = Color.Transparent\n    Box(Modifier.background(tint))\n    val other = colors.primary\n"
            "    val c by animateColorAsState(other, spec)\n}\n"
        )
        self.assertEqual([], self.violations(source))

    def test_邻态改透明度与不是颜色插值的调用放行(self):
        source = (
            "val a = remember { Animatable(1f) }\n"
            "val b = lerp(colors.primary.copy(alpha = 0f), colors.primary, f)\n"
            "suspend fun f() { a.animateTo(1f, spec) }\n"
            "val g = Brush.verticalGradient(listOf(Color.Transparent, scrim))\n"
            "val c by animateColorAsState(colors.primary.copy(alpha = 0.5f), spec)\n"
        )
        self.assertEqual([], self.violations(source))

    def test_color与动画函数的导入别名仍拦下(self):
        aliased = """
import androidx.compose.animation.animateColorAsState as animate
import androidx.compose.ui.graphics.Color as Hue

fun Button() { animate(Hue.Transparent) }
"""
        self.assertEqual(1, len(self.violations(aliased)))


if __name__ == "__main__":
    unittest.main()
