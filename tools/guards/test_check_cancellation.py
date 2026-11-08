"""数据层取消门禁的自测：违规样例拦得住、合规写法放行（tools/ci/check.sh lint 运行）。"""

from pathlib import Path
import tempfile
import unittest

from check_cancellation import find_violations

PKG = "core/data/src/main/kotlin/io/github/touhougo/chogumi/core/data/"

SESSION_HELPER = """
suspend fun <T> withSessionToken(block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
}
"""

LOGIN = """
class AuthRepository {
    suspend fun login(): Boolean {
        try {
            api.getMe()
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        }
    }
}
"""

COLUMN_JSON = """
internal fun String.toIntList() =
    runCatching { json.decodeFromString(intListSerializer, this) }.getOrDefault(emptyList())
"""

MODULE = """
val dataModule = module {
    single { SmileyAssetStore(httpGet = { url -> runCatching { client.get(url).bodyAsBytes() }.getOrNull() }) }
}
"""


class CheckCancellationTest(unittest.TestCase):

    def violations(self, files: dict[str, str]) -> list[str]:
        root = Path(tempfile.mkdtemp())
        for rel, text in files.items():
            path = root / PKG / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        return find_violations(root, report_dead=False)

    def test_唯一判定处与登记过的解析放行(self):
        self.assertEqual([], self.violations({
            "token/SessionRequestHelper.kt": SESSION_HELPER,
            "db/mapper/ColumnJson.kt": COLUMN_JSON,
        }))

    def test_别处手写接取消拦下(self):
        found = self.violations({"repository/AuthRepository.kt": LOGIN})
        self.assertEqual(2, len(found), found)
        self.assertIn("AuthRepository.kt:7", found[0])
        self.assertIn("AuthRepository.kt:9", found[1])

    def test_自己判断异常是不是取消拦下_登记的放行(self):
        stray = """
private fun <T> Flow<T>.closeOnStrayCancellation(channel: ProducerScope<*>): Flow<T> = catch { error ->
    if (error is kotlinx.coroutines.CancellationException && currentCoroutineContext().isActive) channel.close(error) else throw error
}
"""
        found = self.violations({"repository/Observer.kt": stray})
        self.assertEqual(1, len(found), found)
        self.assertIn("Observer.kt:3", found[0])
        self.assertIn("ALLOWED_IS_CANCELLATION", found[0])
        root = Path(tempfile.mkdtemp())
        path = root / PKG / "repository/Observer.kt"
        path.parent.mkdir(parents=True)
        path.write_text(stray, encoding="utf-8")
        allowed = {("repository/Observer.kt", "closeOnStrayCancellation"): "交给收集方"}
        self.assertEqual([], find_violations(root, report_dead=False, is_allowed=allowed))
        self.assertEqual([], self.violations({"repository/Other.kt": "fun f(e: Exception) = e is IOException\n"}))

    def test_判断取消的白名单匹配不到算死配置(self):
        root = Path(tempfile.mkdtemp())
        path = root / PKG / "repository/Observer.kt"
        path.parent.mkdir(parents=True)
        path.write_text("fun f() = 1\n", encoding="utf-8")
        found = find_violations(root, {}, is_allowed={("repository/Observer.kt", "closeOnStrayCancellation"): "x"})
        self.assertTrue(any("ALLOWED_IS_CANCELLATION" in item and "死配置" in item for item in found), found)

    def test_没登记的runCatching拦下(self):
        found = self.violations({"di/DataModule.kt": MODULE})
        self.assertEqual(1, len(found))
        self.assertIn("runCatching", found[0])

    def test_登记的函数名换了文件也拦下(self):
        self.assertEqual(1, len(self.violations({"db/mapper/OtherMapper.kt": COLUMN_JSON})))

    def test_函数里的局部变量不改变归属(self):
        local = """
class SmileyAssetStore {
    private val cleanUp = lazy { runCatching { root.delete() } }

    private fun isUrlAllowed(url: String): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        return uri.scheme == "https"
    }

    private suspend fun fetch(url: String): ByteArray? {
        val bytes = runCatching { client.get(url) }.getOrNull()
        return bytes
    }
}
"""
        found = self.violations({"repository/SmileyAssetStore.kt": local})
        self.assertEqual(1, len(found))
        self.assertIn("SmileyAssetStore.kt:11", found[0])

    def test_白名单里匹配不到的条目算死配置(self):
        root = Path(tempfile.mkdtemp())
        path = root / PKG / "db/mapper/ColumnJson.kt"
        path.parent.mkdir(parents=True)
        path.write_text("fun f() = 1\n", encoding="utf-8")
        found = find_violations(root, {("db/mapper/ColumnJson.kt", "toIntList"): "同步解析"})
        dead = [item for item in found if "toIntList" in item]
        self.assertEqual(1, len(dead), found)
        self.assertIn("死配置", dead[0])

    def test_取消判定文件里没有接取消算死配置(self):
        root = Path(tempfile.mkdtemp())
        for rel, text in {"util/OutcomeOf.kt": SESSION_HELPER, "util/SharedFetch.kt": "fun f() = 1\n"}.items():
            path = root / PKG / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        found = find_violations(root, {}, is_allowed={})
        self.assertEqual(2, len(found), found)
        self.assertTrue(any("token/SessionRequestHelper.kt" in item for item in found), found)
        self.assertTrue(any("util/SharedFetch.kt" in item for item in found), found)

    def test_接住取消的父类型同样拦下_具体异常放行(self):
        for caught in ("Exception", "Throwable", "kotlin.RuntimeException", "IllegalStateException"):
            with self.subTest(caught=caught):
                source = f"fun f() {{ try {{ work() }} catch (e: {caught}) {{ log(e) }} }}\n"
                found = self.violations({"repository/X.kt": source})
                self.assertEqual(1, len(found), found)
        specific = "fun f() { try { parse() } catch (e: DateTimeException) { null } }\n"
        self.assertEqual([], self.violations({"repository/X.kt": specific}))

    def test_登记名字之后的其他声明不借用登记(self):
        for later in (
            "    open suspend fun fetch() { runCatching { client.get() } }\n",
            "    init { scope.launch { runCatching { client.get() } } }\n",
            "    companion object { val probe = runCatching { client.get() } }\n",
        ):
            with self.subTest(later=later):
                source = "class SmileyAssetStore {\n    private fun isUrlAllowed(u: String) = runCatching { 1 }.isSuccess\n" + later + "}\n"
                found = self.violations({"repository/SmileyAssetStore.kt": source})
                self.assertEqual(1, len(found), found)
                self.assertIn("SmileyAssetStore.kt:3", found[0])

    def test_登记的名字是挂起函数也拦下(self):
        source = "class SmileyAssetStore {\n    private suspend fun isUrlAllowed(u: String) = runCatching { client.get(u) }.isSuccess\n}\n"
        found = self.violations({"repository/SmileyAssetStore.kt": source})
        self.assertEqual(1, len(found), found)
        self.assertIn("挂起", found[0])

    def test_注释里提到的不算(self):
        commented = "// 不要 catch (e: CancellationException)，也不要 runCatching { suspendCall() }\nfun f() = 1\n"
        self.assertEqual([], self.violations({"repository/X.kt": commented}))

    def test_取消类型与runCatching的导入别名仍拦下(self):
        source = (
            "import kotlinx.coroutines.CancellationException as Cancelled\n"
            "import kotlin.runCatching as attempt\n"
            "fun guard() { try { work() } catch (e: Cancelled) { throw e } }\n"
            "fun parse() = attempt { decode() }\n"
        )
        found = self.violations({"repository/X.kt": source})
        self.assertEqual(2, len(found), found)
        self.assertTrue(any("接住了取消" in item for item in found))
        self.assertTrue(any("runCatching" in item for item in found))


if __name__ == "__main__":
    unittest.main()
