"""登录门门禁（唯一实现处；tools/ci/check.sh lint 运行，单跑：python3 tools/guards/check_login_gate.py）。

规矩见 docs/code-standards.md「ViewModel 状态与失败提示」：未登录点写操作（收藏、标进度、贴贴、关注、绝交、报告、发动态……）
去登录页，一律经 core:ui 的 `LoginGate`（`rememberLoginGate(isLoggedIn, onLogin)`）。共用组件的写操作参数是 `Gated`，
漏门由编译器拦；这道门禁拦的是页面里手写的登录分支和「请登录」提示——它们各写各的，迟早与登录门分叉。

未登录的界面也各只有一种，都在 feature:login：登录表单只在登录页 `LoginScreen`（独立的登录路由，可以返回）；
没有游客可看内容的整页（未登录时的首页、进度、「我的」tab 与「我的帖子」）由 `appEntries` 换成 `SignInPrompt`，
一行说明加一个打开登录页的按钮。页面自己不画「请先登录」——各画各的，按钮尺寸、字号、间距就会走样。

检查项（主源码 app / core / feature 的 src/main 下 .kt，去掉注释后）：
1. 不得手写登录分支：`else onLogin`、`else { onLogin() }`、`when` 的 `else -> onLogin()`；条件是未登录
   （`!isLoggedIn`、`!state.isLoggedIn`、`isLoggedIn == false`、`isLoggedIn.not()`）的 `if` 紧接着
   `onLogin` / `{ onLogin` / `return onLogin`。
   `appEntries` 按登录态选页（`if (isLoggedIn) HomeScreen(…) else signIn(…)`）不是写操作的门，不拦。
2. 不得用提示代替跳登录：`post(R.string.xxx_login_required)`（任何接收者，`R` 可限定）。
3. 登录入口（`R.string.login_bangumi`）只画在 LoginScreen.kt 与 SignInPrompt.kt：别处出现就是又画了一个未登录界面。
4. 唯一放行 LoginGate.kt 本身（第 1、2 条）与上面两个文件（第 3 条）；放行的文件不在了、或里面已经没有被放行的写法，
   这条放行也报出来。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

from sources import kotlin_typealiases, main_sources, normalize_code

GATE = "core/ui/src/main/kotlin/io/github/touhougo/chogumi/core/ui/common/LoginGate.kt"
SIGN_IN = (
    "feature/login/src/main/kotlin/io/github/touhougo/chogumi/feature/login/LoginScreen.kt",
    "feature/login/src/main/kotlin/io/github/touhougo/chogumi/feature/login/SignInPrompt.kt",
)

LOGIN_STATE = r"(?:\w+\s*\??\s*\.\s*)*isLoggedIn"
SIGNED_OUT = r"(?:!\s*" + LOGIN_STATE + r"|" + LOGIN_STATE + r"\s*(?:==\s*false|\.\s*not\s*\(\s*\)))"
RULES = [
    (re.compile(r"\belse\b\s*(?:->\s*)?(?:\{\s*)?onLogin\b"), "手写了登录分支（else onLogin）"),
    (re.compile(r"\bif\s*\(\s*" + SIGNED_OUT + r"\s*\)\s*(?:\{\s*)?(?:return(?:@\w+)?\s+)?onLogin\b"),
     "手写了登录分支（if (!isLoggedIn) onLogin()）"),
    (re.compile(r"\bpost\s*\(\s*(?:\w+\s*\.\s*)*R\s*\.\s*string\s*\.\s*\w*login_required\b"), "用「请登录」提示代替了跳登录页"),
]
HINT = "写操作的登录门只有 LoginGate 一种：页面里 val gate = rememberLoginGate(isLoggedIn, onLogin)，再 gate.run { … } 或 gate.guard(…)"
SIGN_IN_RULE = re.compile(r"\bR\.string\.login_bangumi\b")
SIGN_IN_HINT = (
    "未登录的界面只在 feature:login：登录表单只在 LoginScreen（登录路由），需要登录的整页由 appEntries 在未登录时换成 SignInPrompt，"
    "写操作经 LoginGate 跳登录路由"
)


def find_violations(root: Path, gate: str = GATE, sign_in: tuple[str, ...] = SIGN_IN) -> list[str]:
    violations: list[str] = []
    paths = main_sources(root)
    typealiases = kotlin_typealiases(paths)
    for path in paths:
        rel = path.relative_to(root).as_posix()
        text = normalize_code(path.read_text(encoding="utf-8"), typealiases)
        if rel != gate:
            for pattern, what in RULES:
                for m in pattern.finditer(text):
                    violations.append(f"{rel}:{line_of(text, m)}: {what}——{HINT}")
        if rel not in sign_in:
            for m in SIGN_IN_RULE.finditer(text):
                violations.append(f"{rel}:{line_of(text, m)}: 另画了一个登录入口——{SIGN_IN_HINT}")
    return violations


def unused_allowances(root: Path, gate: str = GATE, sign_in: tuple[str, ...] = SIGN_IN) -> list[str]:
    """放行的文件不在了、或里面已没有它被放行的那种写法。"""
    stale: list[str] = []
    for rel, patterns in ((gate, [pattern for pattern, _ in RULES]), *((rel, [SIGN_IN_RULE]) for rel in sign_in)):
        path = root / rel
        text = normalize_code(path.read_text(encoding="utf-8")) if path.is_file() else ""
        if not any(pattern.search(text) for pattern in patterns):
            stale.append(f"{rel}: 放行用不上了（文件不在或已没有被放行的写法）——从放行里删掉")
    return stale


def line_of(text: str, m: re.Match[str]) -> int:
    return text[: m.start()].count("\n") + 1


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    violations = find_violations(root) + unused_allowances(root)
    for v in violations:
        print(v, file=sys.stderr)
    if violations:
        print(f"\n登录门门禁未通过：{len(violations)} 处（规则见 core:ui LoginGate.kt）", file=sys.stderr)
        return 1
    print("登录门门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
