"""图标门禁的自测：清单与文件对不上、文件不是生成的、清单里的图标主源码没人用，都要拦下（tools/ci/check.sh lint 运行）。"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "icons"))

from material_symbols import COMMIT, DRAWABLES, render  # noqa: E402
from material_symbols import check as check_tree  # noqa: E402


def check(root: Path, icons: dict, non_icons: dict | None = None) -> list[str]:
    """假仓库里没有真实的平台标志：放行清单默认为空，要测放行的样例自己给。"""
    return check_tree(root, icons, non_icons or {})

ICONS = {"home": ("home", False), "home_filled": ("home", True)}
OFFICIAL = (
    '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp"\n'
    '    android:viewportWidth="960" android:viewportHeight="960" android:tint="?attr/colorControlNormal">\n'
    '  <path android:fillColor="@android:color/white" android:pathData="M240,760L360,760Z"/>\n</vector>\n'
)


class CheckIconsTest(unittest.TestCase):

    def root(self, files: dict[str, str], uses: str = "R.drawable.home_24, R.drawable.home_filled_24") -> Path:
        root = Path(tempfile.mkdtemp())
        (root / DRAWABLES).mkdir(parents=True)
        for name, text in files.items():
            (root / DRAWABLES / f"{name}_24.xml").write_text(text, encoding="utf-8")
        source = root / "feature/home/src/main/kotlin/Home.kt"
        source.parent.mkdir(parents=True)
        source.write_text(f"val icons = listOf({uses})\n", encoding="utf-8")
        manifest = root / "tools/icons/material_symbols.sha256.json"
        manifest.parent.mkdir(parents=True)
        manifest.write_text(json.dumps({
            "commit": COMMIT,
            "icons": {name: hashlib.sha256(self.generated(name).encode("utf-8")).hexdigest() for name in ICONS},
        }), encoding="utf-8")
        return root

    def generated(self, name: str) -> str:
        symbol, filled = ICONS[name]
        return render(symbol, filled, OFFICIAL)

    def test_清单与生成文件一致放行(self):
        root = self.root({n: self.generated(n) for n in ICONS})
        self.assertEqual([], check(root, ICONS))

    def test_手写的图标拦下(self):
        files = {n: self.generated(n) for n in ICONS}
        files["home"] = OFFICIAL
        found = check(self.root(files), ICONS)
        self.assertEqual(1, len(found))
        self.assertIn("home_24.xml", found[0])

    def test_清单外的图标与缺文件都拦下(self):
        files = {"home": self.generated("home"), "pin": OFFICIAL}
        found = check(self.root(files), ICONS)
        self.assertEqual(2, len(found))
        self.assertTrue(any("pin_24.xml" in v for v in found))
        self.assertTrue(any("home_filled" in v for v in found))

    def test_主源码没人用的图标拦下_测试源码里用不算(self):
        root = self.root({n: self.generated(n) for n in ICONS}, uses="R.drawable.home_24")
        test_source = root / "feature/home/src/test/kotlin/HomeTest.kt"
        test_source.parent.mkdir(parents=True)
        test_source.write_text("val icon = R.drawable.home_filled_24\n", encoding="utf-8")
        found = check(root, ICONS)
        self.assertEqual(1, len(found))
        self.assertIn("home_filled", found[0])

    def test_资源里用到的图标算在用(self):
        root = self.root({n: self.generated(n) for n in ICONS}, uses="R.drawable.home_24")
        layout = root / "core/res/src/main/res/xml/shortcut.xml"
        layout.parent.mkdir(parents=True)
        layout.write_text('<shortcut android:icon="@drawable/home_filled_24" />\n', encoding="utf-8")
        self.assertEqual([], check(root, ICONS))

    def test_注释里的资源名不算使用(self):
        root = self.root({n: self.generated(n) for n in ICONS}, uses="R.drawable.home_filled_24")
        source = root / "feature/home/src/main/kotlin/Home.kt"
        source.write_text("// R.drawable.home_24\nval icon = R.drawable.home_filled_24\n", encoding="utf-8")
        found = check(root, ICONS)
        self.assertEqual(1, len(found), found)
        self.assertIn("home 主源码没有引用", found[0])

    def test_字符串里的资源名不算使用(self):
        root = self.root({n: self.generated(n) for n in ICONS}, uses='"R.drawable.home_24"; val icon = R.drawable.home_filled_24')
        found = check(root, ICONS)
        self.assertEqual(1, len(found), found)
        self.assertIn("home 主源码没有引用", found[0])

    def test_类型别名引用图标仍算使用(self):
        root = self.root(
            {n: self.generated(n) for n in ICONS},
            uses="Res.drawable.home_24, R.drawable.home_filled_24",
        )
        aliases = root / "feature/home/src/main/kotlin/Aliases.kt"
        aliases.write_text(
            "typealias Res = io.github.touhougo.chogumi.core.res.R\n",
            encoding="utf-8",
        )
        self.assertEqual([], check(root, ICONS))

    def test_不叫_24的手写矢量图拦下_放行清单里的不算(self):
        root = self.root({n: self.generated(n) for n in ICONS})
        (root / DRAWABLES / "ic_custom.xml").write_text(OFFICIAL, encoding="utf-8")
        (root / DRAWABLES / "ic_allowed.xml").write_text(OFFICIAL, encoding="utf-8")
        found = check(self.with_uses(root, "R.drawable.ic_allowed"), ICONS, {"ic_allowed.xml": "测试用的放行项"})
        self.assertEqual(1, len(found))
        self.assertIn("ic_custom.xml", found[0])

    def test_非图标白名单不能靠裸文件名跨模块冒用(self):
        root = self.root({n: self.generated(n) for n in ICONS})
        rogue = root / "feature/home/src/main/res/drawable/platform_nintendo_switch.xml"
        rogue.parent.mkdir(parents=True)
        rogue.write_text(OFFICIAL, encoding="utf-8")
        canonical = root / DRAWABLES / "platform_nintendo_switch.xml"
        canonical.write_text(OFFICIAL, encoding="utf-8")
        root = self.with_uses(root, "R.drawable.platform_nintendo_switch")
        found = check(root, ICONS, {"platform_nintendo_switch.xml": "平台标志"})
        self.assertEqual(1, len(found), found)
        self.assertIn("feature/home/src/main/res/drawable/platform_nintendo_switch.xml", found[0])
        self.assertIn("手写的矢量图", found[0])

    def test_非图标白名单只在标准资源目录放行(self):
        root = self.root({n: self.generated(n) for n in ICONS})
        canonical = root / DRAWABLES / "platform_nintendo_switch.xml"
        canonical.write_text(OFFICIAL, encoding="utf-8")
        root = self.with_uses(root, "R.drawable.platform_nintendo_switch")
        self.assertEqual([], check(root, ICONS, {"platform_nintendo_switch.xml": "平台标志"}))

    def with_uses(self, root: Path, uses: str) -> Path:
        (root / "feature/home/src/main/kotlin/Extra.kt").write_text(f"val extra = listOf({uses})\n", encoding="utf-8")
        return root

    def test_非图标白名单的死条目拦下(self):
        root = self.root({n: self.generated(n) for n in ICONS})
        (root / DRAWABLES / "logo_kept.xml").write_text(OFFICIAL, encoding="utf-8")
        (root / DRAWABLES / "logo_unused.xml").write_text(OFFICIAL, encoding="utf-8")
        entries = {"logo_gone.xml": "已删", "logo_kept.xml": "在用", "logo_unused.xml": "没人引用"}
        found = check(self.with_uses(root, "R.drawable.logo_kept"), ICONS, entries)
        self.assertEqual(2, len(found), found)
        self.assertTrue(any("logo_gone.xml" in v and "死配置" in v for v in found))
        self.assertTrue(any("logo_unused.xml" in v and "死配置" in v for v in found))

    def test_mipmap目录里的矢量图同样拦下(self):
        root = self.root({n: self.generated(n) for n in ICONS})
        rogue = root / "core/res/src/main/res/mipmap-anydpi-v26/rogue.xml"
        rogue.parent.mkdir(parents=True)
        rogue.write_text(OFFICIAL, encoding="utf-8")
        found = check(root, ICONS)
        self.assertEqual(1, len(found), found)
        self.assertIn("mipmap-anydpi-v26/rogue.xml", found[0])

    def test_文件名与符号名不一致拦下(self):
        icons = {"home": ("search", False), "home_filled": ("home", True)}
        files = {"home": render("search", False, OFFICIAL), "home_filled": self.generated("home_filled")}
        root = self.root(files)
        (root / "tools/icons/material_symbols.sha256.json").write_text(json.dumps({
            "commit": COMMIT,
            "icons": {name: hashlib.sha256(text.encode("utf-8")).hexdigest() for name, text in files.items()},
        }), encoding="utf-8")
        found = check(root, icons)
        self.assertEqual(1, len(found), found)
        self.assertIn("文件名", found[0])

    def test_Kotlin里手写的矢量图拦下(self):
        root = self.root({n: self.generated(n) for n in ICONS})
        source = root / "feature/home/src/main/kotlin/Custom.kt"
        source.write_text(
            "import androidx.compose.ui.graphics.vector.ImageVector as Vector\n"
            "val custom = Vector.Builder(\"c\", 24.dp, 24.dp, 24f, 24f).build()\n"
            "// ImageVector.Builder 写在注释里不算\n",
            encoding="utf-8",
        )
        found = check(root, ICONS)
        self.assertEqual(1, len(found), found)
        self.assertIn("Custom.kt:2", found[0])

    def test_保留首行但改变图标内容拦下(self):
        original = self.generated("home")
        variants = (
            original.replace('android:pathData="M240,760L360,760Z"', 'android:pathData="M0,0L0,0"'),
            original.replace('android:viewportWidth="960"', 'android:viewportWidth="24"'),
            original.replace('android:tint=', 'android:autoMirrored="true" android:tint='),
        )
        for changed in variants:
            with self.subTest(changed=changed):
                files = {name: self.generated(name) for name in ICONS}
                files["home"] = changed
                found = check(self.root(files), ICONS)
                self.assertEqual(1, len(found), f"首行相同不能掩盖内容改变：{found}")
                self.assertIn("内容摘要", found[0])

    def test_换行格式统一后放行(self):
        files = {name: self.generated(name).replace("\n", "\r\n") for name in ICONS}
        self.assertEqual([], check(self.root(files), ICONS))

    def test_生成时保留随文字方向镜像(self):
        mirrored = OFFICIAL.replace('android:tint=', 'android:autoMirrored="true" android:tint=')
        self.assertIn('android:autoMirrored="true"', render("arrow_back", False, mirrored))

    def test_其他模块与资源变体不能绕过唯一图标目录(self):
        for rel in ("feature/home/src/main/res/drawable/rogue_24.xml", "core/res/src/main/res/drawable-night/home_24.xml"):
            with self.subTest(rel=rel):
                root = self.root({n: self.generated(n) for n in ICONS})
                extra = root / rel
                extra.parent.mkdir(parents=True, exist_ok=True)
                extra.write_text(OFFICIAL, encoding="utf-8")
                found = check(root, ICONS)
                self.assertEqual(1, len(found))
                self.assertIn(rel, found[0])


if __name__ == "__main__":
    unittest.main()
