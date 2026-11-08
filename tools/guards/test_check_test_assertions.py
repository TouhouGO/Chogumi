"""测试断言门禁的自测：每条规矩各给一个样例，确认门禁拦得住、也不误拦（tools/ci/check.sh lint 运行）。"""

from pathlib import Path
import tempfile
import unittest

from check_test_assertions import find_violations

TEST_DIR = "feature/a/src/androidTest/kotlin"
FIXTURES = "core/ui/src/testFixtures/kotlin/Fixtures.kt"
BASELINE_DIR = "baselineprofile/src/main/kotlin/io/github/touhougo/chogumi/baselineprofile"
ORDINARY_MAIN = "app/src/main/kotlin/Production.kt"
SCREENSHOT_FAILURE_SELF_TEST = (
    "core/ui/src/test/kotlin/io/github/touhougo/chogumi/core/ui/testing/ScreenshotFilesTest.kt"
)
REPO = Path(__file__).resolve().parents[2]
REAL_SKELETON_FIXTURE = REPO / "core/ui/src/testFixtures/kotlin/io/github/touhougo/chogumi/core/ui/testing/SkeletonAnchor.kt"


def test_class(*methods: str, annotations: str = "") -> str:
    return f"{annotations}\nclass ATest {{\n" + "\n".join(methods) + "\n}\n"


def test_fun(name: str, body: str, annotations: str = "@Test") -> str:
    return f"    {annotations}\n    fun `{name}`() {{\n        {body}\n    }}\n"


