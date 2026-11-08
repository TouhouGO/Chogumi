"""图片加载结果门禁的自测：违规样例要拦住，合规写法放行（tools/ci/check.sh lint 运行）。"""

from pathlib import Path
import tempfile
import unittest

from check_image_result_state import find_violations

REMEMBERED_SIZE = """
@Composable
fun Picture(url: String) {
    var pixels by remember(url) { mutableStateOf<Pair<Float, Float>?>(null) }
    AsyncImage(
        model = url,
        contentDescription = null,
        onSuccess = { pixels = it.result.image.width.toFloat() to it.result.image.height.toFloat() },
        modifier = Modifier.size(pixels),
    )
}
"""

SAVED_SIZE = """
@Composable
fun Picture(url: String) {
    var pixels by rememberSaveable(url, stateSaver = sizeSaver(url)) { mutableStateOf<Pair<Float, Float>?>(null) }
    AsyncImage(
        model = url,
        contentDescription = null,
        onSuccess = { pixels = it.result.image.width.toFloat() to it.result.image.height.toFloat() },
        modifier = Modifier.size(pixels),
    )
}
"""

FAILURE_IN_FIXED_BOX = """
@Composable
fun Avatar(url: String) {
    var failed by remember(url) { mutableStateOf(false) }
    if (failed) Icon() else AsyncImage(model = url, contentDescription = null, onError = { failed = true }, modifier = Modifier.size(40.dp))
}
"""

SUBCOMPOSE = """
@Composable
fun Picture(url: String) {
    SubcomposeAsyncImage(model = url, contentDescription = null)
}
"""


class CheckImageResultStateTest(unittest.TestCase):

    def violations(self, text: str) -> list[str]:
        root = Path(tempfile.mkdtemp())
        path = root / "core/reader/src/main/Picture.kt"
        path.parent.mkdir(parents=True)
        path.write_text(text, encoding="utf-8")
        return find_violations(root)

    def test_remembered_load_result_is_rejected(self):
        found = self.violations(REMEMBERED_SIZE)
        self.assertEqual(1, len(found))
        self.assertIn("Picture.kt:8", found[0])
        self.assertIn("pixels", found[0])

    def test_saved_load_result_passes(self):
        self.assertEqual([], self.violations(SAVED_SIZE))

    def test_remember_object_value_is_rejected(self):
        source = """
fun Picture(url: String) {
    val pixels = remember(url) { mutableStateOf<Pair<Float, Float>?>(null) }
    AsyncImage(model = url, onSuccess = { pixels.value = 1f to 2f })
}
"""
        found = self.violations(source)
        self.assertEqual(1, len(found), found)
        self.assertIn("pixels", found[0])

    def test_fully_qualified_remember_object_value_is_rejected(self):
        source = """
fun Picture(url: String) {
    val pixels = androidx.compose.runtime.remember { mutableStateOf(1f) }
    AsyncImage(model = url, onSuccess = { pixels.value = 2f })
}
"""
        found = self.violations(source)
        self.assertEqual(1, len(found), found)
        self.assertIn("pixels", found[0])

    def test_remembered_non_state_object_value_passes(self):
        source = """
fun Picture(url: String) {
    val renderer = remember { ImageRenderer() }
    AsyncImage(model = url, onSuccess = { renderer.value = 1f })
}
"""
        self.assertEqual([], self.violations(source))

    def test_带类型或基本类型状态的remember也拦下(self):
        for source in (
            "fun P(url: String) {\n    var pixels: Size? by remember(url) { mutableStateOf(null) }\n"
            "    AsyncImage(model = url, onSuccess = { pixels = it.size })\n}\n",
            "fun P(url: String) {\n    val width = remember(url) { mutableFloatStateOf(0f) }\n"
            "    AsyncImage(model = url, onSuccess = { width.floatValue = 1f })\n}\n",
            "fun P(url: String) {\n    val width: MutableState<Float> = remember(url) { mutableStateOf(0f) }\n"
            "    AsyncImage(model = url, onSuccess = { width.value = 1f })\n}\n",
        ):
            with self.subTest(source=source):
                found = self.violations(source)
                self.assertEqual(1, len(found), found)
                self.assertIn("remember", found[0])

    def test_请求监听里的加载结果同样要存档(self):
        listener = (
            "fun P(url: String) {\n    var width by remember(url) { mutableStateOf(0f) }\n"
            "    val request = ImageRequest.Builder(context).data(url).listener(onSuccess = { _, result -> width = 1f }).build()\n"
            "    AsyncImage(model = request, contentDescription = null)\n}\n"
        )
        found = self.violations(listener)
        self.assertEqual(1, len(found), found)
        self.assertIn("width", found[0])
        self.assertEqual([], self.violations(listener.replace("by remember(url)", "by rememberSaveable(url)")))

    def test_带类型的存档状态放行(self):
        source = (
            "fun P(url: String) {\n    var pixels: Size? by rememberSaveable(url, stateSaver = saver) { mutableStateOf(null) }\n"
            "    AsyncImage(model = url, onSuccess = { pixels = it.size })\n}\n"
        )
        self.assertEqual([], self.violations(source))

    def test_failure_flag_of_fixed_box_passes(self):
        self.assertEqual([], self.violations(FAILURE_IN_FIXED_BOX))

    def test_load_following_image_is_rejected(self):
        found = self.violations(SUBCOMPOSE)
        self.assertEqual(1, len(found))
        self.assertIn("SubcomposeAsyncImage", found[0])

    def test_字符串只是提到加载组件不算(self):
        text_only = 'val example = "SubcomposeAsyncImage(model = url)"\n'
        self.assertEqual([], self.violations(text_only))

    def test_aliased_image_and_fully_qualified_remember_are_rejected(self):
        for source in (
            "import coil3.compose.AsyncImage as PictureImage\n" + REMEMBERED_SIZE.replace("AsyncImage(", "PictureImage("),
            REMEMBERED_SIZE.replace("by remember(", "by androidx.compose.runtime.remember("),
            "import androidx.compose.runtime.remember as retain\n" + REMEMBERED_SIZE.replace("by remember(", "by retain("),
            "import coil3.compose.SubcomposeAsyncImage as PictureImage\n" + SUBCOMPOSE.replace("SubcomposeAsyncImage(", "PictureImage("),
        ):
            with self.subTest(source=source):
                self.assertEqual(1, len(self.violations(source)))


if __name__ == "__main__":
    unittest.main()
