"""时钟门禁的自测（tools/ci/check.sh lint 运行）。"""

from __future__ import annotations

from pathlib import Path
import tempfile
import unittest

from check_clock import find_violations, unused_allowances


class CheckClockTest(unittest.TestCase):

    def violations(self, files: dict[str, str], allowed: dict[str, str] | None = None) -> list[str]:
        root = Path(tempfile.mkdtemp())
        for rel, text in files.items():
            path = root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        return find_violations(root, allowed or {})

    def test_各种自己读时间的写法都拦下(self):
        page = "\n".join([
            "val a = System.currentTimeMillis()",
            "val b = System.nanoTime()",
            "val c = SystemClock.elapsedRealtime()",
            "val d = Calendar.getInstance().get(Calendar.YEAR)",
            "val e = LocalDate.now()",
            "val f = Instant.now()",
            "val g = ZoneId.systemDefault()",
            "val h = TimeZone.getDefault()",
            "val i = Clock.systemUTC()",
            "val j = Date()",
            "val k = kotlin.time.Clock.System.now()",
            "val l = SimpleDateFormat(\"yyyy\")",
            "val m = TimeSource.Monotonic.markNow()",
        ]) + "\n"
        found = self.violations({"feature/a/src/main/Page.kt": page})
        self.assertEqual(13, len(found))
        self.assertIn("feature/a/src/main/Page.kt:1", found[0])
        self.assertIn("AppTime", found[0])

    def test_拆行与方法引用的读法也拦下(self):
        page = "\n".join([
            "val a = System\n    .currentTimeMillis()",
            "val b = Clock\n    .systemDefaultZone()",
            "val c = SystemClock\n    .uptimeMillis()",
            "val d = TimeSource\n    .Monotonic.markNow()",
            "val e = TimeZone\n    .getDefault()",
            "val f = ZoneId\n    .systemDefault()",
            "val g = System::currentTimeMillis",
            "val h = SystemClock::elapsedRealtime",
            "val i = MonthDay.now()",
        ]) + "\n"
        found = self.violations({"feature/a/src/main/Page.kt": page})
        self.assertEqual(9, len(found), found)
        self.assertIn("（System.currentTimeMillis）", found[0])
        self.assertIn("（System.currentTimeMillis）", found[6])

    def test_放行按调用本身比_不看换行与引用写法(self):
        files = {"core/foundation/src/main/AppClock.kt": "val s = AppClock(System::currentTimeMillis)\nval t = System\n    .currentTimeMillis()\n"}
        self.assertEqual([], self.violations(files, {"core/foundation/src/main/AppClock.kt": "System.currentTimeMillis"}))

    def test_放行文件不在或用不上了报出来(self):
        root = Path(tempfile.mkdtemp())
        for rel, text in {
            "core/foundation/src/main/AppClock.kt": "val s = AppClock { 0L }\n",
            "core/foundation/src/main/BangumiZone.kt": "fun displayZone() = ZoneId\n    .systemDefault()\n",
        }.items():
            (root / rel).parent.mkdir(parents=True, exist_ok=True)
            (root / rel).write_text(text, encoding="utf-8")
        allowed = {
            "core/foundation/src/main/AppClock.kt": "System.currentTimeMillis",
            "core/foundation/src/main/BangumiZone.kt": "ZoneId.systemDefault",
            "core/foundation/src/main/Gone.kt": "ZoneId.systemDefault",
        }
        found = unused_allowances(root, allowed)
        self.assertEqual(["core/foundation/src/main/AppClock.kt", "core/foundation/src/main/Gone.kt"],
                         [v.split(": ")[0] for v in found])
        self.assertTrue(all("放行" in v for v in found), found)

    def test_放行文件只放行登记的那一种(self):
        files = {"core/foundation/src/main/AppClock.kt": "val s = System.currentTimeMillis()\nval n = System.nanoTime()\n"}
        found = self.violations(files, {"core/foundation/src/main/AppClock.kt": "System.currentTimeMillis"})
        self.assertEqual(1, len(found))
        self.assertIn("System.nanoTime", found[0])

    def test_注释和给定时间的写法不拦(self):
        page = (
            "// 别写 System.currentTimeMillis()，用 AppClock\n"
            "/* LocalDate.now() 会按设备时区 */\n"
            "val d = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()\n"
            "val y = LocalDate.of(2026, 1, 1)\n"
        )
        self.assertEqual([], self.violations({"core/x/src/main/A.kt": page}))

    def test_反引号短模板后注释中的时钟调用不拦(self):
        source = 'val `q"r` = 1\nval t = "$`q"r`"; /* System.currentTimeMillis() */\n'
        self.assertEqual([], self.violations({"feature/a/src/main/Page.kt": source}))

    def test_反引号短模板的URL后时钟调用拦下(self):
        source = 'val `q"r` = 1\nval t = "$`q"r` ` https://example.test"; System.currentTimeMillis()\n'
        found = self.violations({"feature/a/src/main/Page.kt": source})
        self.assertEqual(1, len(found), f"短模板不能让 URL 后的时钟调用漏报：{found}")
        self.assertTrue(found[0].startswith("feature/a/src/main/Page.kt:2:"), found)
        self.assertIn("System.currentTimeMillis", found[0])

    def test_时刻参数带默认值拦下_数据类字段和时长不拦(self):
        page = (
            "fun EpisodeBo.toEntity(updatedAt: Long = 0L) = 1\n"
            "suspend fun save(now: Long = clock.nowMillis()) = 2\n"
            "data class Row(val updatedAt: Long = 0L, var syncedAt: Long? = null)\n"
            "fun call(ttlMillis: Long = 60_000L, updatedAt: Long) = 3\n"
        )
        found = self.violations({"core/data/src/main/A.kt": page})
        self.assertEqual(2, len(found))
        self.assertIn("A.kt:1", found[0])
        self.assertIn("updatedAt", found[0])
        self.assertIn("A.kt:2", found[1])

    def test_时刻参数_类型是Instant也拦_属性的空白写法不误报(self):
        page = (
            "fun f(now: Instant = Instant.EPOCH) = 1\n"
            "data class Row(val  updatedAt: Long = 0L, private val\n    savedAt: Long = 0L)\n"
        )
        found = self.violations({"core/data/src/main/A.kt": page})
        self.assertEqual(["core/data/src/main/A.kt:1"], [v.split(": ")[0] for v in found])

    def test_系统类与时间类型的导入别名仍拦下(self):
        page = (
            "import java.lang.System as Sys\n"
            "import java.time.LocalDate as Day\n"
            "val millis = Sys.currentTimeMillis()\n"
            "val date = Day.now()\n"
        )
        found = self.violations({"feature/a/src/main/A.kt": page})
        self.assertEqual(2, len(found), found)


if __name__ == "__main__":
    unittest.main()
