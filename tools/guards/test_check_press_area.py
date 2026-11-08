"""「整块可点」门禁的自测：每条规矩一个样例（tools/ci/check.sh lint 运行）。"""

from pathlib import Path
import tempfile
import unittest

from check_press_area import SURFACE_ALLOWED, find_violations

ALLOWED = {("Stats.kt", "TopRated"): "角标随封面圆角裁"}

TOP_RATED = """
@Composable
fun TopRated() {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).clickable { open() }) {
        SubjectPoster(url = u)
        Text("9")
    }
}
"""


class CheckPressAreaTest(unittest.TestCase):

    def violations(self, files: dict[str, str]) -> list[str]:
        root = Path(tempfile.mkdtemp())
        for rel, text in {"feature/a/src/main/Stats.kt": TOP_RATED, **files}.items():
            path = root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        return find_violations(root, ALLOWED, {})

    def card(self, modifier: str) -> dict[str, str]:
        return {"feature/a/src/main/Card.kt": f"""
@Composable
fun Card() {{
    Column(modifier = Modifier{modifier}) {{
        SubjectPoster(url = u)
        Text(title)
    }}
}}
"""}

    def test_透明裁切加点击包住带字的一整块拦下(self):
        found = self.violations(self.card(".width(104.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)"))
        self.assertEqual(1, len(found))
        self.assertIn("PressArea", found[0])

    def test_把裁切传给布局函数也拦下(self):
        found = self.violations({"feature/a/src/main/Poster.kt": """
fun PosterCard() {
    PosterLayout(title = t, modifier = modifier.clip(RoundedCornerShape(8.dp)).clickable { open() }) {
        SubjectPoster(url = u)
    }
}
"""})
        self.assertEqual(1, len(found))

    def test_长按与选中的点击修饰符一样拦下(self):
        for press in (".combinedClickable(onClick = a, onLongClick = b)", ".selectable(selected = s) { pick() }",
                      ".toggleable(value = v) { flip() }"):
            with self.subTest(press):
                self.assertEqual(1, len(self.violations(self.card(f".clip(RoundedCornerShape(12.dp)){press}"))))

    def test_有底色的不算(self):
        self.assertEqual([], self.violations(self.card(".clip(MaterialTheme.shapes.small).background(c).clickable { open() }")))

    def test_只有内边距的透明裁切照样拦下(self):
        found = self.violations(self.card(".clip(RoundedCornerShape(20.dp)).clickable { open() }.padding(horizontal = 10.dp)"))
        self.assertEqual(1, len(found))

    def test_纯图或圆形头像的裁切放行(self):
        image = {"feature/a/src/main/Img.kt": "fun Img() {\n    SubjectPoster(url = u, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { open() })\n}\n"}
        avatar = self.card(".clip(CircleShape).clickable { open() }")
        self.assertEqual([], self.violations(image))
        self.assertEqual([], self.violations(avatar))

    def test_白名单死条目拦下(self):
        found = find_violations(Path(tempfile.mkdtemp()), ALLOWED, {})
        self.assertEqual(1, len(found))
        self.assertIn("死配置", found[0])

    def test_导入别名的裁切和点击拦下(self):
        files = {"feature/a/src/main/Card.kt": """
import androidx.compose.ui.draw.clip as round
import androidx.compose.foundation.clickable as tap

@Composable
fun Card() {
    Column(modifier = Modifier.round(RoundedCornerShape(12.dp)).tap(onClick = onClick)) {
        SubjectPoster(url = u)
        Text(title)
    }
}
"""}
        self.assertEqual(1, len(self.violations(files)))

    def test_拆成两行的修饰符链拦下(self):
        found = self.violations(self.card(".\n        clip(RoundedCornerShape(12.dp)).\n        clickable(onClick = onClick)"))
        self.assertEqual(1, len(found))

    def test_字符串里的别名名字不算修饰符(self):
        files = {"feature/a/src/main/Card.kt": """
import androidx.compose.ui.draw.clip as round

@Composable
fun Card() {
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Text(".round(shape)")
    }
}
"""}
        self.assertEqual([], self.violations(files))

    def test_Surface白名单死条目拦下(self):
        allowed = {("Shared.kt", "Shared"): "共享控件"}
        found = find_violations(Path(tempfile.mkdtemp()), {}, allowed)
        self.assertEqual(1, len(found))
        self.assertIn("Surface 白名单", found[0])

    def test_Surface加文字并整块可点要改用PressArea(self):
        source = """
@Composable
fun Entry() {
    Surface(
        onClick = { open() },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Text("探索")
    }
}
"""
        root = Path(tempfile.mkdtemp())
        path = root / "feature/a/src/main/Entry.kt"
        path.parent.mkdir(parents=True)
        path.write_text(source, encoding="utf-8")
        found = find_violations(root, {}, {})
        self.assertEqual(1, len(found))
        self.assertIn("可点的 Surface", found[0])

    def test_Surface与Text的别名和全限定名都拦下(self):
        samples = {
            "alias": """
import androidx.compose.material3.Surface as M3Surface
import androidx.compose.material3.Text as M3Text
fun Entry() { M3Surface(onClick = go) { M3Text("入口") } }
""",
            "fully qualified": """
fun Entry() {
    androidx.compose.material3.Surface(onClick = go) {
        androidx.compose.material3.Text("入口")
    }
}
""",
        }
        for name, source in samples.items():
            with self.subTest(name):
                root = Path(tempfile.mkdtemp())
                path = root / "feature/a/src/main/Entry.kt"
                path.parent.mkdir(parents=True)
                path.write_text(source, encoding="utf-8")
                self.assertEqual(1, len(find_violations(root, {}, {})), source)

    def test_Surface的内容跨行或字符串相邻不误判(self):
        rejected = """
fun Entry() {
    Surface(
        onClick = go,
        color = c,
    ) {
        Column {
            Text(value = "入口")
        }
    }
}
"""
        allowed = """
fun Legend() {
    val copy = "Surface(onClick)"
    val text = "Text(value)"
    Button(onClick = go) { Text(text) }
    Chip(onClick = go, label = { Text(copy) })
}
"""
        for source, expected in ((rejected, 1), (allowed, 0)):
            with self.subTest(expected=expected):
                root = Path(tempfile.mkdtemp())
                path = root / "feature/a/src/main/Entry.kt"
                path.parent.mkdir(parents=True)
                path.write_text(source, encoding="utf-8")
                self.assertEqual(expected, len(find_violations(root, {}, {})), source)

    def test_Surface里的label插槽也算带文字(self):
        source = """
fun PersonChip(label: @Composable () -> Unit) {
    Surface(onClick = go) { label() }
}
"""
        root = Path(tempfile.mkdtemp())
        path = root / "feature/a/src/main/Chip.kt"
        path.parent.mkdir(parents=True)
        path.write_text(source, encoding="utf-8")
        self.assertEqual(1, len(find_violations(root, {}, {})))

    def test_Surface的具名content也要检查文字(self):
        source = """
fun Entry() {
    Surface(onClick = go, content = { Text("入口") })
}
"""
        root = Path(tempfile.mkdtemp())
        path = root / "feature/a/src/main/Entry.kt"
        path.parent.mkdir(parents=True)
        path.write_text(source, encoding="utf-8")
        self.assertEqual(1, len(find_violations(root, {}, {})))

    def test_反引号模板后的Surface仍要检查文字(self):
        source = '''
fun Entry() {
    val text = "$`q"r`"
    Surface(onClick = go) { Text("入口") }
}
'''
        root = Path(tempfile.mkdtemp())
        path = root / "feature/a/src/main/Entry.kt"
        path.parent.mkdir(parents=True)
        path.write_text(source, encoding="utf-8")
        self.assertEqual(1, len(find_violations(root, {}, {})))

    def test_Surface首参数位置调用也要检查文字(self):
        source = '''
fun Entry() {
    Surface(go) { Text("入口") }
}
'''
        root = Path(tempfile.mkdtemp())
        path = root / "feature/a/src/main/Entry.kt"
        path.parent.mkdir(parents=True)
        path.write_text(source, encoding="utf-8")
        self.assertEqual(1, len(find_violations(root, {}, {})))

    def test_共享chip和搜索容器是有理由的例外(self):
        root = Path(tempfile.mkdtemp())
        sources = {
            "HeaderChip.kt": 'fun HeaderChip() { Surface(onClick = go) { Text("资料") } }',
            "ContainedSearchField.kt": 'fun SearchContainer() { Surface(onClick = go) { Text("搜索") } }',
        }
        for name, source in sources.items():
            path = root / f"core/ui/src/main/{name}"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source, encoding="utf-8")
        self.assertEqual([], find_violations(root, {}, SURFACE_ALLOWED))


    def test_onClick不在第一个参数的Surface也要检查文字(self):
        for arguments in ("color = c, onClick = go", "selected = s, onClick = go", "modifier = m, onClick = go"):
            with self.subTest(arguments):
                root = Path(tempfile.mkdtemp())
                path = root / "feature/a/src/main/Entry.kt"
                path.parent.mkdir(parents=True)
                path.write_text(f'fun Entry() {{\n    Surface({arguments}) {{ Text("入口") }}\n}}\n', encoding="utf-8")
                found = find_violations(root, {}, {})
                self.assertEqual(1, len(found))
                self.assertIn("Entry.kt:2", found[0])

    def test_开关重载的Surface加文字也要改用PressArea(self):
        sources = {
            "具名": 'fun Tile() {\n    Surface(checked = s, onCheckedChange = { flip() }, color = c) { Text("标签") }\n}\n',
            "拆行": 'fun Tile() {\n    Surface(\n        checked = s,\n        onCheckedChange = flip,\n    ) {\n        Row { Text("标签") }\n    }\n}\n',
            "别名": 'import androidx.compose.material3.Surface as M3Surface\nfun Tile() {\n    M3Surface(color = c, checked = s, onCheckedChange = flip) { Text("标签") }\n}\n',
        }
        for name, source in sources.items():
            with self.subTest(name):
                root = Path(tempfile.mkdtemp())
                path = root / "feature/a/src/main/Tile.kt"
                path.parent.mkdir(parents=True)
                path.write_text(source, encoding="utf-8")
                found = find_violations(root, {}, {})
                self.assertEqual(1, len(found), found)
                self.assertIn("可点的 Surface", found[0])

    def test_可点的Card加文字也要改用PressArea(self):
        for card in ("Card", "ElevatedCard", "OutlinedCard"):
            with self.subTest(card):
                root = Path(tempfile.mkdtemp())
                path = root / "feature/a/src/main/Entry.kt"
                path.parent.mkdir(parents=True)
                path.write_text(f'fun Entry() {{\n    {card}(onClick = go) {{ Text("入口") }}\n    {card}(modifier = m) {{ Text("说明") }}\n}}\n', encoding="utf-8")
                found = find_violations(root, {}, {})
                self.assertEqual(1, len(found))
                self.assertIn(f"可点的 {card}", found[0])

    def test_不可点的Surface放行(self):
        root = Path(tempfile.mkdtemp())
        path = root / "feature/a/src/main/Entry.kt"
        path.parent.mkdir(parents=True)
        path.write_text('fun Entry() {\n    Surface(modifier = m, color = c) { Text("入口") }\n}\n', encoding="utf-8")
        self.assertEqual([], find_violations(root, {}, {}))

    def test_带修饰符的函数不借上一个函数的白名单(self):
        source = TOP_RATED + """
@Composable
private inline fun Other() {
    Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable { open() }) { Text(t) }
}
"""
        found = self.violations({"feature/a/src/main/Stats.kt": source})
        self.assertEqual(1, len(found))
        self.assertIn("Other", found[0])


if __name__ == "__main__":
    unittest.main()
