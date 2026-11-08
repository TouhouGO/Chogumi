"""文字输入框门禁的自测：每条规矩一个样例（tools/ci/check.sh lint 运行）。"""

from pathlib import Path
import tempfile
import unittest

from check_text_fields import find_violations

ALLOWED = {("Chip.kt", "TagAddChip"): "嵌在 chip 里"}

CHIP = """
@Composable
fun TagAddChip() {
    BasicTextField(state = s)
}
"""


class CheckTextFieldsTest(unittest.TestCase):

    def violations(self, files: dict[str, str]) -> list[str]:
        root = Path(tempfile.mkdtemp())
        for rel, text in {"feature/a/src/main/Chip.kt": CHIP, **files}.items():
            path = root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        return find_violations(root, ALLOWED)

    def test_official_outlined_field_in_a_page_is_rejected(self):
        found = self.violations({"feature/a/src/main/Page.kt": "@Composable\nfun Page() {\n    OutlinedTextField(value = v, onValueChange = {})\n}\n"})
        self.assertEqual(1, len(found))
        self.assertIn("Page.kt:3", found[0])

    def test_basic_field_in_a_page_is_rejected(self):
        found = self.violations({"feature/a/src/main/Page.kt": "@Composable\nfun Page() {\n    BasicTextField(value = v, onValueChange = {})\n}\n"})
        self.assertEqual(1, len(found))

    def test_secure_field_and_aliased_import_are_rejected(self):
        secure = "@Composable\nfun Page() {\n    SecureTextField(state = s)\n}\n"
        aliased = "import androidx.compose.material3.OutlinedTextField as Field\n\n@Composable\nfun Page() {\n    Field(value = v, onValueChange = {})\n}\n"
        for text in (secure, aliased):
            self.assertEqual(1, len(self.violations({"feature/a/src/main/Page.kt": text})), text)

    def test_form_text_field_and_comments_pass(self):
        self.assertEqual([], self.violations({"feature/a/src/main/Page.kt": "// OutlinedTextField(\n@Composable\nfun Page() { FormTextField(value = v, onValueChange = {}) }\n"}))

    def test_homes_may_use_official_fields(self):
        self.assertEqual([], self.violations({"core/ui/src/main/FormTextField.kt": "@Composable\nfun FormTextField() { OutlinedTextField(value = v, onValueChange = {}) }\n"}))

    def test_field_in_dialog_without_in_dialog_is_rejected(self):
        page = "@Composable\nfun Page() {\n    AlertDialog(\n        text = {\n            FormTextField(value = v, onValueChange = {})\n        },\n    )\n}\n"
        found = self.violations({"feature/a/src/main/Page.kt": page})
        self.assertEqual(1, len(found))
        self.assertIn("inDialog", found[0])

    def test_field_in_dialog_with_in_dialog_passes(self):
        page = "@Composable\nfun Page() {\n    AlertDialog(\n        text = {\n            FormTextField(value = v, onValueChange = { f(it) }, label = \"a)\", inDialog = true)\n        },\n    )\n}\n"
        self.assertEqual([], self.violations({"feature/a/src/main/Page.kt": page}))

    def test_field_outside_dialog_needs_nothing(self):
        page = "@Composable\nfun Page() {\n    FormTextField(value = v, onValueChange = {})\n    AlertDialog(text = { Text(\"x\") })\n}\n"
        self.assertEqual([], self.violations({"feature/a/src/main/Page.kt": page}))

    def test_dead_allowlist_entry_is_reported(self):
        found = self.violations({"feature/a/src/main/Chip.kt": "@Composable\nfun TagAddChip() {}\n"})
        self.assertEqual(1, len(found))
        self.assertIn("死配置", found[0])


    def test_search_bar_input_field_is_rejected(self):
        page = "@Composable\nfun Page() {\n    SearchBarDefaults\n        .InputField(query = q, onQueryChange = {})\n}\n"
        found = self.violations({"feature/a/src/main/Page.kt": page})
        self.assertEqual(1, len(found))
        self.assertIn("Page.kt:3", found[0])

    def test_field_in_basic_dialog_content_and_secret_field_need_in_dialog(self):
        basic = "@Composable\nfun Page() {\n    BasicAlertDialog(onDismissRequest = {}) {\n        FormTextField(value = v, onValueChange = {})\n    }\n}\n"
        secret = "@Composable\nfun Page() {\n    AlertDialog(text = { SecretTextField(value = v, onValueChange = {}) })\n}\n"
        for page in (basic, secret):
            with self.subTest(page):
                found = self.violations({"feature/a/src/main/Page.kt": page})
                self.assertEqual(1, len(found))
                self.assertIn("inDialog", found[0])
        passing = "@Composable\nfun Page() {\n    BasicAlertDialog(onDismissRequest = {}) {\n        FormTextField(value = v, inDialog = true)\n    }\n}\n"
        self.assertEqual([], self.violations({"feature/a/src/main/Page.kt": passing}))

    def test_modified_function_does_not_borrow_previous_allowlist_entry(self):
        chip = CHIP + "\n@Composable\nprivate inline fun Other() {\n    BasicTextField(state = s)\n}\n"
        found = self.violations({"feature/a/src/main/Chip.kt": chip})
        self.assertEqual(1, len(found))
        self.assertIn("Other", found[0])


if __name__ == "__main__":
    unittest.main()
