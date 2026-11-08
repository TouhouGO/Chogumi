"""网页地址门禁的自测（tools/ci/check.sh lint 运行）。"""

from __future__ import annotations

from pathlib import Path
import tempfile
import unittest

from check_web_links import INSTALLER_HOME, OPENER_HOME, SHARE_HOME, find_violations, unused_allowances


class CheckWebLinksTest(unittest.TestCase):

    def violations(self, files: dict[str, str], allowed: set[str] = frozenset(),
                   opener_home: str | None = None, share_home: str | None = None,
                   installer_home: str | None = None) -> list[str]:
        root = Path(tempfile.mkdtemp())
        for rel, text in files.items():
            path = root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        return find_violations(root, set(allowed), opener_home, share_home, installer_home)

    def test_手拼主站地址拦下(self):
        found = self.violations({"feature/a/src/main/Page.kt": 'val url = "https://bgm.tv/user/$name"\n'})
        self.assertEqual(1, len(found))
        self.assertIn("feature/a/src/main/Page.kt:1", found[0])
        self.assertIn("BgmWeb", found[0])

    def test_镜像站同样拦下(self):
        self.assertEqual(1, len(self.violations({"core/x/src/main/A.kt": 'val u = "https://bangumi.tv/subject/1"\n'})))

    def test_主站地址的各种写法同样拦下(self):
        for url in ("https://bgm.tv?u=1", "https://bgm.tv#top", "https://bgm.tv:443/user", "HTTPS://BGM.TV/user/x",
                    "//bgm.tv/user/x", "https://www.bangumi.tv"):
            with self.subTest(url=url):
                self.assertEqual(1, len(self.violations({"feature/a/src/main/Page.kt": f'val u = "{url}"\n'})))

    def test_图片与接口域名不拦(self):
        page = 'val a = "https://lain.bgm.tv/pic/1.jpg"\nval b = "https://api.bgm.tv/v0"\nval c = "https://next.bgm.tv/p1"\n'
        self.assertEqual([], self.violations({"core/x/src/main/A.kt": page}))

    def test_注释与放行文件不拦(self):
        files = {
            "core/x/src/main/A.kt": '// 示例 https://bgm.tv/user/fixture_user\nfun f() = Unit\n',
            "core/model/src/main/BgmWeb.kt": 'const val X = "https://bgm.tv"\n',
        }
        self.assertEqual([], self.violations(files, allowed={"core/model/src/main/BgmWeb.kt"}))

    def test_站点常量手拼路径拦下_接口层放行(self):
        files = {
            "core/data/src/main/Repo.kt": 'fun u(s: String) = "$BGM_SITE/oauth/authorize?state=$s"\nval v = "${BGM_SITE}/user"\n',
            "core/network/src/main/AuthApi.kt": 'fun t() = "$BGM_SITE/oauth/access_token"\n',
            "core/data/src/main/Group.kt": 'val r = Regex("(?:${Regex.escape(BGM_SITE)})?/group")\n',
        }
        found = self.violations(files)
        self.assertEqual(2, len(found))
        self.assertTrue(all(v.startswith("core/data/src/main/Repo.kt:") for v in found))

    def test_站点常量的其他拼法同样拦下(self):
        for line in ('val u = BGM_SITE + "/user/" + name', 'val u = "$BGM_SITE$USER_PATH"', 'val u = "${ BGM_SITE }/user"',
                     'val u = BGM_SITE.plus("/user")'):
            with self.subTest(line=line):
                self.assertEqual(1, len(self.violations({"feature/a/src/main/Page.kt": line + "\n"})))

    def test_站点常量本身当参数不拦(self):
        page = 'val d = Jsoup.parseBodyFragment(html, BGM_SITE)\nval e = "$BGM_SITE"\nval f = "${BGM_SITE}"\n'
        self.assertEqual([], self.violations({"core/common/src/main/A.kt": page}))

    def test_起浏览器与分享的其他写法同样拦下(self):
        for line in ('val i = Intent(Intent.ACTION_SEND_MULTIPLE)', 'val i = Intent("android.intent.action.VIEW", uri)',
                     'val i = Intent("android.intent.action.SEND")', 'ShareCompat.IntentBuilder(c).setText(t).startChooser()',
                     'val i = Intent(Intent.ACTION_CHOOSER)', 'val s = CustomTabsSession(client)'):
            with self.subTest(line=line):
                self.assertEqual(1, len(self.violations({"feature/a/src/main/Page.kt": line + "\n"})))

    def test_放行与两个入口文件不在或用不上了报出来(self):
        root = Path(tempfile.mkdtemp())
        for rel, text in {
            "core/model/src/main/BgmWeb.kt": "object BgmWeb\n",
            "core/foundation/src/main/BangumiSite.kt": 'const val BGM_SITE = "https://bgm.tv"\n',
            OPENER_HOME: "class CustomTabUriHandler\n",
        }.items():
            (root / rel).parent.mkdir(parents=True, exist_ok=True)
            (root / rel).write_text(text, encoding="utf-8")
        allowed = {"core/model/src/main/BgmWeb.kt", "core/foundation/src/main/BangumiSite.kt"}
        found = unused_allowances(root, allowed, OPENER_HOME, SHARE_HOME, INSTALLER_HOME)
        expected = ["core/model/src/main/BgmWeb.kt", OPENER_HOME, SHARE_HOME, INSTALLER_HOME]
        self.assertEqual(sorted(expected), [v.split(": ")[0] for v in found])
        self.assertTrue(all("放行" in v for v in found), found)

    def test_自己起浏览器拦下(self):
        page = 'fun open(c: Context, u: String) = CustomTabsIntent.Builder().build().launchUrl(c, u.toUri())\n'
        found = self.violations({"feature/a/src/main/Page.kt": page, "core/x/src/main/B.kt": 'val i = Intent(Intent.ACTION_VIEW, uri)\n'})
        self.assertEqual(2, len(found))
        self.assertTrue(all("LocalUriHandler" in v for v in found))

    def test_外壳的网页打开器放行(self):
        handler = 'class CustomTabUriHandler { fun open() = CustomTabsIntent.Builder(); val a = Intent.ACTION_VIEW }\n'
        self.assertEqual([], self.violations({OPENER_HOME: handler}, opener_home=OPENER_HOME))

    def test_安装器入口只放行ACTION_VIEW(self):
        install = 'fun Context.installApk(apk: File) = startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, MIME))\n'
        self.assertEqual([], self.violations({INSTALLER_HOME: install}, installer_home=INSTALLER_HOME))
        browser = install + 'fun open() = CustomTabsIntent.Builder()\n'
        found = self.violations({INSTALLER_HOME: browser, "feature/a/src/main/Page.kt": install}, installer_home=INSTALLER_HOME)
        self.assertEqual(2, len(found))
        self.assertTrue(any("CustomTabsIntent" in v and v.startswith(INSTALLER_HOME) for v in found), found)
        self.assertTrue(any(v.startswith("feature/a/src/main/Page.kt:") for v in found), found)

    def test_代码标记只在字符串里不算_模板表达式仍拦(self):
        text_only = 'val a = "CustomTabsIntent"\nval b = "ACTION_VIEW"\nval c = "ACTION_SEND"\n'
        self.assertEqual([], self.violations({"feature/a/src/main/Page.kt": text_only}))
        interpolated = 'val browser = "${CustomTabsIntent.Builder()}"\n'
        self.assertEqual(1, len(self.violations({"feature/a/src/main/Page.kt": interpolated})))

    def test_自己拼分享拦下_分享入口放行(self):
        page = 'val i = Intent(Intent.ACTION_SEND)\nstartActivity(Intent.createChooser(i, null))\n'
        found = self.violations({"feature/a/src/main/Page.kt": page, SHARE_HOME: page}, share_home=SHARE_HOME)
        self.assertEqual(2, len(found))
        self.assertTrue(all(v.startswith("feature/a/src/main/Page.kt:") and "shareLink" in v for v in found))


if __name__ == "__main__":
    unittest.main()
