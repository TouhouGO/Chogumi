"""下拉菜单门禁的自测：每条规矩一个样例（tools/ci/check.sh lint 运行）。"""

from __future__ import annotations

from pathlib import Path
import tempfile
import unittest

from check_menus import find_violations


class CheckMenusTest(unittest.TestCase):

    def violations(self, files: dict[str, str]) -> list[str]:
        root = Path(tempfile.mkdtemp())
        for rel, text in files.items():
            path = root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        return find_violations(root)

    def test_official_menu_in_a_page_is_rejected(self):
        found = self.violations({"feature/a/src/main/Page.kt": "@Composable\nfun Page() {\n    DropdownMenu(expanded = e, onDismissRequest = {}) {}\n}\n"})
        self.assertEqual(1, len(found))
        self.assertIn("Page.kt:3", found[0])

    def test_qualified_exposed_and_aliased_menus_are_rejected(self):
        qualified = "@Composable\nfun Page() { androidx.compose.material3.DropdownMenu(expanded = e, onDismissRequest = {}) {} }\n"
        exposed = "@Composable\nfun Page() { ExposedDropdownMenu(expanded = e, onDismissRequest = {}) {} }\n"
        aliased = "import androidx.compose.material3.DropdownMenu as Menu\n\n@Composable\nfun Page() { Menu(expanded = e, onDismissRequest = {}) {} }\n"
        for text in (qualified, exposed, aliased):
            self.assertEqual(1, len(self.violations({"feature/a/src/main/Page.kt": text})), text)

    def test_app_menu_and_comments_pass(self):
        self.assertEqual([], self.violations({"feature/a/src/main/Page.kt": "// DropdownMenu(\n@Composable\nfun Page() { AppDropdownMenu(expanded = e, onDismissRequest = {}) {} }\n"}))

    def test_home_may_use_official_menu(self):
        self.assertEqual([], self.violations({"core/ui/src/main/AppDropdownMenu.kt": "@Composable\nfun AppDropdownMenu() { DropdownMenu(expanded = e, onDismissRequest = {}) {} }\n"}))

    def test_official_menu_item_outside_its_homes_is_rejected(self):
        plain = "@Composable\nfun Page() {\n    DropdownMenuItem(text = { Text(\"a\") }, onClick = {})\n}\n"
        qualified = "@Composable\nfun Page() { androidx.compose.material3.DropdownMenuItem(text = {}, onClick = {}) }\n"
        split = "@Composable\nfun Page() {\n    DropdownMenuItem\n        (text = {}, onClick = {})\n}\n"
        aliased = "import androidx.compose.material3.DropdownMenuItem as Item\n\n@Composable\nfun Page() { Item(text = {}, onClick = {}) }\n"
        for text in (plain, qualified, split, aliased):
            found = self.violations({"feature/a/src/main/Page.kt": text})
            self.assertEqual(1, len(found), text)
            self.assertIn("AppMenuItem", found[0])
        self.assertIn("Page.kt:3", self.violations({"feature/a/src/main/Page.kt": plain})[0])

    def test_menu_item_homes_and_app_menu_item_pass(self):
        home = "@Composable\nfun AppMenuItem() { DropdownMenuItem(text = {}, onClick = {}) }\n"
        selectable = "@Composable\nprivate fun SelectableMenuItem() { DropdownMenuItem(text = {}, onClick = {}) }\n"
        page = "// DropdownMenuItem(\nval url = \"https://bgm.tv/a\" // DropdownMenuItem(\n@Composable\nfun Page() { AppMenuItem(\"a\", onDismiss = {}) {} }\n"
        self.assertEqual([], self.violations({
            "core/ui/src/main/AppDropdownMenu.kt": home,
            "core/ui/src/main/SelectableDropdownMenu.kt": selectable,
            "feature/a/src/main/Page.kt": page,
        }))

    def test_page_draws_its_own_more_button_is_rejected(self):
        page = "@Composable\nfun Page() { IconButton(onClick = {}) { Icon(painterResource(R.drawable.more_vert_24), null) } }\n"
        found = self.violations({"feature/a/src/main/Page.kt": page, "core/ui/src/main/PageMoreMenu.kt": page})
        self.assertEqual(1, len(found))
        self.assertIn("PageMoreMenu", found[0])
        self.assertIn("feature/a/src/main/Page.kt:2", found[0])


    def test_other_more_vert_variants_are_rejected(self):
        for icon in ("R.drawable.more_vert_filled_24", "R.drawable.more_vert_20", "R\n        .drawable.more_vert_24"):
            page = f"@Composable\nfun Page() {{ Icon(painterResource({icon}), null) }}\n"
            with self.subTest(icon):
                self.assertEqual(1, len(self.violations({"feature/a/src/main/Page.kt": page})))


if __name__ == "__main__":
    unittest.main()
