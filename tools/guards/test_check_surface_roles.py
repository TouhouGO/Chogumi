"""容器底色门禁的自测：违规样例要拦住，合规写法放行（tools/ci/check.sh lint 运行）。"""

from pathlib import Path
import tempfile
import unittest

from check_surface_roles import THEME, find_violations


class CheckSurfaceRolesTest(unittest.TestCase):

    def violations(self, text: str, relative: str = "feature/a/src/main/Tile.kt") -> list[str]:
        root = Path(tempfile.mkdtemp())
        path = root / relative
        path.parent.mkdir(parents=True)
        path.write_text(text, encoding="utf-8")
        return find_violations(root)

    def test_页面直接取最低容器色拦下(self):
        found = self.violations("@Composable\nfun Tile() {\n    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest) {}\n}\n")
        self.assertEqual(1, len(found))
        self.assertIn("Tile.kt:3", found[0])

    def test_改写的取法也拦下(self):
        for source in (
            "val c = with(MaterialTheme.colorScheme) { surfaceContainerLowest }\n",
            "val pick = ColorScheme::surfaceContainerLowest\n",
            "val c = MaterialTheme.colorScheme\n    .surfaceContainerLowest\n",
            "val c = MaterialTheme.colorScheme.`surfaceContainerLowest`\n",
            'val s = "${MaterialTheme.colorScheme.surfaceContainerLowest}"\n',
        ):
            with self.subTest(source=source):
                self.assertEqual(1, len(self.violations(source)))

    def test_浮起的块与注释字符串放行(self):
        source = (
            "// surfaceContainerLowest 在深色下是黑的\n"
            "val c = MaterialTheme.colorScheme.raisedContainer\n"
            'val s = "surfaceContainerLowest // 不是代码"\n'
            "val d = MaterialTheme.colorScheme.surfaceContainerLow\n"
        )
        self.assertEqual([], self.violations(source))

    def test_配色定义处放行(self):
        source = "val ColorScheme.raisedContainer: Color get() = if (isDark()) surfaceContainerHigh else surfaceContainerLowest\n"
        self.assertEqual([], self.violations(source, THEME + "Theme.kt"))


if __name__ == "__main__":
    unittest.main()