class CheckTestAssertionsTest(unittest.TestCase):

    def violations(self, files: dict[str, str]) -> list[str]:
        root = Path(tempfile.mkdtemp())
        for rel, text in files.items():
            path = root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        return find_violations(root)

    def test_直接断言放行(self):
        source = test_class(
            test_fun("相等", "assertEquals(1, x)"),
            test_fun("节点在", 'composeRule.onNodeWithText("a").assertExists()'),
            test_fun("抛错", "assertFailsWith<IllegalStateException> { parse() }"),
        )
        self.assertEqual([], self.violations({f"{TEST_DIR}/ATest.kt": source}))

    def test_没有断言拦下(self):
        found = self.violations({f"{TEST_DIR}/ATest.kt": test_class(test_fun("只跑一遍", "render()"))})
        self.assertEqual(1, len(found))
        self.assertIn("只跑一遍 没有断言", found[0])

    def test_别名与全限定Test注解仍要求断言(self):
        for source in (
            test_class(test_fun("empty", "render()", annotations="@org.junit.Test")),
            "import org.junit.Test as Case\n" + test_class(test_fun("empty", "render()", annotations="@Case")),
        ):
            with self.subTest(source=source):
                self.assertEqual(1, len(self.violations({f"{TEST_DIR}/ATest.kt": source})))

    def test_注释与字符串里的断言不算(self):
        body = '// assertTrue(x)\n        println("assertEquals(1, 2)")'
        found = self.violations({f"{TEST_DIR}/ATest.kt": test_class(test_fun("假断言", body))})
        self.assertEqual(1, len(found))

    def test_经辅助函数断言放行_隔几层与测试夹具里的都算(self):
        source = test_class(
            test_fun("同文件", "check()"),
            test_fun("隔两层", "outer()"),
            test_fun("夹具里", "awaitUntil { done }"),
            "    private fun check() { assertTrue(ok) }\n",
            "    private fun outer() = inner(1)\n",
            "    private fun inner(x: Int) {\n        check()\n    }\n",
        )
        fixtures = 'fun awaitUntil(block: () -> Boolean) {\n    if (!block()) fail("超时")\n}\n'
        self.assertEqual([], self.violations({f"{TEST_DIR}/ATest.kt": source, FIXTURES: fixtures}))

    def test_表达式体带尾随lambda时花括号前的断言辅助算断言(self):
        source = test_class(
            "    @Test\n    fun `尾随 lambda`() = assertShown { render() }\n",
            "    @Test\n    fun `只渲染`() = runOnce { render() }\n",
            "    private fun assertShown(content: () -> Unit) {\n        content()\n        assertTrue(shown)\n    }\n",
            "    private fun runOnce(content: () -> Unit) {\n        content()\n    }\n",
        )
        found = self.violations({f"{TEST_DIR}/ATest.kt": source})
        self.assertEqual(1, len(found))
        self.assertIn("只渲染", found[0])

    def test_别的文件里同名的私有辅助函数不算(self):
        other = test_class(
            test_fun("那边", "shoot()"),
            "    private fun shoot() { assertEquals(1, frames) }\n",
        ).replace("ATest", "OtherTest")
        mine = test_class(
            test_fun("这边", "shoot()"),
            "    private fun shoot() { save(frame) }\n",
        )
        found = self.violations({f"{TEST_DIR}/OtherTest.kt": other, f"{TEST_DIR}/ATest.kt": mine})
        self.assertEqual(1, len(found))
        self.assertIn("这边", found[0])

    def test_expected_算断言(self):
        source = test_class(test_fun("抛错", "parse()", annotations="@Test(expected = IllegalStateException::class)"))
        self.assertEqual([], self.violations({f"{TEST_DIR}/ATest.kt": source}))

    def test_截图标在函数或类上放行(self):
        on_fun = test_class(test_fun("出图", "saveScreenshot(dir, name, bitmap)", annotations="@Test\n    @VisualCapture"))
        on_class = test_class(test_fun("录屏", "shoot()"), annotations="@VisualCapture\n@RunWith(AndroidJUnit4::class)")
        self.assertEqual([], self.violations({f"{TEST_DIR}/ATest.kt": on_fun, f"{TEST_DIR}/BTest.kt": on_class}))

    def test_截图里有断言拦下(self):
        source = test_class(test_fun("出图", "assertEquals(1, sizes.size)"), annotations="@VisualCapture")
        found = self.violations({f"{TEST_DIR}/ATest.kt": source})
        self.assertEqual(1, len(found))
        self.assertIn("@VisualCapture 却有断言", found[0])

    def test_截图夹具里的前置检查不算截图测试的断言(self):
        capture = test_class(test_fun("出图", "saveScreenshot(bitmap)"), annotations="@Test\n    @VisualCapture")
        fixture = "fun saveScreenshot(bitmap: Bitmap) { check(bitmap.width > 0) }\n"
        self.assertEqual([], self.violations({f"{TEST_DIR}/ATest.kt": capture, FIXTURES: fixture}))

    def test_自己压截图拦下_经saveScreenshot放行(self):
        own = test_fun("出图", "bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)", annotations="@Test\n    @VisualCapture")
        shared = test_fun("出图", "saveScreenshot(\"tiles\", name, bitmap)", annotations="@Test\n    @VisualCapture")
        found = self.violations({f"{TEST_DIR}/ATest.kt": test_class(own), f"{TEST_DIR}/BTest.kt": test_class(shared)})
        self.assertEqual(1, len(found))
        self.assertIn("ATest.kt", found[0])
        self.assertIn("saveScreenshot", found[0])

    def test_截图失败自测的精确路径放行编码与saveScreenshot但保留断言检查(self):
        body = (
            'val failure = assertFailsWith<IllegalStateException> { '
            'saveScreenshot("review", "compress-failure", bitmap) }; '
            'val format = Bitmap.CompressFormat.PNG'
        )
        source = test_class(test_fun("PNG 压缩失败", body))
        self.assertEqual([], self.violations({SCREENSHOT_FAILURE_SELF_TEST: source}))

        no_assertion = test_class(test_fun(
            "只压缩",
            'saveScreenshot("review", "compress-failure", bitmap); val format = Bitmap.CompressFormat.PNG',
        ))
        found = self.violations({SCREENSHOT_FAILURE_SELF_TEST: no_assertion})
        self.assertEqual(1, len(found), found)
        self.assertIn("没有断言", found[0])

    def test_同名截图失败自测放到其他路径仍拦下编码与saveScreenshot(self):
        body = (
            'assertEquals(1, 1); '
            'saveScreenshot("review", "compress-failure", bitmap); '
            'val format = Bitmap.CompressFormat.PNG'
        )
        source = test_class(test_fun("PNG 压缩失败", body))
        found = self.violations({
            "feature/a/src/test/kotlin/io/github/touhougo/chogumi/core/ui/testing/ScreenshotFilesTest.kt": source,
        })
        self.assertEqual(2, len(found), found)
        self.assertTrue(any("自己把截图压成文件" in message for message in found), found)
        self.assertTrue(any("普通 @Test 调用了 saveScreenshot" in message for message in found), found)

    def test_普通测试有断言也不能顺带落截图(self):
        body = 'assertEquals(48, bounds.height); saveScreenshot("tiles", name, bitmap)'
        found = self.violations({f"{TEST_DIR}/ATest.kt": test_class(test_fun("几何与截图", body))})
        self.assertEqual(1, len(found), found)
        self.assertIn("普通 @Test 调用了 saveScreenshot", found[0])

    def test_经辅助函数落截图也拦下(self):
        source = test_class(
            test_fun("几何与截图", "capture(bitmap)"),
            "    private fun capture(bitmap: Bitmap) { saveScreenshot(\"tiles\", name, bitmap) }\n",
        )
        found = self.violations({f"{TEST_DIR}/ATest.kt": source})
        self.assertEqual(1, len(found), found)
        self.assertIn("普通 @Test 调用了 saveScreenshot", found[0])

    def test_saveScreenshot函数引用实际调用时拦下(self):
        body = 'assertTrue(true); val shot = ::saveScreenshot; shot("tiles", name, bitmap)'
        found = self.violations({f"{TEST_DIR}/ATest.kt": test_class(test_fun("函数引用截图", body))})
        self.assertEqual(1, len(found), found)

    def test_saveScreenshot函数引用只传递不调用时放行(self):
        body = 'assertTrue(true); val shots = listOf(::saveScreenshot)'
        self.assertEqual([], self.violations({f"{TEST_DIR}/ATest.kt": test_class(test_fun("只登记截图函数", body))}))

    def test_直接调用函数引用时拦下(self):
        for body in (
            'assertTrue(true); (::saveScreenshot)("tiles", name, bitmap)',
            'assertTrue(true); ::saveScreenshot.invoke("tiles", name, bitmap)',
        ):
            with self.subTest(body=body):
                found = self.violations({f"{TEST_DIR}/ATest.kt": test_class(test_fun("直接引用截图", body))})
                self.assertEqual(1, len(found), found)

    def test_saveScreenshot函数引用链与invoke仍拦下(self):
        for body in (
            'assertTrue(true); val first = ::saveScreenshot; val second = first; second("tiles", name, bitmap)',
            'assertTrue(true); val shot = ::saveScreenshot; shot.invoke("tiles", name, bitmap)',
        ):
            with self.subTest(body=body):
                found = self.violations({f"{TEST_DIR}/ATest.kt": test_class(test_fun("函数引用截图", body))})
                self.assertEqual(1, len(found), found)

    def test_截图函数别名指向别处后调用时放行(self):
        body = """
assertTrue(true)
var shot = ::saveScreenshot
shot = ::noop
shot("tiles", name, bitmap)
"""
        source = test_class(test_fun("函数引用指向别处", body), "    fun noop(a: String, b: String, c: Bitmap) {}\n")
        self.assertEqual([], self.violations({f"{TEST_DIR}/ATest.kt": source}))

    def test_单元测试也查(self):
        found = self.violations({"core/model/src/test/kotlin/ATest.kt": test_class(test_fun("打印清单", "println(list)"))})
        self.assertEqual(1, len(found))

    def test_baselineprofile主源码没有断言拦下(self):
        found = self.violations({f"{BASELINE_DIR}/StartupJourney.kt": test_class(test_fun("只跑一遍", "generateProfile()"))})
        self.assertEqual(1, len(found))
        self.assertIn("只跑一遍 没有断言", found[0])

    def test_baselineprofile经标准Kotlin检查辅助链放行(self):
        source = test_class(
            test_fun("生成", "requireSignedIn()"),
            test_fun("requireSignedIn", 'checkNotNull(targetString("title")); check(device.waitForIdle())', annotations=""),
        )
        self.assertEqual([], self.violations({f"{BASELINE_DIR}/StartupJourney.kt": source}))

    def test_标准Kotlin检查函数直接使用时也放行(self):
        bodies = (
            "check(ready)",
            "checkNotNull(value)",
            "require(ready)",
            "requireNotNull(value)",
            "return check(ready)",
            "kotlin.check(ready)",
            "kotlin.requireNotNull(value)",
        )
        for body in bodies:
            with self.subTest(body=body):
                self.assertEqual([], self.violations({f"{TEST_DIR}/ATest.kt": test_class(test_fun("检查契约", body))}))

    def test_局部callable值遮蔽check时不算标准检查(self):
        body = "val check = { value: Boolean -> println(value) }; check(ready)"
        found = self.violations({f"{TEST_DIR}/ATest.kt": test_class(test_fun("假检查", body))})
        self.assertEqual(1, len(found))
        self.assertIn("假检查 没有断言", found[0])

    def test_类属性callable遮蔽check时不算标准检查(self):
        source = """
class ATest {
    val check = { value: Boolean -> println(value) }

    @Test
    fun 假检查() {
        check(ready)
    }
}
"""
        found = self.violations({f"{TEST_DIR}/ATest.kt": source})
        self.assertEqual(1, len(found))
        self.assertIn("假检查 没有断言", found[0])

    def test_helper参数callable遮蔽check时不算标准检查(self):
        source = """
class ATest {
    @Test
    fun 假检查() {
        withCheck { value -> println(value) }
    }

    private fun withCheck(check: (Boolean) -> Unit) {
        check(ready)
    }
}
"""
        found = self.violations({f"{TEST_DIR}/ATest.kt": source})
        self.assertEqual(1, len(found))
        self.assertIn("假检查 没有断言", found[0])

    def test_callable遮蔽时显式kotlin_check仍放行(self):
        body = "val check = { value: Boolean -> println(value) }; kotlin . check(ready)"
        self.assertEqual([], self.violations({f"{TEST_DIR}/ATest.kt": test_class(test_fun("检查契约", body))}))

    def test_fixture里无关嵌套check不会遮蔽标准检查(self):
        fixture = """
fun compareEdges() {
    fun check(value: Boolean) { println(value) }
    check(true)
}
"""
        source = test_class(test_fun("检查契约", "check(ready)"))
        self.assertEqual([], self.violations({"feature/a/src/test/kotlin/ATest.kt": source, FIXTURES: fixture}))

    def test_真实fixture里的嵌套check不会遮蔽标准检查(self):
        source = test_class(test_fun("检查契约", "check(ready)"))
        fixture = REAL_SKELETON_FIXTURE.read_text(encoding="utf-8")
        self.assertEqual([], self.violations({"feature/a/src/test/kotlin/ATest.kt": source, FIXTURES: fixture}))

    def test_同文件无关函数里的嵌套check不会遮蔽标准检查(self):
        source = """
class ATest {
    @Test
    fun 检查契约() {
        check(ready)
    }

    fun unrelated() {
        fun check(value: Boolean) { println(value) }
        check(true)
    }
}
"""
        self.assertEqual([], self.violations({f"{TEST_DIR}/ATest.kt": source}))

    def test_外层helper调用自己的nested断言helper仍放行(self):
        source = """
class ATest {
    @Test
    fun 检查契约() {
        outer()
    }

    private fun outer() {
        fun check() { assertTrue(ready) }
        check()
    }
}
"""
        self.assertEqual([], self.violations({f"{TEST_DIR}/ATest.kt": source}))

    def test_本文件的同名可见helper优先于标准检查(self):
        for name, call in (("check", "check(ready)"), ("require", "require(ready)")):
            with self.subTest(name=name):
                source = test_class(
                    test_fun("没有检查", call),
                    test_fun(name, "render()", annotations=""),
                )
                found = self.violations({f"{TEST_DIR}/ATest.kt": source})
                self.assertEqual(1, len(found))
                self.assertIn("没有检查 没有断言", found[0])

    def test_普通测试源码集里的同名可见helper优先于标准检查(self):
        source = test_class(test_fun("没有检查", "check(ready)"))
        helper = "fun check(ready: Boolean) { println(ready) }\n"
        found = self.violations({
            "feature/a/src/test/kotlin/ATest.kt": source,
            "feature/a/src/test/kotlin/Helpers.kt": helper,
        })
        self.assertEqual(1, len(found))
        self.assertIn("没有检查 没有断言", found[0])

    def test_baseline主测试源码集里的同名可见helper优先于标准检查(self):
        source = test_class(test_fun("没有检查", "check(ready)"))
        helper = "fun check(ready: Boolean) { println(ready) }\n"
        found = self.violations({
            f"{BASELINE_DIR}/StartupJourney.kt": source,
            f"{BASELINE_DIR}/Helpers.kt": helper,
        })
        self.assertEqual(1, len(found))
        self.assertIn("没有检查 没有断言", found[0])

    def test_普通测试源码调用应用夹具的同名可见helper优先于标准检查(self):
        source = test_class(test_fun("没有检查", "check(ready)"))
        fixture = "fun check(ready: Boolean) { println(ready) }\n"
        found = self.violations({"feature/a/src/test/kotlin/ATest.kt": source, FIXTURES: fixture})
        self.assertEqual(1, len(found))
        self.assertIn("没有检查 没有断言", found[0])

    def test_baseline主测试源码调用应用夹具的同名可见helper优先于标准检查(self):
        source = test_class(test_fun("没有检查", "check(ready)"))
        fixture = "fun check(ready: Boolean) { println(ready) }\n"
        found = self.violations({f"{BASELINE_DIR}/StartupJourney.kt": source, FIXTURES: fixture})
        self.assertEqual(1, len(found))
        self.assertIn("没有检查 没有断言", found[0])

    def test_别的文件里私有同名helper不可见时裸check仍是标准检查(self):
        source = test_class(test_fun("检查契约", "check(ready)"))
        helper = "private fun check(ready: Boolean) { println(ready) }\n"
        self.assertEqual([], self.violations({
            "feature/a/src/test/kotlin/ATest.kt": source,
            "feature/a/src/test/kotlin/Helpers.kt": helper,
        }))

    def test_全限定标准检查允许包名点与函数名周围换行和空白(self):
        bodies = (
            "kotlin . check(ready)",
            "kotlin\n        .check(ready)",
            "kotlin\n        .\n        checkNotNull(\n            value\n        )",
            "kotlin . require (ready)",
            "return kotlin . check(ready)",
        )
        for body in bodies:
            with self.subTest(body=body):
                self.assertEqual([], self.violations({f"{TEST_DIR}/ATest.kt": test_class(test_fun("检查契约", body))}))

    def test_全限定标准检查不被同名helper遮蔽(self):
        source = test_class(
            test_fun("检查契约", "kotlin . check(ready)"),
            test_fun("check", "render()", annotations=""),
        )
        self.assertEqual([], self.violations({f"{TEST_DIR}/ATest.kt": source}))

    def test_字符串和成员访问里的kotlin链不算标准检查(self):
        bodies = (
            '"kotlin.check(ready)"',
            "fixture.kotlin.check(ready)",
            "fixture . kotlin . check(ready)",
            "fixture\n        .\n        kotlin\n        .\n        check(ready)",
            "fixture . check(ready)",
            "fixture\n        .\n        require(ready)",
        )
        for body in bodies:
            with self.subTest(body=body):
                found = self.violations({f"{TEST_DIR}/ATest.kt": test_class(test_fun("假检查", body))})
                self.assertEqual(1, len(found))
                self.assertIn("假检查 没有断言", found[0])

    def test_同名的空检查辅助函数仍不算断言(self):
        source = test_class(
            test_fun("没有检查", "check()"),
            test_fun("check", "render()", annotations=""),
        )
        found = self.violations({f"{TEST_DIR}/ATest.kt": source})
        self.assertEqual(1, len(found))
        self.assertIn("没有检查 没有断言", found[0])

    def test_普通主源码里的Test仍不参与门禁(self):
        self.assertEqual([], self.violations({ORDINARY_MAIN: test_class(test_fun("生产入口", "render()"))}))

    def test_导入别名的Test注解照样要求断言(self):
        source = "import org.junit.Test as Case\n" + test_class(test_fun("只跑一遍", "render()", annotations="@Case"))
        self.assertEqual(1, len(self.violations({f"{TEST_DIR}/ATest.kt": source})))

    def test_类型别名的Test注解照样要求断言(self):
        found = self.violations({
            f"{TEST_DIR}/Aliases.kt": "typealias Case = org.junit.Test\n",
            f"{TEST_DIR}/ATest.kt": test_class(test_fun("只跑一遍", "render()", annotations="@Case")),
        })
        self.assertEqual(1, len(found))

    def test_导入别名的断言函数算断言(self):
        source = "import org.junit.Assert.assertEquals as same\n" + test_class(test_fun("相等", "same(1, x)"))
        self.assertEqual([], self.violations({f"{TEST_DIR}/ATest.kt": source}))

    def test_字符串里的别名断言不算断言(self):
        source = "import org.junit.Assert.assertEquals as same\n" + test_class(test_fun("只是字", 'println("same(1, x)")'))
        self.assertEqual(1, len(self.violations({f"{TEST_DIR}/ATest.kt": source})))

    def test_注解与函数之间隔着空行_注释或跨行注解仍要求断言(self):
        for annotations in ("@Test\n", "@Test\n    /** 说明 */", "@Test\n    @Suppress(\n        \"X\",\n    )"):
            with self.subTest(annotations=annotations):
                found = self.violations({f"{TEST_DIR}/ATest.kt": test_class(test_fun("只跑一遍", "render()", annotations=annotations))})
                self.assertEqual(1, len(found), found)
                self.assertIn("没有断言", found[0])

    def test_测试名里的单引号与字符字面量的花括号不打乱后面的函数(self):
        source = test_class(
            test_fun("it's fine", "assertTrue(ok)"),
            test_fun("只跑一遍", "render()"),
            test_fun("字符", "val c = '{'; render()"),
            test_fun("有断言", "assertTrue(ok)"),
        )
        found = self.violations({f"{TEST_DIR}/ATest.kt": source})
        self.assertEqual(2, len(found), found)
        self.assertIn("只跑一遍", found[0])
        self.assertIn("字符", found[1])

    def test_字符串模板里的断言是代码(self):
        source = test_class(test_fun("模板", 'println("${assertEquals(1, x)}")'))
        self.assertEqual([], self.violations({f"{TEST_DIR}/ATest.kt": source}))


if __name__ == "__main__":
    unittest.main()
