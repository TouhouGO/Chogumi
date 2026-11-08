"""「封面只在 SubjectPoster 里裁」门禁的自测：每条规矩一个样例（tools/ci/check.sh lint 运行）。"""

from pathlib import Path
import tempfile
import unittest

from check_cover_clip import find_violations


class CheckCoverClipTest(unittest.TestCase):

    def violations(self, text: str, name: str = "Shelf.kt") -> list[str]:
        root = Path(tempfile.mkdtemp())
        path = root / "feature/a/src/main" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return find_violations(root)

    def test_在封面外面另包一层裁切拦下(self):
        found = self.violations("""
@Composable
fun Card() {
    Box(Modifier.width(96.dp).clip(MaterialTheme.shapes.medium)) {
        SubjectPoster(url, Modifier.fillMaxSize())
        Badge()
    }
}
""")
        self.assertEqual(1, len(found))
        self.assertIn("overlay", found[0])

    def test_图层裁切也拦下(self):
        found = self.violations("""
fun Card() {
    Box(Modifier.graphicsLayer { shape = s; clip = true }) { SubjectPoster(url) }
}
""")
        self.assertEqual(1, len(found))

    def test_给封面的修饰符挂点击拦下(self):
        found = self.violations("""
fun Strip() {
    SubjectPoster(url, modifier = Modifier.width(48.dp).clickable { open() })
}
""")
        self.assertEqual(1, len(found))
        self.assertIn("onClick", found[0])

    def test_形状交给封面_角标放进覆盖层_通过(self):
        self.assertEqual([], self.violations("""
fun Card() {
    SubjectPoster(url, Modifier.width(96.dp), shape = MaterialTheme.shapes.medium, onClick = { open() }) {
        Badge()
    }
}
"""))

    def test_不画封面的函数里裁切不管(self):
        self.assertEqual([], self.violations("""
fun Avatar() { Box(Modifier.clip(CircleShape)) }
fun Card() { SubjectPoster(url) }
"""))

    def test_SubjectPoster_本身不算(self):
        self.assertEqual([], self.violations("fun SubjectPoster(url: String?) { Box(Modifier.clip(shape)) }", name="SubjectPoster.kt"))

    def test_导入别名的封面外裁切拦下(self):
        found = self.violations("""
import io.github.touhougo.chogumi.core.ui.composables.SubjectPoster as Cover
import androidx.compose.ui.draw.clip as round

fun Card() {
    Box(Modifier.width(96.dp).round(MaterialTheme.shapes.medium)) {
        Cover(url, Modifier.fillMaxSize())
    }
}
""")
        self.assertEqual(1, len(found))

    def test_类型别名的封面挂点击拦下(self):
        root = Path(tempfile.mkdtemp())
        files = {
            "core/ui/src/main/Aliases.kt": "typealias Cover = SubjectPoster\n",
            "feature/a/src/main/Strip.kt": "fun Strip() {\n    Cover(url, modifier = Modifier.width(48.dp)\n        .clickable { open() })\n}\n",
        }
        for rel, text in files.items():
            path = root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        self.assertEqual(1, len(find_violations(root)))

    def test_字符串里的别名名字不算封面(self):
        self.assertEqual([], self.violations("""
import io.github.touhougo.chogumi.core.ui.composables.SubjectPoster as Cover

fun Card() {
    Box(Modifier.clip(shape)) { Text("Cover(url)") }
}
"""))


    def test_给封面的修饰符挂长按或选中也拦下(self):
        for press in (".combinedClickable(onClick = a, onLongClick = b)", ".selectable(selected = s) { pick() }"):
            with self.subTest(press):
                found = self.violations(f"fun Strip() {{\n    SubjectPoster(url, modifier = Modifier{press})\n}}\n")
                self.assertEqual(1, len(found))

    def test_条件裁切的图层也拦下_明写false放行(self):
        clipped = "fun Card() {\n    Box(Modifier.graphicsLayer { clip = rounded }) { SubjectPoster(url) }\n}\n"
        self.assertEqual(1, len(self.violations(clipped)))
        unclipped = "fun Card() {\n    Box(Modifier.graphicsLayer { clip = false }) { SubjectPoster(url) }\n}\n"
        self.assertEqual([], self.violations(unclipped))

    def test_带修饰符的函数自成一段(self):
        self.assertEqual([], self.violations("""
fun Avatar() { Box(Modifier.clip(CircleShape)) }
private inline fun Card() { SubjectPoster(url) }
"""))


if __name__ == "__main__":
    unittest.main()
