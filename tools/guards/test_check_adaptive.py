"""屏宽自适应门禁的自测：每条规矩一个样例（tools/ci/check.sh lint 运行）。"""

from pathlib import Path
import tempfile
import unittest

from check_adaptive import find_violations

GRID_ALLOWED = {("List.kt", "TabletList"): "列表行"}
TEXT_ALLOWED = {("Days.kt", "DaySelector"): "单字"}
ESCAPE_ALLOWED = {("Badge.kt", "OverflowBadge"): "越出"}
CHUNKED_ALLOWED = {("Mosaic.kt", "Mosaic"): "张数定版式"}
BASE = {
    "core/ui/src/main/ContentGrid.kt": "fun ContentGrid() {\n    LazyVerticalGrid(columns = GridCells.Adaptive(100.dp)) {}\n}\n",
    "core/ui/src/main/ConnectedChoiceGroup.kt": "fun <T> ConnectedChoiceGroup() {\n    ConnectedButtonGroup(options) { o, _ -> Text(o) }\n}\n",
    "feature/a/src/main/List.kt": "fun TabletList() {\n    LazyVerticalGrid(columns = GridCells.Fixed(2)) {}\n}\n",
    "feature/a/src/main/Badge.kt": "fun OverflowBadge() {\n    Box(Modifier.wrapContentSize(unbounded = true))\n}\n",
    "feature/a/src/main/Mosaic.kt": "import androidx.compose.runtime.Composable\n\nfun Mosaic() {\n    images.chunked(3).forEach { Row {} }\n}\n",
    "feature/a/src/main/Days.kt": "fun DaySelector() {\n    ConnectedButtonGroup(options = days, selected = d, onSelect = {}) { day, _ -> Text(day) }\n}\n",
}


