"""测试网络客户端门禁的自测：手拼客户端拦得住，生产工厂与注释放行（tools/ci/check.sh lint 运行）。"""

from pathlib import Path
import tempfile
import unittest

from check_test_clients import find_violations

PRODUCTION_SHAPED = """
private fun repository(engine: MockEngine) = SubjectRepository(SubjectApi(testApiClient(engine)))
"""

HAND_BUILT = """
private fun repository(engine: MockEngine) = SubjectRepository(
    SubjectApi(HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }),
)
"""

PLACEHOLDER = """
private val webPage = WebPageApi(HttpClient(MockEngine { error("不请求") }))
"""


class CheckTestClientsTest(unittest.TestCase):

    def violations(self, files: dict[str, str]) -> list[str]:
        root = Path(tempfile.mkdtemp())
        for rel, text in files.items():
            path = root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        return find_violations(root)

    def test_生产工厂放行(self):
        self.assertEqual([], self.violations({"core/data/src/test/kotlin/RepoTest.kt": PRODUCTION_SHAPED}))

    def test_单元测试里手拼客户端拦下(self):
        found = self.violations({"feature/home/src/test/kotlin/HomeTest.kt": HAND_BUILT})
        self.assertEqual(1, len(found))
        self.assertIn("feature/home/src/test/kotlin/HomeTest.kt:3", found[0])

    def test_全限定名_空格_换名导入的手拼客户端也拦(self):
        for text in (
            "val c = io.ktor.client.HttpClient(engine)\n",
            "val c = HttpClient (engine)\n",
            "import io.ktor.client.HttpClient as Client\n",
        ):
            self.assertEqual(1, len(self.violations({"feature/home/src/test/kotlin/HomeTest.kt": text})), text)

    def test_测试夹具与设备测试里也拦(self):
        found = self.violations({
            "core/data/src/testFixtures/kotlin/Clients.kt": HAND_BUILT,
            "app/src/androidTest/kotlin/AppTest.kt": HAND_BUILT,
        })
        self.assertEqual(2, len(found))

    def test_占位客户端也拦_夹具同样受约束(self):
        found = self.violations({"core/data/src/test/kotlin/StatsTest.kt": PLACEHOLDER})
        self.assertEqual(1, len(found))
        fixture = self.violations({"core/data/src/testFixtures/kotlin/Clients.kt": PLACEHOLDER})
        self.assertEqual(1, len(fixture))
        self.assertIn("HttpClient", fixture[0])

    def test_夹具里另写裸客户端不再整目录豁免(self):
        found = self.violations({"feature/a/src/testFixtures/kotlin/Client.kt": "val client = HttpClient()\n"})
        self.assertEqual(1, len(found), found)
        self.assertIn("HttpClient", found[0])

    def test_主源码不归它管(self):
        self.assertEqual([], self.violations({"core/network/src/main/kotlin/NetworkModule.kt": HAND_BUILT}))

    def test_baselineprofile主源码是测试源码(self):
        found = self.violations({"baselineprofile/src/main/kotlin/StartupJourney.kt": HAND_BUILT})
        self.assertEqual(1, len(found))
        self.assertIn("baselineprofile/src/main/kotlin/StartupJourney.kt:3", found[0])

    def test_注释里提到不算(self):
        commented = "// 不要 install(ContentNegotiation) 自己拼\n" + PRODUCTION_SHAPED
        self.assertEqual([], self.violations({"core/data/src/test/kotlin/RepoTest.kt": commented}))

    def test_字符串只是提到不算_模板表达式仍拦(self):
        text_only = 'val note = "HttpClient(engine)"\nval plugin = "ContentNegotiation"\n'
        self.assertEqual([], self.violations({"core/data/src/test/kotlin/RepoTest.kt": text_only}))
        interpolated = 'val client = "${HttpClient(engine)}"\n'
        self.assertEqual(1, len(self.violations({"core/data/src/test/kotlin/RepoTest.kt": interpolated})))

    def test_跨行安装与插件别名仍拦下(self):
        for text in (
            "fun f() { install\n(\nContentNegotiation\n) }",
            "import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as Negotiation\nfun f() { install(Negotiation) }",
            "fun f() { install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) }",
        ):
            with self.subTest(text=text):
                self.assertEqual(1, len(self.violations({"core/data/src/test/kotlin/RepoTest.kt": text})))

    def test_泛型_命名参数_先存成值_函数引用也拦(self):
        for text in (
            "val c = HttpClient<MockEngineConfig>(MockEngine) {}\n",
            "val c = testApiClient(engine).config { install(plugin = ContentNegotiation) }\n",
            "val plugin = ContentNegotiation\nfun f() = testApiClient(engine).config { install(plugin) }\n",
            "val make = ::HttpClient\n",
        ):
            with self.subTest(text=text):
                self.assertEqual(1, len(self.violations({"core/data/src/test/kotlin/RepoTest.kt": text})))

    def test_只是类型的HttpClient放行(self):
        text = "private lateinit var client: HttpClient\nprivate val clients = mutableListOf<HttpClient>()\n"
        self.assertEqual([], self.violations({"core/data/src/test/kotlin/RepoTest.kt": text}))

    def test_块注释不隐藏同一行的客户端(self):
        self.assertEqual([], self.violations({"core/data/src/test/kotlin/RepoTest.kt": "val x = 1 /* HttpClient(engine) */"}))
        self.assertEqual(1, len(self.violations({"core/data/src/test/kotlin/RepoTest.kt": "/* note */ val c = HttpClient(engine)"})))


if __name__ == "__main__":
    unittest.main()
