"""动画颜色门禁（唯一实现处；tools/ci/check.sh lint 运行，单跑：python3 tools/guards/check_color_anim.py）。

颜色插值的一端是 `Color.Transparent`（透明黑）时，插值要经过深灰：浅色主题下容器先发灰再变透明（docs/code-standards.md「按属性选动效」）。
从无到有的颜色用 `目标色.copy(alpha = 0f)`，和邻态同一色系。

检查项：颜色插值的调用——`animateColorAsState(…)`、`Transition.animateColor(…) { … }`（含尾随 lambda）、
`Animatable(…)`、`animateTo(…)` / `snapTo(…)`、`lerp(…)`——的参数里不得出现透明黑（`Color.Transparent`、`Color(0x00000000)`、
`Color.Black.copy(alpha = 0f)`），也不得传入一个最近一次声明是用透明黑初始化的 val / var（初始化式可以跨行）。
静态的渐变与背景（`Brush.verticalGradient(…)`、`background(…)`）不插值，不拦。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

from sources import call_end, kotlin_typealiases, main_sources, normalize_code

CALL = re.compile(r"\b(?:animateColorAsState|animateColor|Animatable|animateTo|snapTo|lerp)\s*\(")
TRANSPARENT = re.compile(
    r"\bColor\s*\.\s*Transparent\b"
    r"|\bColor\s*\(\s*0x00000000L?\s*\)"
    r"|\bColor\s*\.\s*Black\s*\.\s*copy\s*\(\s*(?:alpha\s*=\s*)?0(?:\.0*)?[fF]?\s*\)"
)
DECLARATION = re.compile(r"\b(?:val|var)\s+(\w+)\s*(?::[^=\n]+)?=(?!=)")
IDENTIFIER = re.compile(r"(?<![\w$.])[A-Za-z_]\w*")
# 下一行接着上一行的表达式：以这些开头，或上一行以这些结尾
CONTINUES_NEXT = re.compile(r"^\s*(?:else\b|\?\.|\?:|\.|&&|\|\||[+*/])")
CONTINUED_BY = re.compile(r"(?:[=(\[{,+\-*/:.&|]|\belse|->)\s*$")


def indent(line: str) -> int:
    return len(line) - len(line.lstrip())


def initializer(text: str, start: int) -> str:
    """`=` 之后到这条声明结束：括号配对，续行比声明行缩进更深、或以运算符 / else 接上。"""
    line_start = text.rfind("\n", 0, start) + 1
    base = indent(text[line_start:start])
    depth = 0
    i = start
    while i < len(text):
        c = text[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            if depth == 0:
                break
            depth -= 1
        elif c == ";" and depth == 0:
            break
        elif c == "\n" and depth == 0:
            next_end = text.find("\n", i + 1)
            following = text[i + 1:next_end if next_end >= 0 else len(text)]
            continued = (
                CONTINUED_BY.search(text[start:i]) is not None
                or CONTINUES_NEXT.match(following) is not None
                or (following.strip() != "" and indent(following) > base)
            )
            if not continued:
                break
        i += 1
    return text[start:i]


def declarations(text: str) -> dict[str, list[tuple[int, bool]]]:
    """每个名字的声明（位置, 是否用透明黑初始化）；函数参数 `name: Type` 也算一次声明（不透明）。"""
    found: dict[str, list[tuple[int, bool]]] = {}
    declared_at: set[int] = set()
    for m in DECLARATION.finditer(text):
        found.setdefault(m.group(1), []).append((m.start(), TRANSPARENT.search(initializer(text, m.end())) is not None))
        declared_at.add(m.start(1))
    for name in list(found):
        for m in re.finditer(rf"\b{name}\s*:(?!:)", text):
            if m.start() not in declared_at:
                found[name].append((m.start(), False))
    return found


def transparent_name(text: str, span: str, at: int, decls: dict[str, list[tuple[int, bool]]]) -> bool:
    """span 里有没有一个名字，它在 at 之前最近的一次声明是用透明黑初始化的。"""
    for m in IDENTIFIER.finditer(span):
        before = [decl for decl in decls.get(m.group(), []) if decl[0] < at]
        if before and max(before)[1]:
            return True
    return False


def interpolated(text: str, open_index: int) -> str:
    """调用的参数，连同紧跟其后的尾随 lambda（Transition.animateColor 的目标写在那里）。"""
    end = call_end(text, open_index)
    span = text[open_index + 1:end]
    trailing = re.compile(r"\s*\{").match(text, end + 1)
    if trailing:
        depth = 0
        for i in range(trailing.end() - 1, len(text)):
            if text[i] == "{":
                depth += 1
            elif text[i] == "}":
                depth -= 1
                if depth == 0:
                    span += "," + text[trailing.end():i]
                    break
    return span


def find_violations(root: Path) -> list[str]:
    violations: list[str] = []
    paths = main_sources(root)
    typealiases = kotlin_typealiases(paths)
    for path in paths:
        text = normalize_code(path.read_text(encoding="utf-8"), typealiases)
        decls = declarations(text)
        for call in CALL.finditer(text):
            span = interpolated(text, call.end() - 1)
            if TRANSPARENT.search(span) or transparent_name(text, span, call.start(), decls):
                line = text.count("\n", 0, call.start()) + 1
                violations.append(
                    f"{path.relative_to(root).as_posix()}:{line}: 颜色插值的一端是透明黑（Color.Transparent）"
                    "——插值会经过深灰；改成邻态颜色的 .copy(alpha = 0f)"
                )
    return violations


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    violations = find_violations(root)
    for v in violations:
        print(v, file=sys.stderr)
    if violations:
        print(f"\n动画颜色门禁未通过：{len(violations)} 处", file=sys.stderr)
        return 1
    print("动画颜色门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
