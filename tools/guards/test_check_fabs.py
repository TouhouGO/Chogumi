"""FAB 位置门禁的自测：每条规矩一个样例（tools/ci/check.sh lint 运行）。"""

from pathlib import Path
import tempfile
import unittest

from check_fabs import find_violations

ALLOWED = {("Fab.kt", "MyFab"): "组件定义"}
FAB_FILE = "fun MyFab(onClick: () -> Unit, modifier: Modifier = Modifier) {\n    FloatingActionButton(onClick = onClick, modifier = modifier) {}\n}\n"
CLEARANCE = "val FabClearance = 88.dp\n"


class CheckFabsTest(unittest.TestCase):

    def violations(self, files: dict[str, str]) -> list[str]:
        root = Path(tempfile.mkdtemp())
        base = {"core/ui/src/main/Fab.kt": FAB_FILE, "core/ui/src/main/FabPlacement.kt": CLEARANCE}
        for rel, text in {**base, **files}.items():
            path = root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        return find_violations(root, ALLOWED)

    def test_规范写法放行(self):
        page = "fun Page() {\n    Scaffold(floatingActionButton = { MyFab(onClick = {}) }) {}\n    Box { SelectionFabMenu(expanded = e, onSelect = {}) }\n}\n"
        self.assertEqual([], self.violations({"feature/a/src/main/Page.kt": page}))

    def test_槽位里自己补导航条高度拦下(self):
        page = "fun Page() {\n    Scaffold(floatingActionButton = { MyFab(onClick = {}, modifier = Modifier.padding(bottom = nav)) }) {}\n}\n"
        found = self.violations({"feature/a/src/main/Page.kt": page})
        self.assertEqual(1, len(found))
        self.assertIn("padding", found[0])

    def test_菜单FAB调用处自己定位拦下(self):
        page = "fun Page() {\n    Box { SelectionFabMenu(expanded = e, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) }\n}\n"
        found = self.violations({"feature/a/src/main/Page.kt": page})
        self.assertEqual(1, len(found))

    def test_槽位外直接用FAB拦下(self):
        page = "fun Page() {\n    Box { FloatingActionButton(onClick = {}, modifier = Modifier.align(Alignment.BottomEnd)) {} }\n}\n"
        found = self.violations({"feature/a/src/main/Page.kt": page})
        self.assertEqual(1, len(found))
        self.assertIn("槽位", found[0])

    def test_扩展FAB随滚动收起拦下(self):
        page = (
            "fun Page() {\n    Scaffold(floatingActionButton = {\n"
            "        ExtendedFloatingActionButton(text = { Text(label) }, icon = {}, onClick = {}, expanded = !list.lastScrolledForward)\n"
            "    }) {}\n}\n"
        )
        found = self.violations({"feature/a/src/main/Page.kt": page})
        self.assertEqual(1, len(found))
        self.assertIn("expanded", found[0])

    def test_固定的扩展FAB放行(self):
        page = "fun Page() {\n    Scaffold(floatingActionButton = { ExtendedFloatingActionButton(onClick = {}) { Text(label) } }) {}\n}\n"
        self.assertEqual([], self.violations({"feature/a/src/main/Page.kt": page}))

    def test_FAB留白多定义一份拦下(self):
        found = self.violations({"feature/a/src/main/Page.kt": "private val FAB_CLEARANCE = 88.dp\n"})
        self.assertTrue(any("FAB_CLEARANCE" in v for v in found))

    def test_导入别名的FAB在槽位外拦下(self):
        page = ("import androidx.compose.material3.FloatingActionButton as Fab\n\n"
                "fun Page() {\n    Box { Fab(onClick = {}) {} }\n}\n")
        found = self.violations({"feature/a/src/main/Page.kt": page})
        self.assertEqual(1, len(found))
        self.assertIn("槽位", found[0])

    def test_类型别名的FAB在槽位外拦下(self):
        alias = "typealias Fab = androidx.compose.material3.FloatingActionButton\n"
        page = "fun Page() {\n    Box { Fab(onClick = {}) {} }\n}\n"
        found = self.violations({"feature/a/src/main/Aliases.kt": alias, "feature/a/src/main/Page.kt": page})
        self.assertEqual(1, len(found))

    def test_拆成两行的定位修饰符拦下(self):
        page = "fun Page() {\n    Box { SelectionFabMenu(expanded = e, modifier = Modifier.\n        align(Alignment.BottomEnd)) }\n}\n"
        self.assertEqual(1, len(self.violations({"feature/a/src/main/Page.kt": page})))

    def test_字符串里的别名名字不算FAB(self):
        page = ("import androidx.compose.material3.FloatingActionButton as Fab\n\n"
                "fun Page() {\n    Text(\"Fab(onClick = {})\")\n}\n")
        self.assertEqual([], self.violations({"feature/a/src/main/Page.kt": page}))


    def test_Expressive扩展FAB随滚动收起也拦下(self):
        for fab in ("SmallExtendedFloatingActionButton", "MediumExtendedFloatingActionButton", "LargeExtendedFloatingActionButton"):
            page = f"fun Page() {{\n    Scaffold(floatingActionButton = {{ {fab}(onClick = {{}}, expanded = e) {{}} }}) {{}}\n}}\n"
            with self.subTest(fab):
                found = self.violations({"feature/a/src/main/Page.kt": page})
                self.assertEqual(1, len(found))
                self.assertIn("expanded", found[0])

    def test_槽位外的Expressive变体拦下(self):
        page = "fun Page() {\n    Box { ToggleFloatingActionButton(checked = c, onCheckedChange = {}) {} }\n}\n"
        found = self.violations({"feature/a/src/main/Page.kt": page})
        self.assertEqual(1, len(found))
        self.assertIn("槽位", found[0])

    def test_槽位里的lambda偏移与安全区内边距拦下(self):
        for modifier in ("offset { IntOffset(0, -lift) }", "safeDrawingPadding()", "absoluteOffset(y = 4.dp)"):
            page = f"fun Page() {{\n    Scaffold(floatingActionButton = {{ MyFab(modifier = Modifier.{modifier}) }}) {{}}\n}}\n"
            with self.subTest(modifier):
                self.assertEqual(1, len(self.violations({"feature/a/src/main/Page.kt": page})))

    def test_小写开头的FAB留白也算一份(self):
        found = self.violations({"feature/a/src/main/Page.kt": "private val fabClearance: Dp = 88.dp\n"})
        self.assertTrue(any("fabClearance" in v for v in found))

    def test_带修饰符的函数不借上一个函数的白名单(self):
        fab = FAB_FILE + "private inline fun OtherFab() {\n    FloatingActionButton(onClick = {}) {}\n}\n"
        found = self.violations({"core/ui/src/main/Fab.kt": fab})
        self.assertEqual(1, len(found))
        self.assertIn("OtherFab", found[0])


if __name__ == "__main__":
    unittest.main()
