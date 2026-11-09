"""网络线路边界门禁的自测：违规样例要拦住，合规写法放行（tools/ci/check.sh lint 运行）。"""

from pathlib import Path
import tempfile
import unittest

from check_netroute import find_violations


class CheckNetRouteTest(unittest.TestCase):

    def violations(self, files: dict[str, str]) -> list[str]:
        root = Path(tempfile.mkdtemp())
        for relative, text in files.items():
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        return find_violations(root)

    def test_模块外引用核心拦下(self):
        for source in (
            "import io.github.touhougo.chogumi.ech.echcore.Echcore\n",
            "fun f() = io.github.touhougo.chogumi.ech.echcore.Echcore.start()\n",
            "import io.github.touhougo.chogumi.ech.echcore.Echcore as Core\nfun f() = Core.stop()\n",
            "fun f() = Echcore\n    .start()\n",
        ):
            with self.subTest(source=source):
                found = self.violations({"app/src/main/kotlin/A.kt": source})
                self.assertGreaterEqual(len(found), 1)
                self.assertIn("app/src/main/kotlin/A.kt:", found[0])

    def test_测试源码也查(self):
        self.assertEqual(1, len(self.violations({"feature/a/src/test/kotlin/T.kt": "val x = Echcore.start()\n"})))

    def test_模块内与注释字符串放行(self):
        self.assertEqual([], self.violations({
            "core/netroute/src/main/kotlin/N.kt": "import io.github.touhougo.chogumi.ech.echcore.Echcore\nfun f() = Echcore.start()\n",
            "app/src/main/kotlin/A.kt": '// Echcore 只在 core:netroute\nval s = "io.github.touhougo.chogumi.ech // 不是代码"\nval r = NetRoute::class\n',
        }))

    def test_别处依赖核心包拦下_模块自己放行(self):
        found = self.violations({
            "app/build.gradle.kts": 'dependencies {\n    implementation(files("libs/echcore.aar"))\n}\n',
            "core/netroute/build.gradle.kts": 'dependencies {\n    implementation(files("libs/echcore.aar"))\n}\n',
        })
        self.assertEqual(1, len(found))
        self.assertIn("app/build.gradle.kts:2", found[0])


if __name__ == "__main__":
    unittest.main()
