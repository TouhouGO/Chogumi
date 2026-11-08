"""缓存流 flowOn 门禁的自测：违规样例拦得住、合规写法放行（tools/ci/check.sh lint 运行）。"""

from pathlib import Path
import tempfile
import unittest

from check_cached_network import find_violations

REPO = """
class HomeRepository(private val computationDispatcher: CoroutineDispatcher) {
    fun progress(refresh: Boolean): Flow<Load<List<Int>>> =
        cachedNetworkCall(
            readCache = { dao.get() },
            mapCache = { it },
            fetchNetwork = { api.get() },
            writeCache = { dao.put(it) },
            refresh = refresh,
            clock = clock,
            workContext = computationDispatcher,
        ).map { load -> withContext(computationDispatcher) { load.map { it.sorted() } } }

    fun observe(): Flow<List<Int>> = dao.observe().map { it.sorted() }.flowOn(computationDispatcher)
}
"""


class CheckCachedNetworkTest(unittest.TestCase):

    def violations(self, files: dict[str, str]) -> list[str]:
        root = Path(tempfile.mkdtemp())
        for rel, text in files.items():
            path = root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        return find_violations(root)

    def test_传workContext与别的函数里的flowOn放行(self):
        self.assertEqual([], self.violations({"core/data/src/main/HomeRepository.kt": REPO}))

    def test_缓存流上接flowOn拦下(self):
        bad = REPO.replace(
            "        ).map { load -> withContext(computationDispatcher) { load.map { it.sorted() } } }",
            "        ).map { load -> load.map { it.sorted() } }\n            .flowOn(computationDispatcher)",
        )
        found = self.violations({"core/data/src/main/HomeRepository.kt": bad})
        self.assertEqual(1, len(found))
        self.assertIn("progress", found[0])

    def test_调用名与参数之间的注释不放过flowOn(self):
        statements = (
            "cachedNetworkCall/*x*/(cache, fetch).flowOn(dispatcher)",
            "cachedNetworkCall(cache, fetch).flowOn/*x*/(dispatcher)",
            "cachedNetworkCall/*x*/(cache, fetch).flowOn/*x*/(dispatcher)",
        )
        for statement in statements:
            with self.subTest(statement=statement):
                source = "fun sample() = " + statement + "\n"
                found = self.violations({"core/data/src/main/Sample.kt": source})
                self.assertEqual(1, len(found), f"注释不能让缓存流的 flowOn 漏报：{found}")
                self.assertTrue(found[0].startswith("core/data/src/main/Sample.kt:1:"), found)
                self.assertIn("sample 在 cachedNetworkCall 的流上接了 flowOn", found[0])

    def test_包在flow构造器里的也拦下(self):
        wrapped = """
fun firstPage(): Flow<Load<Int>> = flow {
    emitAll(cachedNetworkCall(readCache = { 1 }, mapCache = { it }, fetchNetwork = { 2 }, writeCache = {}, clock = clock))
}.flowOn(Dispatchers.Default)
"""
        self.assertEqual(1, len(self.violations({"feature/explore/src/main/Repo.kt": wrapped})))

    def test_点号后换行接flowOn也拦下(self):
        source = "fun sample() = cachedNetworkCall(cache, fetch).\n    flowOn(dispatcher)\n"
        found = self.violations({"core/data/src/main/Sample.kt": source})
        self.assertEqual(1, len(found), found)
        self.assertTrue(found[0].startswith("core/data/src/main/Sample.kt:1:"), found)

    def test_各种修饰符的下一个函数不算进上一个(self):
        for modifiers in ("open", "actual", "private suspend", "final override", "tailrec"):
            with self.subTest(modifiers=modifiers):
                source = (
                    "class Repo {\n"
                    "    fun progress() = cachedNetworkCall(cache, fetch)\n"
                    f"    {modifiers} fun observe() = dao.observe().flowOn(dispatcher)\n"
                    "}\n"
                )
                self.assertEqual([], self.violations({"core/data/src/main/Repo.kt": source}))

    def test_属性是自己的一段(self):
        source = (
            "class Repo {\n"
            "    fun progress() = cachedNetworkCall(cache, fetch)\n"
            "    private val shared = dao.observe().flowOn(dispatcher)\n"
            "    val cached = cachedNetworkCall(cache, fetch)\n"
            "        .flowOn(dispatcher)\n"
            "}\n"
        )
        found = self.violations({"core/data/src/main/Repo.kt": source})
        self.assertEqual(1, len(found), found)
        self.assertTrue(found[0].startswith("core/data/src/main/Repo.kt:5: cached "), found)

    def test_函数里的局部变量不切开函数(self):
        source = (
            "fun sample(): Flow<Load<Int>> {\n"
            "    val flow = cachedNetworkCall(cache, fetch)\n"
            "    val other = 1\n"
            "    return flow.flowOn(dispatcher)\n"
            "}\n"
        )
        found = self.violations({"core/data/src/main/Sample.kt": source})
        self.assertEqual(1, len(found), found)
        self.assertIn("sample 在", found[0])

    def test_注释里提到的不算(self):
        commented = REPO + "// 不要在 cachedNetworkCall( 的结果上接 .flowOn(...)\n"
        self.assertEqual([], self.violations({"core/data/src/main/HomeRepository.kt": commented}))

    def test_导入别名仍拦下(self):
        source = (
            "import sample.cachedNetworkCall as cached\n"
            "fun sample() = cached(cache, fetch).flowOn(dispatcher)\n"
        )
        found = self.violations({"core/data/src/main/Sample.kt": source})
        self.assertEqual(1, len(found), found)
        self.assertIn("flowOn", found[0])


if __name__ == "__main__":
    unittest.main()
