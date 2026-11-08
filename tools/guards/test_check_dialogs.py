"""对话框门禁的自测：每条规矩一个违规样例与一个放行样例（tools/ci/check.sh lint 运行）。"""

from __future__ import annotations

from pathlib import Path
import tempfile
import unittest

from check_dialogs import find_violations


class CheckDialogsTest(unittest.TestCase):

    def violations(self, files: dict[str, str], allowed: dict | None = None) -> list[str]:
        root = Path(tempfile.mkdtemp())
        for rel, text in files.items():
            path = root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        return find_violations(root, allowed or {})

    def test_page_builds_its_own_confirm_dialog_is_rejected(self):
        found = self.violations({"feature/a/src/main/Page.kt": "@Composable\nfun Page() {\n    AlertDialog(onDismissRequest = {}, confirmButton = {})\n}\n"})
        self.assertEqual(1, len(found))
        self.assertIn("Page.kt:3", found[0])
        self.assertIn("ConfirmDialog", found[0])

    def test_qualified_split_basic_and_aliased_dialogs_are_rejected(self):
        qualified = "@Composable\nfun Page() { androidx.compose.material3.AlertDialog(onDismissRequest = {}, confirmButton = {}) }\n"
        split = "@Composable\nfun Page() {\n    AlertDialog\n        (onDismissRequest = {}, confirmButton = {})\n}\n"
        basic = "@Composable\nfun Page() { BasicAlertDialog(onDismissRequest = {}) {} }\n"
        aliased = "import androidx.compose.material3.AlertDialog as Ask\n\n@Composable\nfun Page() { Ask(onDismissRequest = {}, confirmButton = {}) }\n"
        for text in (qualified, split, basic, aliased):
            self.assertEqual(1, len(self.violations({"feature/a/src/main/Page.kt": text})), text)

    def test_confirm_dialog_home_and_comments_pass(self):
        page = "// AlertDialog(\nval url = \"https://bgm.tv/a\" // AlertDialog(\n@Composable\nfun Page() { ConfirmDialog(1L, \"删除\", onConfirm = {}, onDismiss = {}, text = \"x\") }\n"
        home = "@Composable\nfun ConfirmDialog() { AlertDialog(onDismissRequest = {}, confirmButton = {}) }\n"
        self.assertEqual([], self.violations({"feature/a/src/main/Page.kt": page, "core/ui/src/main/ConfirmDialog.kt": home}))

    def test_字符串与类型别名(self):
        text_only = 'val example = "AlertDialog(onDismissRequest = {}, confirmButton = {})"\n'
        self.assertEqual([], self.violations({"feature/a/src/main/Page.kt": text_only}))
        aliased = "typealias Dialog = AlertDialog\n@Composable\nfun Page() { Dialog(onDismissRequest = {}, confirmButton = {}) }\n"
        self.assertEqual(1, len(self.violations({"feature/a/src/main/Page.kt": aliased})))

    def test_allowed_input_dialog_passes_and_dead_entry_is_reported(self):
        page = "@Composable\nprivate fun RenameDialog() { AlertDialog(onDismissRequest = {}, confirmButton = {}) }\n"
        self.assertEqual([], self.violations({"feature/a/src/main/Page.kt": page}, {("Page.kt", "RenameDialog"): "输入框"}))
        found = self.violations({"feature/a/src/main/Page.kt": "@Composable\nfun Page() {}\n"}, {("Page.kt", "Old"): "x"})
        self.assertEqual(1, len(found))
        self.assertIn("死配置", found[0])


    def test_modified_function_does_not_borrow_previous_allowlist_entry(self):
        page = ("@Composable\nprivate fun RenameDialog() { AlertDialog(onDismissRequest = {}, confirmButton = {}) }\n"
                "@Composable\nprivate inline fun DeleteDialog() { AlertDialog(onDismissRequest = {}, confirmButton = {}) }\n")
        found = self.violations({"feature/a/src/main/Page.kt": page}, {("Page.kt", "RenameDialog"): "输入框"})
        self.assertEqual(1, len(found))
        self.assertIn("DeleteDialog", found[0])


if __name__ == "__main__":
    unittest.main()
