"""未使用 import 门禁的自测：每条规矩一个样例（tools/ci/check.sh lint 运行）。"""

import unittest

from check_unused_imports import unused_imports


class CheckUnusedImportsTest(unittest.TestCase):

    def test_用到的放行(self):
        text = "package a\n\nimport x.Foo\nimport x.bar\n\nfun f() = bar(Foo())\n"
        self.assertEqual([], unused_imports(text))

    def test_没用到的拦下并报行号(self):
        text = "package a\n\nimport x.Foo\nimport x.Unused\n\nfun f() = Foo()\n"
        self.assertEqual([(4, "import x.Unused")], unused_imports(text))

    def test_别名按别名判断(self):
        text = "import x.Foo as Bar\nimport x.Baz as Qux\n\nfun f() = Bar()\n"
        self.assertEqual([(2, "import x.Baz as Qux")], unused_imports(text))

    def test_导入行带行尾注释仍检查(self):
        text = "package p\nimport x.Unused // 行尾说明\nfun f() = Unit\n"
        self.assertEqual([(2, "import x.Unused // 行尾说明")], unused_imports(text))

    def test_KDoc引用算用到(self):
        text = "import x.Foo\n\n/** 见 [Foo]。 */\nfun f() {}\n"
        self.assertEqual([], unused_imports(text))

    def test_名字只是更长标识符的一部分不算用到(self):
        text = "import x.Foo\n\nfun f() = FooBar()\n"
        self.assertEqual([(1, "import x.Foo")], unused_imports(text))

    def test_运算符与委托约定放行(self):
        text = "import x.getValue\nimport x.plus\nimport x.component1\n\nval a by lazy { 1 }\n"
        self.assertEqual([], unused_imports(text))

    def test_通配导入拦下(self):
        text = "package a\n\nimport x.*\n\nfun f() = Foo()\n"
        self.assertEqual([(3, "import x.*")], unused_imports(text))

    def test_只在注释里出现不算用到(self):
        text = "import x.Foo\n\n// 这里提到 Foo\n/* Foo 也不行 */\nfun f() {}\n"
        self.assertEqual([(1, "import x.Foo")], unused_imports(text))

    def test_只在字符串里出现不算用到(self):
        text = 'import x.Foo\n\nval s = "Foo"\nval t = """Foo"""\n'
        self.assertEqual([(1, "import x.Foo")], unused_imports(text))

    def test_字符串模板里的名字算用到(self):
        text = 'import x.foo\nimport x.Bar\n\nval s = "$foo ${Bar.x}"\n'
        self.assertEqual([], unused_imports(text))

    def test_同包导入拦下(self):
        text = "package a.b\n\nimport a.b.Foo\nimport a.b.Foo as Alias\n\nfun f() = Foo() to Alias()\n"
        self.assertEqual([(3, "import a.b.Foo")], unused_imports(text))

    def test_URL和原始字符串的文字不算用到(self):
        text = (
            "import x.Foo\n\n"
            'val url = "https://example.test/Foo//path"\n'
            'val raw = """Foo /* 仍是文字 */"""\n'
        )
        self.assertEqual([(1, "import x.Foo")], unused_imports(text))

    def test_嵌套模板表达式和反引号模板名称算用到(self):
        text = (
            "import x.Foo\n"
            "import x.`when`\n\n"
            'val nested = "${run { "${Foo()}" }}"\n'
            'val named = "$`when`"\n'
        )
        self.assertEqual([], unused_imports(text))

    def test_分号结尾_缩进_同一行两条导入仍逐条检查(self):
        self.assertEqual([(2, "import x.Unused;")], unused_imports("package a\nimport x.Unused;\nfun f() = 1\n"))
        self.assertEqual([(2, "  import x.Unused")], unused_imports("package a\n  import x.Unused\nfun f() = 1\n"))
        self.assertEqual(
            [(2, "import x.A; import x.Unused")],
            unused_imports("package a\nimport x.A; import x.Unused\nfun f() = A()\n"),
        )

    def test_反引号别名按别名判断(self):
        text = "import x.Foo as `Bar`\nimport x.Baz as `Qux`\n\nfun f() = `Bar`()\n"
        self.assertEqual([(2, "import x.Baz as `Qux`")], unused_imports(text))

    def test_原始字符串里像导入的行不是导入(self):
        text = 'package a\n\nval s = """\nimport x.Foo\n"""\n'
        self.assertEqual([], unused_imports(text))

    def test_KDoc链接保留但普通说明遮蔽(self):
        text = (
            "import x.Foo\n"
            "import x.Bar\n\n"
            "/** Foo 只是说明；[Bar.member] 是链接。 */\n"
            "fun f() {}\n"
        )
        self.assertEqual([(1, "import x.Foo")], unused_imports(text))


if __name__ == "__main__":
    unittest.main()
