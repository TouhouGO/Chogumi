"""状态栏门禁的自测：每条规矩各给一个违规样例，确认门禁真的拦得住（tools/ci/check.sh lint 运行）。"""

from pathlib import Path
import tempfile
import unittest

from check_status_bar import HEADER_BAR_HOME, STICKY_HOMES, find_violations

SHEET_PATH = "core/ui/src/main/Sheet.kt"
ALLOWED = {SHEET_PATH: "面板停靠位置"}
STICKY_HOME_TEXT = "fun LazyListScope.floorCommentsSection() { stickyHeader(key = \"sort\") { Bar() } }\n"

HEADER_PAGE = """
@Composable
fun DetailsScreen(onBack: () -> Unit) {
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Scaffold(topBar = { HeaderTopAppBar(title = "t", fill = { 0f }, onBack = onBack) }) {
        Header(topPadding = statusBarHeight)
    }
}
"""

SHEET = """
@Composable
fun Sheet() {
    val statusBarTop = WindowInsets.statusBars
}
"""

HEADER_BAR = """
@Composable
fun HeaderTopAppBar(title: String, fill: () -> Float, onBack: (() -> Unit)?) {
    TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent))
}
"""


class CheckStatusBarTest(unittest.TestCase):

    def violations(self, files: dict[str, str]) -> list[str]:
        root = Path(tempfile.mkdtemp())
        base = {HEADER_BAR_HOME: HEADER_BAR, SHEET_PATH: SHEET, **{home: STICKY_HOME_TEXT for home in STICKY_HOMES}}
        for rel, text in {**base, **files}.items():
            path = root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        return find_violations(root, ALLOWED)

    def test_画进状态栏且用了头部顶栏的放行(self):
        self.assertEqual([], self.violations({"feature/a/src/main/Details.kt": HEADER_PAGE}))

    def test_画进状态栏却没有头部顶栏拦下(self):
        page = HEADER_PAGE.replace("HeaderTopAppBar(title = \"t\", fill = { 0f }, onBack = onBack)", "Row { BackButton() }")
        found = self.violations({"feature/a/src/main/Details.kt": page})
        self.assertEqual(1, len(found))
        self.assertIn("Details.kt", found[0])

    def test_读系统栏高度却没有头部顶栏拦下(self):
        page = "fun P() { val top = WindowInsets.systemBars.asPaddingValues().calculateTopPadding() }"
        self.assertEqual(1, len(self.violations({"feature/a/src/main/P.kt": page})))

    def test_永不让位的顶栏拦下(self):
        page = "fun P() { val behavior = TopAppBarDefaults.pinnedScrollBehavior() }"
        found = self.violations({"feature/a/src/main/P.kt": page})
        self.assertEqual(1, len(found))
        self.assertIn("pinnedScrollBehavior", found[0])

    def test_吸顶项没有柔边拦下(self):
        page = "fun P() { LazyColumn(state = s) { stickyHeader(key = \"sort\") { Bar() } } }"
        found = self.violations({"feature/a/src/main/P.kt": page})
        self.assertEqual(1, len(found))
        self.assertIn("stickyHeaderFade", found[0])

    def test_评论区排序行没有柔边也拦下(self):
        page = "fun P() { LazyColumn(state = s) { floorCommentsSection(comments) } }"
        self.assertEqual(1, len(self.violations({"feature/a/src/main/P.kt": page})))

    def test_吸顶项挂了柔边放行(self):
        page = ("fun P() { LazyColumn(state = s, modifier = Modifier.stickyHeaderFade(e), overscrollEffect = e.overscrollEffect)"
                " { stickyHeader { Bar() } } }")
        self.assertEqual([], self.violations({"feature/a/src/main/P.kt": page}))

    def test_柔边没接回弹拦下(self):
        page = "fun P() { LazyColumn(state = s, modifier = Modifier.stickyHeaderFade(e)) { stickyHeader { Bar() } } }"
        found = self.violations({"feature/a/src/main/P.kt": page})
        self.assertEqual(1, len(found))
        self.assertIn("overscrollEffect", found[0])

    def test_透明度为零的顶栏色也拦下(self):
        page = HEADER_PAGE + "\nval c = TopAppBarDefaults.topAppBarColors(containerColor = x.copy(alpha = 0f))\n"
        self.assertEqual(1, len(self.violations({"feature/a/src/main/Details.kt": page})))

    def test_自己拼透明顶栏拦下(self):
        page = HEADER_PAGE.replace(
            "HeaderTopAppBar(title = \"t\", fill = { 0f }, onBack = onBack)",
            "HeaderTopAppBar(title = \"t\", fill = { 0f }, onBack = onBack); "
            "TopAppBar(colors = TopAppBarDefaults.topAppBarColors(\n    containerColor = Color.Transparent,\n))",
        )
        found = self.violations({"feature/a/src/main/Details.kt": page})
        self.assertEqual(1, len(found))
        self.assertIn("透明容器", found[0])

    def test_注释里提到的不算(self):
        page = "// 别直接读 WindowInsets.statusBars\n@Composable\nfun Page() { Column(Modifier.statusBarsPadding()) {} }\n"
        self.assertEqual([], self.violations({"feature/a/src/main/Page.kt": page}))

    def test_lerp顶栏填色拦下(self):
        found = self.violations({"feature/a/src/main/Bar.kt": "val c = lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surfaceContainer, fill())\n"})
        self.assertEqual(1, len(found))
        self.assertIn("页面底色", found[0])

    def test_白名单死配置拦下(self):
        found = self.violations({SHEET_PATH: "@Composable\nfun Sheet() {}\n"})
        self.assertEqual(1, len(found))
        self.assertIn("死配置", found[0])

    def test_白名单按仓库路径_同名文件不能冒用(self):
        page = "fun Sheet() { val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() }\n"
        found = self.violations({"feature/a/src/main/Sheet.kt": page})
        self.assertEqual(1, len(found), found)
        self.assertIn("feature/a/src/main/Sheet.kt", found[0])

    def test_头部顶栏与吸顶项的归属文件不在了拦下(self):
        for home in (HEADER_BAR_HOME, *STICKY_HOMES):
            with self.subTest(home=home):
                found = self.violations({home: "fun Moved() {}\n"})
                self.assertEqual(1, len(found), found)
                self.assertIn(home, found[0])

    def test_其他读法的状态栏高度也拦下(self):
        for read in (
            "WindowInsets.statusBarsIgnoringVisibility",
            "WindowInsets.safeDrawing",
            "WindowInsets.safeContent",
            "WindowInsets.Companion.statusBars",
            "WindowInsets\n    .statusBars",
        ):
            with self.subTest(read=read):
                page = f"fun P() {{ val top = {read}.asPaddingValues().calculateTopPadding() }}\n"
                self.assertEqual(1, len(self.violations({"feature/a/src/main/P.kt": page})))

    def test_透明顶栏色的其他写法也拦下(self):
        for colors in (
            "TopAppBarDefaults.topAppBarColors().copy(containerColor = Color.Transparent)",
            "TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)",
            "TopAppBarDefaults.topAppBarColors(containerColor = x.copy(alpha = 0.0f))",
            "TopAppBarColors(Color.Transparent, a, b, c, d)",
        ):
            with self.subTest(colors=colors):
                found = self.violations({"feature/a/src/main/P.kt": f"val c = {colors}\n"})
                self.assertEqual(1, len(found), found)
                self.assertIn("透明容器", found[0])

    def test_顶栏不透明的颜色与半透明放行(self):
        page = (
            "val a = TopAppBarDefaults.topAppBarColors(containerColor = surface).copy(titleContentColor = onSurface)\n"
            "val b = TopAppBarDefaults.topAppBarColors(containerColor = x.copy(alpha = 0.5f))\n"
        )
        self.assertEqual([], self.violations({"feature/a/src/main/P.kt": page}))

    def test_换个顺序或换一级容器色的lerp也拦下(self):
        for call in (
            "lerp(colors.surfaceContainer, colors.surface, 1f - fill)",
            "lerp(colors.surface, colors.surfaceContainerHigh, fill)",
            "lerp(stop = colors.surfaceContainer, start = colors.surface, fraction = fill)",
        ):
            with self.subTest(call=call):
                found = self.violations({"feature/a/src/main/Bar.kt": f"val c = {call}\n"})
                self.assertEqual(1, len(found), found)
                self.assertIn("页面底色", found[0])

    def test_与surface无关的lerp放行(self):
        page = "val c = lerp(base, colors.surface, fade)\nval d = lerp(colors.primaryContainer, colors.surfaceContainer, f)\n"
        self.assertEqual([], self.violations({"feature/a/src/main/Bar.kt": page}))

    def test_回弹交给列表的表达式写法放行(self):
        page = ("fun P() { LazyColumn(modifier = Modifier.stickyHeaderFade(e),\n"
                "    overscrollEffect = rememberStickyHeaderEdge(s).overscrollEffect) { stickyHeader { Bar() } } }")
        self.assertEqual([], self.violations({"feature/a/src/main/P.kt": page}))

    def test_让位的顶栏不经heldBy拦下(self):
        for behavior in ("enterAlwaysScrollBehavior", "exitUntilCollapsedScrollBehavior"):
            with self.subTest(behavior=behavior):
                page = (f"fun P() {{ val b = TopAppBarDefaults.{behavior}()\n"
                        "    Scaffold(Modifier.nestedScroll(b.nestedScrollConnection)) {} }\n")
                found = self.violations({"feature/a/src/main/P.kt": page})
                self.assertEqual(1, len(found), found)
                self.assertIn("heldBy", found[0])

    def test_让位的顶栏经heldBy放行(self):
        page = ("fun P(hold: TopBarHold) { val b = TopAppBarDefaults.enterAlwaysScrollBehavior()\n"
                "    val c = remember(b, hold) { b.heldBy(hold) } }\n")
        self.assertEqual([], self.violations({"feature/a/src/main/P.kt": page}))

    def test_状态栏与顶栏API的导入别名仍拦下(self):
        aliased_status = (
            "import androidx.compose.foundation.layout.WindowInsets as Insets\n"
            "fun P() { val top = Insets.statusBars.asPaddingValues().calculateTopPadding() }\n"
        )
        self.assertEqual(1, len(self.violations({"feature/a/src/main/P.kt": aliased_status})))
        aliased_pinned = (
            "import androidx.compose.material3.TopAppBarDefaults as BarDefaults\n"
            "fun P() { val behavior = BarDefaults.pinnedScrollBehavior() }\n"
        )
        self.assertEqual(1, len(self.violations({"feature/a/src/main/P.kt": aliased_pinned})))


if __name__ == "__main__":
    unittest.main()
