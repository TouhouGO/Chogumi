"""动效规格门禁的自测：调用处自己写的规格要拦住，唯一出处与主题方案放行（tools/ci/check.sh lint 运行）。"""

from __future__ import annotations

from pathlib import Path
import tempfile
import unittest

from check_motion import EXPRESSIVE_ALLOWED, OWNER, find_violations

TILE = "feature/a/src/main/Tile.kt"

OWNER_TEXT = """
private val Shake = spring<Float>(dampingRatio = 0.25f, stiffness = 1500f)
private val Standard = MotionScheme.standard()
"""

LITERAL_SPRING = """
@Composable
fun Tile() {
    val scale by animateFloatAsState(1f, spring(dampingRatio = 0.5f), label = "s")
}
"""

TYPED_TWEEN = """
private val Fade = tween<Float>(durationMillis = 300)
"""

DIRECT_SCHEME = """
@Composable
fun Fold() {
    val spec = MotionScheme.standard().defaultSpatialSpec<Float>()
}
"""

FROM_THEME = """
@Composable
fun Fold() {
    val spec = LocalAppMotion.current.scheme.defaultSpatialSpec<Float>()
    val fade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val springy = rememberSpringy()
    // spring(dampingRatio = 0.5f)
}
"""


class CheckMotionTest(unittest.TestCase):

    def violations(
        self, text: str, with_owner: bool = True, allowed: dict[str, str] | None = None, extra: dict[str, str] | None = None,
    ) -> list[str]:
        root = Path(tempfile.mkdtemp())
        if with_owner:
            owner = root / OWNER
            owner.parent.mkdir(parents=True)
            owner.write_text(OWNER_TEXT, encoding="utf-8")
        path = root / TILE
        path.parent.mkdir(parents=True)
        path.write_text(text, encoding="utf-8")
        for rel, extra_text in (extra or {}).items():
            (root / rel).parent.mkdir(parents=True, exist_ok=True)
            (root / rel).write_text(extra_text, encoding="utf-8")
        return find_violations(root, allowed or {})

    def test_literal_spring_is_rejected(self):
        found = self.violations(LITERAL_SPRING)
        self.assertEqual(1, len(found))
        self.assertIn("Tile.kt:4", found[0])

    def test_typed_tween_is_rejected(self):
        found = self.violations(TYPED_TWEEN)
        self.assertEqual(1, len(found))
        self.assertIn("tween", found[0])

    def test_hand_written_shared_bounds_is_rejected(self):
        for call in (
            "fun Modifier.fly() = this.sharedBounds(state, scope)\n",
            "fun Modifier.fly() = sharedBounds(state, scope)\n",
            "fun Modifier.fly() = sharedElement(state, scope)\n",
            "val m = Modifier.sharedElementWithCallerManagedVisibility(state, visible)\n",
        ):
            with self.subTest(call=call):
                found = self.violations(call)
                self.assertEqual(1, len(found), found)
                self.assertIn("共享转场", found[0])

    def test_direct_scheme_is_rejected(self):
        found = self.violations(DIRECT_SCHEME)
        self.assertEqual(1, len(found))
        self.assertIn("MotionScheme.standard", found[0])

    def test_theme_specs_comments_and_owner_pass(self):
        self.assertEqual([], self.violations(FROM_THEME))

    def test_expressive_outside_the_list_is_rejected(self):
        found = self.violations("val spec = LocalAppMotion.current.expressive.fastSpatialSpec<Float>()\n")
        self.assertEqual(1, len(found))
        self.assertIn("expressive", found[0])
        self.assertEqual(1, len(self.violations("ExpressiveMotion { Menu() }\n")))

    def test_expressive_in_a_listed_file_passes_and_a_dead_entry_is_reported(self):
        use = "val spec = LocalAppMotion.current.expressive.fastSpatialSpec<Float>()\n"
        self.assertEqual([], self.violations(use, allowed={TILE: "tile"}))
        found = self.violations(FROM_THEME, allowed={TILE: "tile"})
        self.assertEqual(1, len(found))
        self.assertIn("死配置", found[0])

    def test_非规格参数与部分动画参数不能充规格(self):
        calls = (
            "Modifier.animateContentSize(alignment = Alignment.TopStart)",
            "Modifier.animateContentSize(finishedListener = { _, _ -> })",
            "state.animateScrollBy(value = 1f,)",
            "Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)",
            "Modifier.animateItem(placementSpec = null)",
        )
        for call in calls:
            with self.subTest(call=call):
                found = self.violations(call + "\n")
                self.assertEqual(1, len(found), f"缺少真实动画规格参数不能放行：{found}")
                self.assertIn("没传规格", found[0])

    def test_真实规格按名称或合法位置放行(self):
        calls = (
            "Modifier.animateContentSize(animationSpec = spec, alignment = Alignment.TopStart)",
            "Modifier.animateContentSize(spec, Alignment.TopStart, { _, _ -> })",
            "state.animateScrollBy(1f, spec)",
            "state.animateScrollBy(animationSpec = spec, value = 1f)",
            "state.animateScrollBy(if (amount < limit) 1f else 2f, spec)",
            "Modifier.animateContentSize(makeSpec<Float, Int>())",
            "Modifier.animateItem(fadeInSpec = null, placementSpec = spec, fadeOutSpec = null)",
            "Modifier.animateItem(null, spec, null)",
        )
        for call in calls:
            with self.subTest(call=call):
                self.assertEqual([], self.violations(call + "\n"))

    def test_嵌套表达式与字符串不能伪装成规格参数(self):
        calls = (
            'Modifier.animateContentSize(alignment = find("animationSpec = spec"))',
            "state.animateScrollBy(value = choose(1f, 2f))",
            'Modifier.animateContentSize(finishedListener = { _, _ -> println("animationSpec = spec") })',
        )
        for call in calls:
            with self.subTest(call=call):
                self.assertEqual(1, len(self.violations(call + "\n")))

    def test_missing_owner_is_reported(self):
        found = self.violations(FROM_THEME, with_owner=False)
        self.assertEqual(1, len(found))
        self.assertIn("不存在", found[0])

    def test_导入别名的spring拦下(self):
        found = self.violations("import androidx.compose.animation.core.spring as springy\n\nval s = springy<Float>(dampingRatio = 0.5f)\n")
        self.assertEqual(1, len(found))
        self.assertIn("Tile.kt:3", found[0])

    def test_另一个文件的类型别名方案拦下(self):
        found = self.violations(
            "fun Fold() {\n    val spec = Scheme.standard().defaultSpatialSpec<Float>()\n}\n",
            extra={"feature/a/src/main/Aliases.kt": "typealias Scheme = androidx.compose.material3.MotionScheme\n"},
        )
        self.assertEqual(1, len(found))
        self.assertIn("Tile.kt:2", found[0])

    def test_拆成两行的方案调用拦下(self):
        self.assertEqual(1, len(self.violations("val spec = MotionScheme\n    .standard()\n")))

    def test_放行清单按仓库路径_同名文件不能冒用(self):
        self.assertTrue(all("/" in name for name in EXPRESSIVE_ALLOWED))
        found = self.violations("val spec = LocalAppMotion.current.expressive.fastSpatialSpec<Float>()\n", allowed={"other/Tile.kt": "tile"})
        self.assertEqual(2, len(found), found)
        self.assertTrue(any("Tile.kt:1" in v for v in found))

    def test_不点名的expressive也拦下(self):
        found = self.violations("val spec = with(LocalAppMotion.current) { expressive.fastSpatialSpec<Float>() }\n")
        self.assertEqual(1, len(found), found)
        self.assertIn("expressive", found[0])

    def test_尺寸动画不用expressive_放行清单里也不行(self):
        found = self.violations(
            "val m = Modifier.animateContentSize(LocalAppMotion.current.expressive.defaultSpatialSpec())\n", allowed={TILE: "tile"},
        )
        self.assertEqual(1, len(found), found)
        self.assertIn("animateContentSize", found[0])

    def test_Float前缀的规格类也拦下(self):
        for spec in ("FloatSpringSpec(dampingRatio = 0.5f)", "FloatTweenSpec(300)"):
            with self.subTest(spec=spec):
                self.assertEqual(1, len(self.violations(f"val s = {spec}\n")))

    def test_不传规格的动画一律拦下(self):
        calls = (
            "suspend fun f() { scroll.animateScrollTo(0) }",
            "suspend fun f() { anim.animateTo(1f) }",
            'val a by animateFloatAsState(if (x) 1f else 0f, label = "a")',
            'val a by animateDpAsState(8.dp, label = "a")',
            "val a by animateColorAsState(colors.primary)",
            "val e = fadeIn() + expandVertically(spatial)",
            "val e = slideInHorizontally { it }",
            "Crossfade(targetState = page) { Page(it) }",
            "AnimatedContent(targetState = page) { Page(it) }",
            "val a by transition.animateFloat { if (it) 1f else 0f }",
            'val a by transition.animateDp(label = "d") { if (it) 8.dp else 0.dp }',
        )
        for call in calls:
            with self.subTest(call=call):
                found = self.violations(call + "\n")
                self.assertEqual(1, len(found), found)
                self.assertIn("没传规格", found[0])

    def test_传了规格的动画放行(self):
        calls = (
            "suspend fun f() { scroll.animateScrollTo(0, spec) }",
            "suspend fun f() { anim.animateTo(1f, motion.fastSpatialSpec()) { update(value) } }",
            'val a by animateFloatAsState(if (x) 1f else 0f, spec, label = "a")',
            "val a by animateColorAsState(targetValue = colors.primary, animationSpec = effects)",
            "val e = fadeIn(effects) + expandVertically(spatial)",
            "val e = slideInHorizontally(spatial) { it }",
            "Crossfade(page, Modifier, effects) { Page(it) }",
            "AnimatedContent(targetState = page, transitionSpec = { fadeIn(e) togetherWith fadeOut(e) }) { Page(it) }",
            "val a by transition.animateFloat(transitionSpec = { spatial }) { if (it) 1f else 0f }",
            "val a by infinite.animateFloat(0f, 1f, repeat, label = \"r\")",
        )
        for call in calls:
            with self.subTest(call=call):
                self.assertEqual([], self.violations(call + "\n"))

    def test_报的名字不带换行(self):
        found = self.violations("val s = spring\n<Float>(1f)\n")
        self.assertEqual(1, len(found))
        self.assertIn("`spring`", found[0])

    def test_字符串里的别名名字不算规格(self):
        self.assertEqual([], self.violations("import androidx.compose.animation.core.spring as springy\n\nval s = \"springy(1f)\"\n"))


if __name__ == "__main__":
    unittest.main()
