"""底部面板门禁的自测：每条规矩各给一个违规样例，确认门禁真的拦得住（tools/ci/check.sh lint 运行）。"""

from pathlib import Path
import tempfile
import unittest

from check_sheets import NAV_TRANSITION_READERS, NAVIGATOR, find_violations

READER = "val v = LocalNavAnimatedContentScope.current\n"
READERS_OK = {reader: READER for reader in NAV_TRANSITION_READERS}
NAVIGATOR_OK = "val decorators = listOf(rememberPageSheetNavEntryDecorator(sheets))\n"

PAGE_SHEET = """
@Composable
fun FilterSheet(onDismiss: () -> Unit) {
    PageModalSheet(onDismiss = onDismiss, fullHeight = false) {
        Text("筛选")
    }
}
"""


class CheckSheetsTest(unittest.TestCase):

    def tree(self, files: dict[str, str]) -> Path:
        root = Path(tempfile.mkdtemp())
        for rel, text in {NAVIGATOR: NAVIGATOR_OK, **READERS_OK, **files}.items():
            path = root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        return root

    def violations(self, files: dict[str, str]) -> list[str]:
        return find_violations(self.tree(files))

    def test_页面面板放行(self):
        self.assertEqual([], self.violations({"feature/a/src/main/FilterSheet.kt": PAGE_SHEET}))

    def test_官方面板拦下_不跳页的也一样(self):
        window = PAGE_SHEET.replace("PageModalSheet(onDismiss = onDismiss, fullHeight = false)", "ModalBottomSheet(onDismissRequest = onDismiss)")
        found = self.violations({"feature/a/src/main/FilterSheet.kt": window})
        self.assertEqual(1, len(found))
        self.assertIn("ModalBottomSheet", found[0])

    def test_换名导入官方面板拦下(self):
        aliased = "import androidx.compose.material3.ModalBottomSheet as Sheet\n\n" + PAGE_SHEET.replace(
            "PageModalSheet(onDismiss = onDismiss, fullHeight = false)", "Sheet(onDismissRequest = onDismiss)")
        found = self.violations({"feature/a/src/main/FilterSheet.kt": aliased})
        self.assertEqual(1, len(found))
        self.assertIn("ModalBottomSheet", found[0])

    def test_官方底部面板脚手架拦下(self):
        scaffold = PAGE_SHEET.replace("PageModalSheet(onDismiss = onDismiss, fullHeight = false)", "BottomSheetScaffold(sheetContent = {})")
        found = self.violations({"feature/a/src/main/FilterSheet.kt": scaffold})
        self.assertEqual(1, len(found))
        self.assertIn("BottomSheetScaffold", found[0])

    def test_跨行空格和全限定调用仍拦下(self):
        for call in ("ModalBottomSheet \n (onDismissRequest = {})", "androidx.compose.material3.ModalBottomSheet\n({})"):
            with self.subTest(call=call):
                self.assertEqual(1, len(self.violations({"feature/a/src/main/Sheet.kt": call})))

    def test_块注释与行尾真代码分开识别(self):
        self.assertEqual([], self.violations({"feature/a/src/main/Sheet.kt": "val a = 1 /* ModalBottomSheet({}) */"}))
        self.assertEqual(1, len(self.violations({"feature/a/src/main/Sheet.kt": "/* note */ ModalBottomSheet({})"})))

    def test_注释里提到官方面板不算(self):
        commented = PAGE_SHEET.replace('Text("筛选")', '// 不用 ModalBottomSheet(：独立窗口\n        Text("筛选")')
        self.assertEqual([], self.violations({"feature/a/src/main/FilterSheet.kt": commented}))

    def test_字符串只是提到官方面板不算_模板表达式仍要拦(self):
        text_only = 'val example = "ModalBottomSheet(onDismissRequest = {})"\n'
        self.assertEqual([], self.violations({"feature/a/src/main/FilterSheet.kt": text_only}))
        interpolated = 'val sheet = "${ModalBottomSheet(onDismissRequest = {})}"\n'
        self.assertEqual(1, len(self.violations({"feature/a/src/main/FilterSheet.kt": interpolated})))

    def test_读导航转场状态拦下(self):
        found = self.violations({
            "feature/a/src/main/FilterSheet.kt": PAGE_SHEET,
            "core/ui/src/main/Sheet.kt": "val t = LocalNavAnimatedContentScope.current.transition\n",
        })
        self.assertEqual(1, len(found))
        self.assertIn("LocalNavAnimatedContentScope", found[0])

    def test_容器转换与进场返回取转场作用域放行(self):
        for reader in NAV_TRANSITION_READERS:
            self.assertEqual([], self.violations({reader: "val v = LocalNavAnimatedContentScope.current\n"}))

    def test_放行文件不存在或已不读转场作用域拦下(self):
        missing = self.tree({"feature/a/src/main/FilterSheet.kt": PAGE_SHEET})
        reader = sorted(NAV_TRANSITION_READERS)[0]
        (missing / reader).unlink()
        found = find_violations(missing)
        self.assertEqual(1, len(found))
        self.assertIn("不存在", found[0])
        stale = self.violations({reader: "val v = 1\n"})
        self.assertEqual(1, len(stale))
        self.assertIn("已不读", stale[0])

    def test_没挂页面面板层装饰器拦下(self):
        found = self.violations({
            "feature/a/src/main/FilterSheet.kt": PAGE_SHEET,
            NAVIGATOR: "val decorators = listOf()\n",
        })
        self.assertEqual(1, len(found))
        self.assertIn("rememberPageSheetNavEntryDecorator", found[0])

    def test_只导入或只名字相近的装饰器不算挂上(self):
        for navigator in (
            "import x.rememberPageSheetNavEntryDecorator\nval decorators = listOf()\n",
            "val decorators = listOf(rememberPageSheetNavEntryDecoratorOld(sheets))\n",
        ):
            with self.subTest(navigator=navigator):
                found = self.violations({NAVIGATOR: navigator})
                self.assertEqual(1, len(found), found)
                self.assertIn("rememberPageSheetNavEntryDecorator", found[0])

    def test_带类型实参或拆行的装饰器调用放行(self):
        for navigator in (
            "val decorators = listOf(rememberPageSheetNavEntryDecorator<Route>(sheets))\n",
            "val decorators = listOf(\n    rememberPageSheetNavEntryDecorator\n        (sheets),\n)\n",
        ):
            with self.subTest(navigator=navigator):
                self.assertEqual([], self.violations({NAVIGATOR: navigator}))


if __name__ == "__main__":
    unittest.main()
