"""Kotlin 注释扫描自测：字面量完整保留，注释不参与门禁，诊断仍指向原行。"""

from __future__ import annotations

from pathlib import Path
import re
import tempfile
import unittest

import check_adaptive
import check_cached_network
import check_cancellation
import check_clock
import check_color_anim
import check_cover_clip
import check_display_name
import check_fabs
import check_login_gate
import check_menus
import check_motion
import check_press_area
import check_separators
import check_status_bar
import check_surface_roles
import check_text_fields
import check_timeline_category
import check_type_shape
import check_viewmodel_rules
import check_web_links
from sources import (
    github_sources,
    import_usage,
    kotlin_typealiases,
    local_action_candidates,
    normalize_code,
    normalize_kotlin,
    project_sources,
    strip_comments,
    symbol_names,
    test_sources,
)


TEST_SOURCE_MATRIX = {
    "app/src/test/kotlin/AppTest.kt",
    "app/src/androidTest/kotlin/AppAndroidTest.kt",
    "core/ui/src/testFixtures/kotlin/Fixtures.kt",
    "baselineprofile/src/main/kotlin/StartupJourney.kt",
}


class TestSourcesTest(unittest.TestCase):
    def sources(self, files: set[str]) -> list[str]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative in files:
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("class Sample\n", encoding="utf-8")
            return [path.relative_to(root).as_posix() for path in test_sources(root)]

    def test_普通测试源集与baselineprofile主源码都是测试源码(self):
        self.assertEqual(sorted(TEST_SOURCE_MATRIX), self.sources(TEST_SOURCE_MATRIX))

    def test_普通主源码隐藏目录与构建输出不是测试源码(self):
        excluded = {
            "app/src/main/kotlin/Production.kt",
            "baselineprofile/src/debug/kotlin/DebugSupport.kt",
            ".scratch/app/src/test/kotlin/HiddenTest.kt",
            "app/build/generated/src/test/kotlin/GeneratedTest.kt",
            "app/.cache/copied/src/androidTest/kotlin/CachedTest.kt",
        }
        self.assertEqual([], self.sources(excluded))