class CheckAdaptiveTest(unittest.TestCase):

    def violations(self, files: dict[str, str]) -> list[str]:
        root = Path(tempfile.mkdtemp())
        for rel, text in {**BASE, **files}.items():
            path = root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        return find_violations(root, GRID_ALLOWED, TEXT_ALLOWED, ESCAPE_ALLOWED, CHUNKED_ALLOWED)

    def test_规范写法放行(self):
        page = "fun Page() {\n    ContentGrid(cell = GridCell.Poster) {}\n    ConnectedChoiceGroup(options, s, { it }, {})\n    Card(Modifier.width(108.dp))\n}\n"
        self.assertEqual([], self.violations({"feature/b/src/main/Page.kt": page}))

    def test_页面自己定列数拦下(self):
        page = "fun Page() {\n    val columns = GridCells.Adaptive(112.dp)\n}\n"
        found = self.violations({"feature/b/src/main/Page.kt": page})
        self.assertEqual(1, len(found))
        self.assertIn("GridCells.Adaptive", found[0])

    def test_页面自己写惰性网格拦下(self):
        page = "fun Page() {\n    LazyVerticalGrid(columns = cells, contentPadding = PaddingValues(start = 16.dp, end = 24.dp)) {}\n}\n"
        found = self.violations({"feature/b/src/main/Page.kt": page})
        self.assertEqual(1, len(found))
        self.assertIn("ContentGrid", found[0])

    def test_交错网格与windowed切行拦下(self):
        page = ("import androidx.compose.runtime.Composable\n\nfun Page() {\n"
                "    LazyVerticalStaggeredGrid(columns = x) {}\n    items.windowed(3, 3)\n}\n")
        self.assertEqual(2, len(self.violations({"feature/b/src/main/Page.kt": page})))

    def test_界面里chunked切行拦下(self):
        page = "import androidx.compose.foundation.layout.Row\n\nfun Friends() {\n    friends.chunked(4).forEach { row -> Row {} }\n}\n"
        found = self.violations({"feature/b/src/main/Friends.kt": page})
        self.assertEqual(1, len(found))
        self.assertIn("AdaptiveGridRows", found[0])

    def test_FlowRow自定每行个数拦下_拆行与白名单放行(self):
        page = ("import androidx.compose.foundation.layout.FlowRow\n\nfun Chips() {\n"
                "    FlowRow(\n        maxItemsInEachRow\n            = 3,\n    ) {}\n}\n")
        found = self.violations({"feature/b/src/main/Chips.kt": page})
        self.assertEqual(1, len(found))
        self.assertIn("maxItemsInEachRow", found[0])
        allowed = "import androidx.compose.runtime.Composable\n\nfun Mosaic() {\n    FlowRow(maxItemsInEachRow = 6) {}\n}\n"
        self.assertEqual([], self.violations({"feature/a/src/main/Mosaic.kt": allowed}))
        self.assertEqual([], self.violations({"feature/b/src/main/Chips.kt": page.replace("maxItemsInEachRow\n            = 3,", "")}))

    def test_非界面文件的chunked放行(self):
        dao = "fun getByIds(ids: List<Int>) = ids.chunked(SQLITE_IN_CHUNK).flatMap { query(it) }\n"
        self.assertEqual([], self.violations({"core/data/src/main/Dao.kt": dao}))

    def test_组件越出父级宽度拦下(self):
        card = "fun Card() {\n    Poster(Modifier.wrapContentWidth(Alignment.Start).width(108.dp))\n}\n"
        found = self.violations({"core/ui/src/main/Card.kt": card})
        self.assertEqual(1, len(found))
        self.assertIn("wrapContentWidth", found[0])

    def test_往按钮组里直接画文字拦下(self):
        page = "fun Sort() {\n    ConnectedButtonGroup(options = sorts, selected = s, onSelect = {}) { sort, _ ->\n        Text(sort.name, maxLines = 1)\n    }\n}\n"
        found = self.violations({"feature/b/src/main/Sort.kt": page})
        self.assertEqual(1, len(found))
        self.assertIn("ConnectedChoiceGroup", found[0])

    def test_按钮组只画图标放行(self):
        page = "fun Tabs() {\n    ConnectedButtonGroup(options = tabs, selected = t, onSelect = {}) { tab, _ -> Icon(tab.icon, null) }\n}\n"
        self.assertEqual([], self.violations({"feature/b/src/main/Tabs.kt": page}))

    def test_白名单死配置拦下(self):
        found = self.violations({"feature/a/src/main/List.kt": "fun TabletList() {}\n"})
        self.assertTrue(any("死配置" in v for v in found))

    def test_导入别名的网格列数拦下(self):
        page = ("import androidx.compose.foundation.lazy.grid.GridCells as Cells\n\n"
                "fun Page() {\n    val columns = Cells.Adaptive(112.dp)\n}\n")
        found = self.violations({"feature/b/src/main/Page.kt": page})
        self.assertEqual(1, len(found))
        self.assertIn("Page.kt:4", found[0])

    def test_另一个文件的类型别名网格拦下(self):
        alias = "typealias Grid = androidx.compose.foundation.lazy.grid.LazyVerticalGrid\n"
        page = "fun Page() {\n    Grid(columns = cells) {}\n}\n"
        found = self.violations({"feature/b/src/main/Aliases.kt": alias, "feature/b/src/main/Page.kt": page})
        self.assertEqual(1, len(found))
        self.assertIn("Page.kt:2", found[0])

    def test_拆成两行的chunked拦下(self):
        page = "import androidx.compose.foundation.layout.Row\n\nfun Friends() {\n    friends.\n        chunked(4).forEach { row -> Row {} }\n}\n"
        self.assertEqual(1, len(self.violations({"feature/b/src/main/Friends.kt": page})))

    def test_字符串里的别名名字不算调用(self):
        page = ("import androidx.compose.foundation.lazy.grid.GridCells as Cells\n\n"
                "fun Page() {\n    val hint = \"Cells.Adaptive(112.dp)\"\n}\n")
        self.assertEqual([], self.violations({"feature/b/src/main/Page.kt": page}))


    def test_导入嵌套的列数类拦下(self):
        nested = ("import androidx.compose.foundation.lazy.grid.GridCells.Fixed\n\n"
                  "fun Page() {\n    ContentList(columns = Fixed(2))\n}\n")
        aliased = ("import androidx.compose.foundation.lazy.grid.GridCells.Adaptive as Cells\n\n"
                   "fun Page() {\n    val columns = Cells(112.dp)\n}\n")
        for page in (nested, aliased):
            with self.subTest(page):
                found = self.violations({"feature/b/src/main/Page.kt": page})
                self.assertEqual(1, len(found))
                self.assertIn("GridCells.", found[0])

    def test_不写接收者的chunked拦下(self):
        extension = "import androidx.compose.foundation.layout.Row\n\nprivate fun <T> List<T>.rows() = chunked(4)\n"
        scoped = "import androidx.compose.foundation.layout.Row\n\nfun Friends() {\n    with(friends) { chunked(4) }\n}\n"
        for page in (extension, scoped):
            with self.subTest(page):
                self.assertEqual(1, len(self.violations({"feature/b/src/main/Friends.kt": page})))

    def test_带修饰符的函数不借上一个函数的白名单(self):
        days = ("fun DaySelector() {\n    ConnectedButtonGroup(options = days) { day, _ -> Text(day) }\n}\n\n"
                "private inline fun SortSelector() {\n    ConnectedButtonGroup(options = sorts) { sort, _ -> Text(sort) }\n}\n")
        found = self.violations({"feature/a/src/main/Days.kt": days})
        self.assertEqual(1, len(found))
        self.assertIn("SortSelector", found[0])


if __name__ == "__main__":
    unittest.main()
