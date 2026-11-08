"""ViewModel 规矩门禁的自测：每条规矩各给一个违规样例（tools/ci/check.sh lint 运行）。"""

from pathlib import Path
import tempfile
import unittest

from check_viewmodel_rules import find_violations, unused_home

HOME = "core/data/src/testFixtures/kotlin/io/github/touhougo/chogumi/core/data/repository/TestMain.kt"


class CheckViewModelRulesTest(unittest.TestCase):

    def violations(self, files: dict[str, str]) -> list[str]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for rel, text in files.items():
                path = root / rel
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text, encoding="utf-8")
            return find_violations(root)

    def test_规范写法放行(self):
        files = {
            "feature/a/src/main/AVm.kt": "class AVm { val r = outcomeOf { load() } }\n",
            HOME: "fun install() { Dispatchers.setMain(d) }\n",
            "feature/a/src/test/AVmTest.kt": "val s = TestMain.install()\n",
        }
        self.assertEqual([], self.violations(files))

    def test_feature里的catch与TokenStore拦下(self):
        found = self.violations({
            "feature/a/src/main/AVm.kt": "fun f() { try { x() } catch (e: Exception) {} }\nclass B(val t: TokenStore)\n",
            "core/data/src/main/Repo.kt": "fun g() { try { x() } catch (e: Exception) {} }\n",
        })
        self.assertEqual(2, len(found))

    def test_AppEntries创建ViewModel拦下(self):
        found = self.violations({"app/src/main/AppEntries.kt": "val vm = koinViewModel<A>()\n"})
        self.assertEqual(1, len(found))

    def test_feature直接使用页面目的地或路由回调拦下(self):
        samples = (
            "val destination = Route.SubjectDetails(1)",
            "val destination = Route.Calendar",
            "val destination = Route\n .SubjectDetails\n (1)",
            "val destination = io.github.touhougo.chogumi.core.ui.common.navigation.Route.Calendar",
            "import io.github.touhougo.chogumi.core.ui.common.navigation.Route as Page\nval destination = Page.Calendar",
            "import io.github.touhougo.chogumi.core.ui.common.navigation.Route.SubjectDetails as Details\nval destination = Details(1)",
            "fun Screen(onOpen: (Route) -> Unit) {}",
            "fun Screen(onOpen: (\n Route\n )\n -> Unit) {}",
            "fun Screen(onOpen: (destination: Route?) -> Unit) {}",
            "fun Screen(onOpen: Function1<Route, Unit>) {}",
            "fun Screen(onOpen: Route.() -> Unit) {}",
            "import io.github.touhougo.chogumi.core.ui.common.navigation.Route as Page\nfun Screen(onOpen: Page.() -> Unit) {}",
            'val text = "页面 ${Route.SubjectDetails(1)}"',
        )
        for source in samples:
            with self.subTest(source=source):
                found = self.violations({"feature/a/src/main/Screen.kt": source})
                self.assertEqual(1, len(found), found)
                self.assertIn("AppEntries", found[0])

    def test_带别的参数的Route回调也拦下(self):
        for source in (
            "fun Screen(onOpen: (Route, Int) -> Unit) {}",
            "fun Screen(onOpen: (Int, Route) -> Unit) {}",
            "fun Screen(onOpen: (index: Int,\n destination: Route?) -> Unit) {}",
            "fun Screen(onOpen: suspend (Int, core.ui.Route) -> Unit) {}",
        ):
            with self.subTest(source=source):
                found = self.violations({"feature/a/src/main/Screen.kt": source})
                self.assertEqual(1, len(found), found)
                self.assertIn("Route 回调", found[0])

    def test_带别的参数的语义回调放行(self):
        self.assertEqual([], self.violations({"feature/a/src/main/Screen.kt": (
            "fun Screen(onOpen: (RouteKind, Int) -> Unit, onPick: (Int, Router) -> Unit) {}\n"
        )}))

    def test_feature语义回调与注释字符串不算直接路由(self):
        self.assertEqual([], self.violations({
            "feature/a/src/main/Screen.kt": (
                "fun Screen(onSubject: (Int) -> Unit) {}\n"
                "// Route.SubjectDetails(1)\n"
                'val example = "https://example.test/ Route.Calendar (Route) -> Unit"\n'
            ),
            "feature/settings/src/main/DebugLinkEntry.kt": "fun debugRoute(input: String): Route? = inAppRoute(input)",
            "app/src/main/AppEntries.kt": "fun target(id: Int) = Route.SubjectDetails(id)",
            "feature/a/src/test/ScreenTest.kt": "val destination = Route.SubjectDetails(1)",
        }))

    def test_Koin注解拦下(self):
        found = self.violations({
            "feature/a/src/main/Di.kt": "import org.koin.core.annotation.Named\n",
            "gradle/libs.versions.toml": 'koin-annotations = { module = "io.insert-koin:koin-annotations" }\n',
        })
        self.assertEqual(2, len(found))

    def test_嗅探失败文本拦下(self):
        found = self.violations({"core/data/src/main/R.kt": 'val x = e.message?.contains("404") == true\n'})
        self.assertEqual(1, len(found))

    def test_嗅探失败文本的改写也拦下(self):
        for source in (
            'val x = e.message\n    ?.contains("404") == true',
            'val x = e.message.orEmpty().contains("404")',
            'val x = e.message?.startsWith("HTTP 4") == true',
            'val x = e.message!!.endsWith("timeout")',
            'val x = "404" in e.message.orEmpty()',
        ):
            with self.subTest(source=source):
                self.assertEqual(1, len(self.violations({"core/data/src/main/R.kt": source})))

    def test_读失败消息但不嗅探放行(self):
        self.assertEqual([], self.violations({"core/common/src/main/Crash.kt": (
            'val a = error.message?.take(200) ?: "无描述"\n'
            "val b = row.message.id\n"
            "val c = keyword in row.message.body\n"
            "val d = draft.messages.contains(row)\n"
            "val e = row in thread.messages\n"
        )}))

    def test_ViewModel在feature之外也不写catch(self):
        found = self.violations({
            "app/src/main/ShellViewModel.kt": (
                "class ShellViewModel(\n    repo: Repo,\n) : StateViewModel<ShellState>(ShellState()) {\n"
                "    fun f() { try { x() } catch (e: Exception) {} }\n}\n"
            ),
            "core/ui/src/main/HolderVm.kt": "class HolderVm : androidx.lifecycle.ViewModel() { fun f() { try {} catch (e: Exception) {} } }\n",
            "app/src/main/CustomTabUriHandler.kt": "class H { fun f() { try { x() } catch (_: ActivityNotFoundException) {} } }\n",
        })
        self.assertEqual(["app/src/main/ShellViewModel.kt:4", "core/ui/src/main/HolderVm.kt:1"],
                         [v.split(": ")[0] for v in found])

    def test_构建逻辑源码里的koin_annotations拦下(self):
        found = self.violations({
            "build-logic/src/main/kotlin/Deps.kt": (
                '// 不加 koin-annotations\nfun deps() = add("ksp", "io.insert-koin:koin-annotations:2.0")\n'
            ),
        })
        self.assertEqual(1, len(found), found)
        self.assertTrue(found[0].startswith("build-logic/src/main/kotlin/Deps.kt:2:"), found)

    def test_数据层持有FailureNotices拦下(self):
        found = self.violations({
            "core/data/src/main/SearchRepository.kt": "class SearchRepository { val failures = FailureNotices() }\n",
            "core/network/src/main/Api.kt": "class Api(private val notices: FailureNotices)\n",
            "feature/a/src/main/AVm.kt": "class AVm { val failures = FailureNotices() }\n",
            "core/reader/src/main/Composer.kt": "class ComposerActions { val failures = FailureNotices() }\n",
            "core/data/src/main/LaunchWrite.kt": "fun CoroutineScope.launchWrite(failures: FailureNotices, block: () -> Unit) {}\n",
        })
        self.assertEqual(2, len(found))
        self.assertTrue(all(v.startswith(("core/data/", "core/network/")) for v in found))

    def test_setMain只许在TestMain(self):
        found = self.violations({"feature/a/src/test/AVmTest.kt": "fun s() { Dispatchers.setMain(d) }\n"})
        self.assertEqual(1, len(found))

    def test_baselineprofile主源码也是测试源码(self):
        relative = "baselineprofile/src/main/kotlin/StartupJourney.kt"
        found = self.violations({relative: "fun s() { Dispatchers.setMain(d) }\n"})
        self.assertEqual(1, len(found))
        self.assertTrue(found[0].startswith(f"{relative}:1:"), found)

    def test_setMain的别名与跨行成员访问也拦下(self):
        for text in (
            "import kotlinx.coroutines.Dispatchers as Schedulers\nfun f() { Schedulers.setMain(d) }",
            "fun f() { kotlinx.coroutines.Dispatchers\n .setMain(d) }",
            "import kotlinx.coroutines.test.setMain as installMain\nfun f() { Dispatchers.installMain(d) }",
        ):
            with self.subTest(text=text):
                self.assertEqual(1, len(self.violations({"feature/a/src/test/T.kt": text})))

    def test_作用域函数里调setMain也拦下(self):
        for text in (
            "fun f() { with(Dispatchers) { setMain(d) } }",
            "import kotlinx.coroutines.test.setMain\nfun f() { Dispatchers.run { setMain(d) } }",
        ):
            with self.subTest(text=text):
                found = self.violations({"feature/a/src/test/T.kt": text})
                self.assertEqual(1, len(found), found)

    def test_所有深度模块的测试源集拦下setMain(self):
        modules = (
            "app", "benchmark", "baselineprofile", "build-logic", "core/data", "feature/home",
            "feature/group/deep", "extensions/nested/deeper/module", "",
        )
        for module in modules:
            for source_set in ("test", "testFixtures", "androidTest"):
                relative = f"{module + '/' if module else ''}src/{source_set}/kotlin/SampleTest.kt"
                with self.subTest(relative=relative):
                    found = self.violations({relative: "fun install() { Dispatchers.setMain(dispatcher) }\n"})
                    self.assertEqual(1, len(found), f"测试目录不能漏掉 {relative} 的 setMain：{found}")
                    self.assertTrue(found[0].startswith(f"{relative}:1:"), found)
                    self.assertIn("Dispatchers.setMain 只许在 TestMain", found[0])

    def test_同名TestMain只放行真实路径(self):
        others = (
            "app/src/test/kotlin/io/github/touhougo/chogumi/core/data/repository/TestMain.kt",
            "core/data/src/test/kotlin/io/github/touhougo/chogumi/core/data/repository/TestMain.kt",
            "core/data/src/testFixtures/kotlin/TestMain.kt",
            "core/other/src/testFixtures/kotlin/io/github/touhougo/chogumi/core/data/repository/TestMain.kt",
            "feature/group/deep/src/androidTest/kotlin/TestMain.kt",
        )
        for relative in others:
            with self.subTest(relative=relative):
                files = {
                    HOME: "fun install() { Dispatchers.setMain(dispatcher) }\n",
                    relative: "fun install() { Dispatchers.setMain(dispatcher) }\n",
                }
                found = self.violations(files)
                self.assertEqual(1, len(found), f"同名文件不能获得真实 TestMain 的例外：{found}")
                self.assertTrue(found[0].startswith(f"{relative}:1:"), found)

    def test_TestMain不在或已不调setMain时报出来(self):
        for files in ({}, {HOME: "fun install() = Unit\n// Dispatchers.setMain(d)\n"}):
            with self.subTest(files=files), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                for rel, text in files.items():
                    (root / rel).parent.mkdir(parents=True, exist_ok=True)
                    (root / rel).write_text(text, encoding="utf-8")
                found = unused_home(root)
                self.assertEqual(1, len(found), found)
                self.assertTrue(found[0].startswith(f"{HOME}: "), found)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / HOME).parent.mkdir(parents=True)
            (root / HOME).write_text("fun install() { Dispatchers.setMain(d) }\n", encoding="utf-8")
            self.assertEqual([], unused_home(root))

    def test_测试源码按路径排序且只报一次(self):
        source = "fun install() { Dispatchers.setMain(dispatcher) }\n"
        files = {
            "feature/group/deep/src/androidTest/kotlin/Z.kt": source,
            "app/src/test/kotlin/A.kt": source,
            "core/data/src/testFixtures/kotlin/B.kt": source,
        }
        found = self.violations(files)
        self.assertEqual(3, len(found), found)
        self.assertEqual(sorted(files), [violation.split(":", 1)[0] for violation in found])

    def test_测试源集注释里的setMain不拦(self):
        source = '// Dispatchers.setMain(dispatcher)\n/* 外层 /* 内层 */ Dispatchers.setMain(dispatcher) */\n'
        files = {
            "app/src/test/kotlin/A.kt": source,
            "feature/group/deep/src/testFixtures/kotlin/B.kt": source,
        }
        self.assertEqual([], self.violations(files))

    def test_隐藏目录与构建输出不属于测试源集(self):
        source = "fun install() { Dispatchers.setMain(dispatcher) }\n"
        files = {
            ".scratch/app/src/test/kotlin/A.kt": source,
            "app/build/generated/src/test/kotlin/A.kt": source,
            "app/.cache/copied/src/androidTest/kotlin/A.kt": source,
            "copies/src/test/kotlin/nested/src/test/kotlin/A.kt": source,
        }
        found = self.violations(files)
        self.assertEqual(1, len(found), found)
        self.assertTrue(found[0].startswith("copies/src/test/kotlin/nested/src/test/kotlin/A.kt:1:"), found)

    def test_注释里的写法不拦(self):
        self.assertEqual([], self.violations({"feature/a/src/main/AVm.kt": "// 不写 catch (e: Exception)，包进 outcomeOf\n"}))

    def test_TokenStore与FailureNotices的导入别名仍拦下(self):
        found = self.violations({
            "feature/a/src/main/AVm.kt": (
                "import io.github.touhougo.chogumi.core.data.token.TokenStore as Tokens\n"
                "class AVm(val store: Tokens)\n"
            ),
            "core/data/src/main/Repo.kt": (
                "import io.github.touhougo.chogumi.core.common.failure.FailureNotices as Notices\n"
                "class Repo { val failures = Notices() }\n"
            ),
        })
        self.assertEqual(2, len(found), found)


if __name__ == "__main__":
    unittest.main()