class KotlinCommentsTest(unittest.TestCase):
    def test_字符串里的类型别名声明不进入类型索引(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "Example.kt"
            source.write_text(
                'val example = """\ntypealias FakeLabel = androidx.compose.material3.Text\n"""\n'
                'typealias RealLabel = androidx.compose.material3.Text\n',
                encoding="utf-8",
            )
            self.assertEqual({"RealLabel": "Text"}, kotlin_typealiases([source]))

    def test_字符串里的导入别名声明不改写真实标识(self):
        source = 'val example = """\nimport java.time.LocalDate as Day\n"""\nfun local() = Day()\n'
        self.assertIn("fun local() = Day()", normalize_kotlin(source))

    def test_全部项目源码入口只枚举指定模块且排除隐藏和build目录(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            included = (
                "app/src/main/App.kt",
                "core/data/src/test/DataTest.kt",
                "feature/home/src/androidTest/HomeTest.kt",
                "baselineprofile/src/main/Baseline.kt",
                "build-logic/src/test/PluginTest.kt",
            )
            excluded = (
                "sample/src/main/Outside.kt",
                "app/src/main/build/Generated.kt",
                "core/data/src/.hidden/Hidden.kt",
                ".hidden/feature/fake/src/main/Hidden.kt",
            )
            for relative in included + excluded:
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("", encoding="utf-8")

            self.assertEqual(sorted(included), [path.relative_to(root).as_posix() for path in project_sources(root)])

    def test_import使用遮罩与注释遮罩共用模板边界(self):
        source = 'val text = "${run { "${Foo()}" }} $`when` literal" /** [Doc] */ // [Line]\n/* [Block] */\n'
        usage = import_usage(source)
        self.assertIn("Foo", usage)
        self.assertIn("when", usage)
        self.assertNotIn("literal", usage)
        self.assertIn("[Doc]", usage)
        self.assertNotIn("Line", usage)
        self.assertNotIn("Block", usage)
        self.assertEqual(len(source), len(strip_comments(source)))
        self.assertEqual(source[:source.index("/**")], strip_comments(source)[:source.index("/**")])

    def test_符号别名和原名称统一列出(self):
        source = "import sample.Image as Picture\nval x = Image()"
        self.assertEqual({"Image", "Picture"}, symbol_names(source, "sample.Image"))
        self.assertEqual({"Image"}, symbol_names(strip_comments("// import sample.Image as Picture"), "sample.Image"))

    def test_普通字符串完整保留(self):
        source = 'val site = "https://example.test/*原样*/"; val label = "//"; next()\n'
        self.assertEqual(source, strip_comments(source))

    def test_普通字符串的转义引号与反斜杠保留(self):
        source = r'val label = "\"https://example.test/*原样*/\"\\"; next()' + "\n"
        self.assertEqual(source, strip_comments(source))

    def test_三引号字符串中的注释标记引号与换行保留(self):
        source = 'val text = """https://example.test\n/*原样*/ " // \\n\n"""; next()\n'
        self.assertEqual(source, strip_comments(source))

    def test_三引号末尾连续引号不改变后续注释边界(self):
        for ending in ('""""', '"""""', '""""""'):
            with self.subTest(ending=ending):
                source = 'val text = """内容' + ending + '; /*x*/ next()\n'
                expected = 'val text = """内容' + ending + ';       next()\n'
                self.assertEqual(expected, strip_comments(source))

    def test_三引号结束不被反斜杠转义(self):
        source = 'val text = """\\"""; /*x*/ next()\n'
        expected = 'val text = """\\""";       next()\n'
        self.assertEqual(expected, strip_comments(source))

    def test_字符字面量不会改变字符串边界(self):
        source = r'''val quote = '"'; val slash = '/'; val single = '\''; val backslash = '\\'; val label = "/*原样*/"; next()''' + "\n"
        self.assertEqual(source, strip_comments(source))

    def test_反引号名称中的引号不开始字符串(self):
        source = 'val `"名称` = 1; /*x*/ next()\n'
        expected = 'val `"名称` = 1;       next()\n'
        self.assertEqual(expected, strip_comments(source))

    def test_反引号短模板名称中的引号不结束字符串(self):
        cases = (
            ('val t = "$`q"r`"; /*x*/ next()\n',
             'val t = "$`q"r`";       next()\n'),
            ('val t = "$`q"r` ` https://example.test"; /*x*/ next()\n',
             'val t = "$`q"r` ` https://example.test";       next()\n'),
            ('val t = """$`q"""r`"""; /*x*/ next()\n',
             'val t = """$`q"""r`""";       next()\n'),
            ('val t = $$"""$$`q"""r`"""; /*x*/ next()\n',
             'val t = $$"""$$`q"""r`""";       next()\n'),
        )
        for source, expected in cases:
            with self.subTest(source=source):
                self.assertEqual(expected, strip_comments(source))

    def test_注释遮蔽保留字符偏移与换行(self):
        cases = (
            ("a/*x*/b", "a     b"),
            ("a//x\nb", "a   \nb"),
            ("a/*x\r\ny*/b", "a   \r\n   b"),
            ("a//x\rb", "a   \rb"),
            ("/**/next()", "    next()"),
            ("a// /*x*/\nb", "a        \nb"),
            ("a/*//\nx*/b", "a    \n   b"),
        )
        for source, expected in cases:
            with self.subTest(source=source):
                self.assertEqual(expected, strip_comments(source))

    def test_嵌套块注释整体遮蔽(self):
        self.assertEqual("a           b", strip_comments("a/*1/*2*/3*/b"))

    def test_未闭合注释遮到文件末尾(self):
        cases = (
            ("a/*x", "a   "),
            ("a//x", "a   "),
            ("a/*1/*2*/", "a        "),
        )
        for source, expected in cases:
            with self.subTest(source=source):
                self.assertEqual(expected, strip_comments(source))

    def test_未闭合字面量完整保留(self):
        for source in ('val text = "https://example.test', 'val text = """/*原样*/\n', "val slash = '/"):
            with self.subTest(source=source):
                self.assertEqual(source, strip_comments(source))

    def test_模板表达式中的字符串完整保留(self):
        cases = (
            'val text = "${"https://example.test/*原样*/"}"; next()\n',
            'val text = """${"https://example.test/*原样*/"}"""; next()\n',
            'val text = "${run { "${"https://example.test"}" }}"; next()\n',
        )
        for source in cases:
            with self.subTest(source=source):
                self.assertEqual(source, strip_comments(source))

    def test_多美元模板阈值内的文字不作为代码(self):
        cases = (
            'val text = $$"""${/*原样*/ "https://example.test"}"""; next()\n',
            'val text = $$$"""$${/*原样*/ "https://example.test"}"""; next()\n',
        )
        for source in cases:
            with self.subTest(source=source):
                self.assertEqual(source, strip_comments(source))

    def test_多美元模板中的真实注释遮蔽(self):
        cases = (
            ('val text = $$"""$${/*x*/ "https://example.test"}"""; next()\n',
             'val text = $$"""$${      "https://example.test"}"""; next()\n'),
            ('val text = $$$"""$$$${/*x*/ "https://example.test"}"""; next()\n',
             'val text = $$$"""$$$${      "https://example.test"}"""; next()\n'),
        )
        for source, expected in cases:
            with self.subTest(source=source):
                self.assertEqual(expected, strip_comments(source))

    def test_模板表达式中的真实注释遮蔽(self):
        cases = (
            ('val text = "${/*x*/ "https://example.test"}"; next()\n',
             'val text = "${      "https://example.test"}"; next()\n'),
            ('val text = """${run { /*x*/ "https://example.test" }}"""; next()\n',
             'val text = """${run {       "https://example.test" }}"""; next()\n'),
            ('val text = "${run { //x\n 1 }}"; next()\n',
             'val text = "${run {    \n 1 }}"; next()\n'),
        )
        for source, expected in cases:
            with self.subTest(source=source):
                self.assertEqual(expected, strip_comments(source))


class GithubSourcesTest(unittest.TestCase):

    def testworkflow范围不全局扫描local_action(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            expected = [
                root / ".github/workflows/ci.yaml",
                root / ".github/workflows/release.yml",
            ]
            for path in [
                *expected,
                root / ".github/actions/setup-build/action.yml",
                root / ".github/workflows/说明.md",
                root / ".github/README.yml",
            ]:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("name: 测试\n", encoding="utf-8")
            self.assertEqual(sorted(expected), github_sources(root))

    def testlocal_action只解析仓内引用路径(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            expected = [
                (root / "tools/actions/build/action.yml").resolve(),
                (root / "tools/actions/build/action.yaml").resolve(),
            ]
            self.assertEqual(expected, local_action_candidates(root, "./tools/actions/build"))
            self.assertEqual(expected, local_action_candidates(root, "$/tools/actions/build"))
            with self.assertRaisesRegex(ValueError, "逃出仓库"):
                local_action_candidates(root, "./../other/action")
            with self.assertRaisesRegex(ValueError, "不是 local"):
                local_action_candidates(root, "${{ inputs.action }}")


FEATURE_SOURCE = "feature/sample/src/main/kotlin/Sample.kt"
DATA_SOURCE = "core/data/src/main/kotlin/Sample.kt"
GUARD_CASES = (
    (check_adaptive, FEATURE_SOURCE, "GridCells.Fixed(2)", "GridCells.Fixed",
     {"grid_allowed": {}, "text_allowed": {}, "escape_allowed": {}, "chunked_allowed": {}}, {}),
    (check_cached_network, FEATURE_SOURCE, "cachedNetworkCall(cache, fetch).flowOn(dispatcher)", "flowOn", {}, {}),
    (check_cancellation, DATA_SOURCE, "try { Unit } catch (e: CancellationException) {}", "接住了取消", {"allowed": {}, "report_dead": False}, {}),
    (check_motion, FEATURE_SOURCE, "spring<Float>()", "自己写的动画规格", {"expressive_allowed": {}}, {check_motion.OWNER: ""}),
    (check_color_anim, FEATURE_SOURCE, "animateColorAsState(Color.Transparent)", "Color.Transparent", {}, {}),
    (check_surface_roles, FEATURE_SOURCE, "Surface(color = colorScheme.surfaceContainerLowest) {}", "surfaceContainerLowest", {}, {}),
    (check_fabs, FEATURE_SOURCE, "FloatingActionButton(onClick = {}) {}", "直接用了 FloatingActionButton", {"allowed": {}},
     {"core/ui/src/main/FabPlacement.kt": "val FabClearance = 0\n"}),
    (check_separators, FEATURE_SOURCE, 'val label = "左·右"', "出现了分隔点", {}, {}),
    (check_login_gate, FEATURE_SOURCE, "if (!isLoggedIn) onLogin()", "手写了登录分支", {}, {}),
    (check_menus, FEATURE_SOURCE, "DropdownMenu(expanded = true, onDismissRequest = {}) {}", "直接用 DropdownMenu", {}, {}),
    (check_status_bar, FEATURE_SOURCE, "TopAppBarDefaults.pinnedScrollBehavior()", "pinnedScrollBehavior", {"allowed": {}, "yielding_allowed": {}},
     {check_status_bar.HEADER_BAR_HOME: "fun HeaderTopAppBar() {}\n",
      **{home: "fun LazyListScope.rows() { stickyHeader {} }\n" for home in check_status_bar.STICKY_HOMES}}),
    (check_text_fields, FEATURE_SOURCE, 'TextField(value = "", onValueChange = {})', "直接用 TextField", {"allowed": {}}, {}),
    (check_press_area, FEATURE_SOURCE, "Modifier.clip(shape).clickable {}", "clip + clickable", {"allowed": {}, "surface_allowed": {}}, {}),
    (check_display_name, FEATURE_SOURCE, "nameCn.ifBlank { name }", "手写了显示名规则", {"allowed": set()}, {}),
    (check_web_links, FEATURE_SOURCE, "Intent(Intent.ACTION_VIEW)", "自己起浏览器", {"allowed": set()}, {}),
    (check_clock, FEATURE_SOURCE, "System.currentTimeMillis()", "自己读了系统时间", {"allowed": {}}, {}),
    (check_timeline_category, FEATURE_SOURCE, "when (item.cat) { else -> Unit }", "拿 cat 比裸数字", {}, {}),
    (check_type_shape, FEATURE_SOURCE, "TextStyle(fontWeight = weight)", "自造字重", {}, {}),
    (check_viewmodel_rules, FEATURE_SOURCE, "try { Unit } catch (e: Exception) {}", "不写 catch", {}, {}),
    (check_cover_clip, FEATURE_SOURCE, "SubjectPoster(cover); Modifier.clip(shape)", "画了封面又自己裁切", {}, {}),
)


class KotlinGuardCommentsTest(unittest.TestCase):
    def violations(self, case, body: str, imports: str = "") -> list[str]:
        guard, relative, _, _, kwargs, support = case
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name, content in {**support, relative: "package sample\n" + imports + "\nfun sample() {\n" + body + "\n}\n"}.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")
            return guard.find_violations(root, **kwargs)

    def test_URL后同行违规由真实门禁拒绝(self):
        for case in GUARD_CASES:
            guard, relative, statement, hint, _, _ = case
            with self.subTest(guard=guard.__name__):
                found = self.violations(case, '    val site = "https://example.test"; ' + statement)
                self.assertEqual(1, len(found), f"{guard.__name__} 漏掉 URL 后的 {statement}: {found}")
                self.assertTrue(found[0].startswith(f"{relative}:4:"), found)
                self.assertIn(hint, found[0])

    def test_调用名与括号之间的注释不改变违规判定(self):
        for case in GUARD_CASES:
            guard, relative, statement, hint, _, _ = case
            with self.subTest(guard=guard.__name__):
                body = "    " + statement.replace("(", "/*x*/(")
                found = self.violations(case, body)
                self.assertEqual(1, len(found), f"{guard.__name__} 漏掉带注释的调用：{found}")
                self.assertTrue(found[0].startswith(f"{relative}:4:"), found)
                self.assertIn(hint, found[0])

    def test_反引号包住的名字仍由真实门禁拒绝(self):
        for case in GUARD_CASES:
            guard, relative, statement, hint, _, _ = case
            name = re.match(r"[A-Za-z_]\w*", statement).group()
            if name in {"try", "if", "when", "val"}:
                continue
            with self.subTest(guard=guard.__name__):
                found = self.violations(case, "    `" + name + "`" + statement[len(name):])
                self.assertEqual(1, len(found), f"{guard.__name__} 漏掉反引号包住的 {name}：{found}")
                self.assertIn(hint, found[0])

    def test_相邻调用的注释空白仍由真实门禁识别(self):
        adaptive = GUARD_CASES[0]
        fabs = next(case for case in GUARD_CASES if case[0] is check_fabs)
        cases = (
            (adaptive, "LazyVerticalGrid/*x*/(columns = cells) {}", "自己写了 LazyVerticalGrid", ""),
            (adaptive, "items.chunked/*x*/(2)", "自己切行定列数", "import androidx.compose.runtime.Composable\n"),
            (adaptive, "items.windowed/*x*/(2)", "自己切行定列数", "import androidx.compose.runtime.Composable\n"),
            (adaptive, "Modifier.requiredWidth/*x*/(width)", "越出了父级给的宽度", ""),
            (adaptive, 'ConnectedButtonGroup/*x*/({ Text("名称") })', "往 ConnectedButtonGroup 里直接画文字", ""),
            (adaptive, 'ConnectedButtonGroup({ Text/*x*/("名称") })', "往 ConnectedButtonGroup 里直接画文字", ""),
            (fabs, "SelectionFabMenu/*x*/(modifier = Modifier.padding(width))", "SelectionFabMenu 调用处自己定位", ""),
            (fabs, "SelectionFabMenu(modifier = Modifier.padding/*x*/(width))", "SelectionFabMenu 调用处自己定位", ""),
            (next(case for case in GUARD_CASES if case[0] is check_status_bar),
             "TopAppBarDefaults.topAppBarColors/*x*/(containerColor = Color.Transparent)", "自己拼了透明容器的顶栏", ""),
            (next(case for case in GUARD_CASES if case[0] is check_type_shape),
             "style.copy/*x*/(fontWeight = weight)", "自造字重", ""),
            (next(case for case in GUARD_CASES if case[0] is check_type_shape),
             'Text/*x*/("名称", fontWeight = weight)', "给 Text 单独传 fontWeight", ""),
            (next(case for case in GUARD_CASES if case[0] is check_cover_clip),
             "SubjectPoster(cover); Modifier.clipToBounds/*x*/()", "画了封面又自己裁切", ""),
        )
        for case, body, hint, imports in cases:
            with self.subTest(guard=case[0].__name__, body=body):
                found = self.violations(case, "    " + body, imports)
                self.assertEqual(1, len(found), f"注释空白不能改变 {body} 的判定：{found}")
                self.assertIn(hint, found[0])

    def test_嵌套调用中的注释空白不改变违规判定(self):
        clock = next(case for case in GUARD_CASES if case[0] is check_clock)
        category = next(case for case in GUARD_CASES if case[0] is check_timeline_category)
        status = next(case for case in GUARD_CASES if case[0] is check_status_bar)
        login = next(case for case in GUARD_CASES if case[0] is check_login_gate)
        type_shape = next(case for case in GUARD_CASES if case[0] is check_type_shape)
        cases = (
            (clock, "LocalDate.now/*x*/()", "自己读了系统时间"),
            (clock, "Date/*x*/()", "自己读了系统时间"),
            (category, "item.cat.equals/*x*/(1)", "拿 cat 比裸数字"),
            (category, "item.cat in setOf/*x*/(1, 2)", "拿 cat 比裸数字"),
            (next(case for case in GUARD_CASES if case[0] is check_display_name),
             "nameCn.unescapeHtmlEntities/*x*/().ifBlank { name }", "手写了显示名规则"),
            (next(case for case in GUARD_CASES if case[0] is check_viewmodel_rules),
             'failure.message?.contains/*x*/("404")', "不嗅探失败文本"),
            (login, "if (allowed) Unit else { onLogin/*x*/() }", "手写了登录分支"),
            (login, "failures.post/*x*/(R.string.example_login_required)", "用「请登录」提示代替了跳登录页"),
            (status, "TopAppBarDefaults.topAppBarColors(containerColor = color.copy/*x*/(alpha = 0f))", "自己拼了透明容器的顶栏"),
            (status, "lerp/*x*/(surface, surfaceContainer, amount)", "lerp 顶栏填色"),
            (type_shape, "RoundedCornerShape/*x*/(8.dp)", "圆角字面量等于 M3 阶梯"),
            (type_shape, "RoundedCornerShape(CornerSize/*x*/(8.dp))", "圆角字面量等于 M3 阶梯"),
            (type_shape, "val Corner = 8.dp; RoundedCornerShape/*x*/(Corner)", "圆角常量 Corner"),
        )
        for case, body, hint in cases:
            with self.subTest(guard=case[0].__name__, body=body):
                found = self.violations(case, "    " + body)
                self.assertEqual(1, len(found), f"注释空白不能改变 {body} 的判定：{found}")
                self.assertIn(hint, found[0])

    def test_合规调用中的注释空白不产生违规(self):
        case = next(case for case in GUARD_CASES if case[0] is check_status_bar)
        bodies = (
            "    WindowInsets.statusBars; HeaderTopAppBar/*x*/(fill = fraction)",
            "    stickyHeader {}; Modifier.stickyHeaderFade/*x*/(edge); overscrollEffect = edge.overscrollEffect",
        )
        for body in bodies:
            with self.subTest(body=body):
                self.assertEqual([], self.violations(case, body))

    def test_注释空白不改变背景裁切例外(self):
        case = next(case for case in GUARD_CASES if case[0] is check_press_area)
        body = "    Modifier.clip(shape).background/*x*/(color).clickable {}"
        self.assertEqual([], self.violations(case, body))

    def test_注释换行不截断按压修饰符链(self):
        case = next(case for case in GUARD_CASES if case[0] is check_press_area)
        body = "    Modifier.clip(shape).padding/*x\n*/(width).clickable {}"
        found = self.violations(case, body)
        self.assertEqual(1, len(found), found)
        self.assertTrue(found[0].startswith(f"{case[1]}:4:"), found)
        self.assertIn("clip + clickable", found[0])

    def test_字面量中的块标记不遮蔽后续代码(self):
        for case in GUARD_CASES:
            guard, relative, statement, hint, _, _ = case
            for quotes in ('"', '"""'):
                with self.subTest(guard=guard.__name__, quotes=quotes):
                    body = f'    val site = {quotes}https://example.test/*{quotes}; {statement}; val tail = {quotes}*/{quotes}'
                    found = self.violations(case, body)
                    self.assertEqual(1, len(found), f"{guard.__name__} 把字面量之间的 {statement} 当成注释: {found}")
                    self.assertTrue(found[0].startswith(f"{relative}:4:"), found)
                    self.assertIn(hint, found[0])

    def test_字面量后同行违规由真实门禁拒绝(self):
        prefixes = (
            r'val text = "\"https://example.test/*原样*/\"";',
            'val text = """https://example.test/*原样*/""";',
            '''val quote = '"'; val text = "https://example.test";''',
            'val text = "${"https://example.test/*原样*/"}";',
        )
        for case in GUARD_CASES:
            guard, relative, statement, hint, _, _ = case
            for prefix in prefixes:
                with self.subTest(guard=guard.__name__, prefix=prefix):
                    found = self.violations(case, f"    {prefix} {statement}")
                    self.assertEqual(1, len(found), f"{guard.__name__} 漏掉字面量后的 {statement}: {found}")
                    self.assertTrue(found[0].startswith(f"{relative}:4:"), found)
                    self.assertIn(hint, found[0])

    def test_注释中的违规不参与真实门禁(self):
        for case in GUARD_CASES:
            guard, _, statement, _, _, _ = case
            comments = (
                f'    // {statement} "说明"',
                f'    /* {statement} "说明" */',
                f'    /* 外层 /* 内层 */ {statement} "说明" */',
            )
            for comment in comments:
                with self.subTest(guard=guard.__name__, comment=comment):
                    self.assertEqual([], self.violations(case, comment))

    def test_只在原始字符串里提到违规代码不算(self):
        for case in GUARD_CASES:
            guard, _, statement, _, _, _ = case
            if guard.__name__ in {"check_separators", "check_web_links"}:
                continue
            with self.subTest(guard=guard.__name__):
                found = self.violations(case, f'    val text = """{statement}"""')
                self.assertEqual([], found, f"{guard.__name__} 把字符串内容当成了代码：{found}")

    def test_模板表达式里的违规代码仍由真实门禁拒绝(self):
        for case in GUARD_CASES:
            guard, relative, statement, hint, _, _ = case
            with self.subTest(guard=guard.__name__):
                found = self.violations(case, f'    val text = "${{run {{ {statement} }}}}"')
                self.assertEqual(1, len(found), f"{guard.__name__} 漏掉模板表达式里的代码：{found}")
                self.assertTrue(found[0].startswith(f"{relative}:4:"), found)
                self.assertIn(hint, found[0])

    def test_跨行嵌套注释后的诊断保持原行(self):
        for case in GUARD_CASES:
            guard, relative, statement, hint, _, _ = case
            with self.subTest(guard=guard.__name__):
                body = '    /* 外层\n       /* 内层 */\n       "说明" */\n    ' + statement
                found = self.violations(case, body)
                self.assertEqual(1, len(found), found)
                self.assertTrue(found[0].startswith(f"{relative}:7:"), found)
                self.assertIn(hint, found[0])



class KotlinAliasTest(unittest.TestCase):
    def write(self, files: dict[str, str]) -> list[Path]:
        root = Path(tempfile.mkdtemp())
        paths = []
        for rel, text in files.items():
            path = root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
            paths.append(path)
        return paths

    def test_导入别名只在代码里换回原名且行号不变(self):
        source = (
            "import sample.Image as Picture\n"
            "// Picture in a comment\n"
            "val a = Picture(\"Picture\")\n"
            "val b = \"${Picture()} $Picture\"\n"
        )
        normalized = normalize_kotlin(source)
        self.assertEqual(source.count("\n"), normalized.count("\n"))
        lines = normalized.split("\n")
        self.assertEqual("import sample.Image as Image", lines[0])
        self.assertNotIn("Picture", lines[1])
        self.assertEqual('val a = Image("Picture")', lines[2])
        self.assertEqual('val b = "${Image()} $Image"', lines[3])

    def test_更长的名字和成员名不被半截替换(self):
        normalized = normalize_kotlin("import sample.Image as Pic\nval p = Picture(Pic.size, x.Pic2)\n")
        self.assertIn("Picture(Image.size, x.Pic2)", normalized)

    def test_没有别名时与去注释结果相同(self):
        source = "val a = 1 // note\n"
        self.assertEqual(strip_comments(source), normalize_kotlin(source))

    def test_类型别名跨文件沿别名链走到底(self):
        paths = self.write({
            "a/A.kt": "typealias Cells = androidx.compose.foundation.lazy.grid.GridCells\n",
            "b/B.kt": "internal typealias C2 = Cells\n/* typealias Hidden = Gone */\n",
        })
        self.assertEqual({"Cells": "GridCells", "C2": "GridCells"}, kotlin_typealiases(paths))

    def test_同名别名指向不同目标或成环时不收录(self):
        paths = self.write({
            "a/A.kt": "typealias Box = sample.One\ntypealias Loop1 = Loop2\n",
            "b/B.kt": "typealias Box = sample.Two\ntypealias Loop2 = Loop1\n",
        })
        self.assertEqual({}, kotlin_typealiases(paths))

    def test_文件自己的导入别名优先于类型别名(self):
        normalized = normalize_kotlin("import sample.Real as Cells\nval c = Cells()\n", {"Cells": "GridCells"})
        self.assertIn("val c = Real()", normalized)

    def test_反引号别名分号后的导入与带修饰的类型别名都认得(self):
        source = "import a.TextField as `Field`; import b.Menu as M\nval x = `Field`(M())\n"
        self.assertIn("val x =   TextField(Menu())", normalize_kotlin(source))
        paths = self.write({
            "a/A.kt": "actual typealias F1 = sample.TextField\n@Suppress(\"x\") internal typealias `F2` = sample.Menu\n",
        })
        self.assertEqual({"F1": "TextField", "F2": "Menu"}, kotlin_typealiases(paths))

    def test_带空格的反引号名字原样保留(self):
        source = "fun `a test`() = `when`\n"
        self.assertEqual("fun `a test`() =   when\n", normalize_kotlin(source))

    def test_代码视图遮蔽字符串但保留模板表达式(self):
        source = 'val text = "spring() Color.Transparent"; val code = "${spring()}"\n'
        code = normalize_code(source)
        self.assertNotIn("Color.Transparent", code)
        self.assertEqual(code.count("spring"), 1, code)


if __name__ == "__main__":
    unittest.main()
