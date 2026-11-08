"""登录门门禁的自测：每条规矩一个样例（tools/ci/check.sh lint 运行）。"""

from pathlib import Path
import tempfile
import unittest

from check_login_gate import find_violations, unused_allowances

GATE = "core/ui/src/main/LoginGate.kt"
SIGN_IN = "feature/login/src/main/LoginScreen.kt"
PROMPT = "feature/login/src/main/SignInPrompt.kt"


class CheckLoginGateTest(unittest.TestCase):

    def violations(self, files: dict[str, str]) -> list[str]:
        root = Path(tempfile.mkdtemp())
        gate = 'class LoginGate { fun run(action: () -> Unit) { if (isLoggedIn()) action() else onLogin() } }\n'
        sign_in = 'fun LoginScreen() {\n    Button(onClick = login) { Text(stringResource(R.string.login_bangumi)) }\n}\n'
        prompt = 'fun SignInPrompt() {\n    Button(onClick = onLogin) { Text(stringResource(R.string.login_bangumi)) }\n}\n'
        for rel, text in {GATE: gate, SIGN_IN: sign_in, PROMPT: prompt, **files}.items():
            path = root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        return find_violations(root, GATE, (SIGN_IN, PROMPT))

    def test_经登录门与按登录态选页放行(self):
        page = (
            'fun Page() {\n'
            '    val gate = rememberLoginGate(isLoggedIn, onLogin)\n'
            '    Button(onClick = { gate.run { collect() } })\n'
            '}\n'
        )
        entries = 'entry<Route.Home> {\n    if (isLoggedIn) HomeScreen() else signIn(R.string.home, R.string.sign_in_prompt_home)\n}\n'
        self.assertEqual([], self.violations({"feature/a/src/main/Page.kt": page, "app/src/main/Entries.kt": entries}))

    def test_页面自己画登录入口拦下(self):
        page = 'fun Page() {\n    if (!isLoggedIn) {\n        Button(onClick = onLogin) { Text(stringResource(R.string.login_bangumi)) }\n    }\n}\n'
        found = self.violations({"core/ui/src/main/Guest.kt": page})
        self.assertEqual(1, len(found))
        self.assertIn("core/ui/src/main/Guest.kt:3", found[0])
        self.assertIn("LoginScreen", found[0])

    def test_else_onLogin拦下(self):
        found = self.violations({"feature/a/src/main/Page.kt": 'val onClick = if (isLoggedIn) onToggle else onLogin\n'})
        self.assertEqual(1, len(found))
        self.assertIn("feature/a/src/main/Page.kt:1", found[0])
        self.assertIn("LoginGate", found[0])

    def test_else块里调onLogin拦下(self):
        page = 'fun f() {\n    if (isLoggedIn) {\n        open()\n    } else {\n        onLogin()\n    }\n}\n'
        self.assertEqual(1, len(self.violations({"feature/a/src/main/Page.kt": page})))

    def test_未登录直接调onLogin拦下(self):
        page = 'fun f() {\n    if (!isLoggedIn) onLogin() else open()\n}\n'
        self.assertEqual(1, len(self.violations({"feature/a/src/main/Page.kt": page})))

    def test_登录分支的改写也拦下(self):
        for page in (
            "fun f() {\n    if (!isLoggedIn) return onLogin()\n    open()\n}\n",
            "fun f() {\n    if (!state.isLoggedIn) onLogin() else open()\n}\n",
            "fun f() {\n    if (isLoggedIn.not()) onLogin()\n}\n",
            "fun f() {\n    if (state.isLoggedIn == false) {\n        onLogin()\n        return\n    }\n}\n",
            "fun f() = when {\n    isLoggedIn -> open()\n    else -> onLogin()\n}\n",
            "val onClick = if (!isLoggedIn) onLogin else onToggle\n",
            "fun f() {\n    if (isLoggedIn) open() else {\n        onLogin.invoke()\n    }\n}\n",
        ):
            with self.subTest(page=page):
                found = self.violations({"feature/a/src/main/Page.kt": page})
                self.assertEqual(1, len(found), found)
                self.assertIn("LoginGate", found[0])

    def test_按登录态选内容不是登录门_放行(self):
        page = (
            "val shown = if (isLoggedIn) scope else FeedScope.ALL\n"
            "val c = when {\n    loading -> Skeleton()\n    else -> PmConversationScreen(key, isLoggedIn, onLogin, onBack)\n}\n"
            "val d = if (!isLoggedIn) emptyList() else items\n"
        )
        self.assertEqual([], self.violations({"feature/a/src/main/Page.kt": page}))

    def test_请登录提示的改写也拦下(self):
        for vm in (
            "fun f() {\n    failures.post(io.github.touhougo.chogumi.core.res.R.string.react_login_required)\n}\n",
            "fun f() {\n    actions.failures?.post(R.string.react_login_required)\n}\n",
            "import io.github.touhougo.chogumi.core.res.R as Res\nfun f() {\n    failures.post(Res.string.react_login_required)\n}\n",
        ):
            with self.subTest(vm=vm):
                found = self.violations({"feature/a/src/main/Vm.kt": vm})
                self.assertEqual(1, len(found), found)
                self.assertIn("提示代替", found[0])

    def test_请登录提示拦下(self):
        vm = 'fun react() {\n    failures.post(R.string.floor_react_login_required)\n}\n'
        found = self.violations({"core/reader/src/main/Vm.kt": vm})
        self.assertEqual(1, len(found))
        self.assertIn("提示代替", found[0])

    def test_放行文件不在或用不上了报出来(self):
        root = Path(tempfile.mkdtemp())
        path = root / GATE
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("class LoginGate(val run: () -> Unit)\n", encoding="utf-8")
        found = unused_allowances(root, GATE, (SIGN_IN, PROMPT))
        self.assertEqual([GATE, SIGN_IN, PROMPT], [v.split(": ")[0] for v in found])
        self.assertTrue(all("放行" in v for v in found), found)

    def test_放行文件都用得上时不报(self):
        root = Path(tempfile.mkdtemp())
        for rel, text in {
            GATE: "class LoginGate { fun run(action: () -> Unit) { if (isLoggedIn()) action() else onLogin() } }\n",
            SIGN_IN: "val a = R.string.login_bangumi\n",
            PROMPT: "val b = R.string.login_bangumi\n",
        }.items():
            (root / rel).parent.mkdir(parents=True, exist_ok=True)
            (root / rel).write_text(text, encoding="utf-8")
        self.assertEqual([], unused_allowances(root, GATE, (SIGN_IN, PROMPT)))

    def test_注释里的不拦(self):
        page = '// 原先写 if (isLoggedIn) x else onLogin\nfun f() = Unit\n'
        self.assertEqual([], self.violations({"feature/a/src/main/Page.kt": page}))


if __name__ == "__main__":
    unittest.main()
