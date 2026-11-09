"""固有尺寸门禁（唯一实现处；tools/ci/check.sh lint 运行，单跑：python3 tools/guards/check_intrinsic_size.py）。

规矩见 docs/dev/guide.md §2.3：富文本外层不许查固有尺寸。富文本组件里有 SubcomposeLayout（FoldableText 的
「超出才折叠」、BBCodeImages 的 BoxWithConstraints），SubcomposeLayout 一被问固有尺寸就抛
「Asking for intrinsic measurements of SubcomposeLayout layouts is not supported」；
泛型 layout modifier 的默认固有尺寸实现还会把测量回调当探测来调，折叠判定每帧翻转。

检查项：
1. IntrinsicSize 只许出现在白名单函数里（按「仓库路径, 函数名」列，写明里面为什么没有富文本）。函数指包住它的最外层
   具名函数（局部函数算进外层），函数体按括号配对到它自己的右括号为止。
2. 白名单函数里调用了 core:reader 声明的任何 @Composable（富文本都在那里），拦下。
3. 白名单里匹配不到的条目算死配置。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

from sources import call_end, kotlin_typealiases, main_sources, normalize_code

# 允许查固有尺寸的函数（仓库路径, 函数名）：里面只有纯文字与色块，没有 core:reader 的组件。
INTRINSIC_ALLOWED = {
    ("feature/home/src/main/kotlin/io/github/touhougo/chogumi/feature/home/HomeSchedule.kt", "ScheduleDayColumn"):
        "追更时间表的一天：列宽取日期行与封面行中较宽者，里面只有日期与封面",
    ("feature/explore/src/main/kotlin/io/github/touhougo/chogumi/feature/explore/ExploreScreen.kt", "ExploreEntriesRow"):
        "探索页固定入口行：各格等高取最高者，里面只有图标与标签文字",
    ("core/ui/src/main/kotlin/io/github/touhougo/chogumi/core/ui/tour/FloorDemo.kt", "DemoFloorMenu"):
        "手势引导里长按菜单的示意：与弹出菜单一样按最宽的一组定宽，里面只有表情色块与菜单项",
}

INTRINSIC = re.compile(r"\bIntrinsicSize\s*\.")
MODIFIER = r"(?:private|internal|public|protected|inline|override|open|actual|suspend|operator|infix|tailrec)"
COMPOSABLE_DECL = re.compile(
    r"@Composable\b(?:\s*@\w+(?:\([^)]*\))?)*\s+(?:" + MODIFIER + r"\s+)*fun\s+(?:<[^>]*>\s+)?(?:[\w.]+\.)?(\w+)\s*\(",
)
FUN_DECL = re.compile(r"\bfun\s+(?:<[^>]*>\s+)?(?:[\w.]+\.)?(\w+)\s*\(")
BODY_START = re.compile(r"[{=]")
READER = "core/reader/src/main/"


def matching_brace(text: str, open_index: int) -> int:
    depth = 0
    for i in range(open_index, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return i
    return len(text)


def expression_end(text: str, start: int, base_indent: int) -> int:
    """表达式函数体到下一行缩进不深于声明行、且括号已配对的位置。"""
    depth = 0
    for i in range(start, len(text)):
        c = text[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == "\n" and depth <= 0:
            following = text[i + 1:text.find("\n", i + 1) if text.find("\n", i + 1) >= 0 else len(text)]
            if following.strip() and len(following) - len(following.lstrip()) <= base_indent:
                return i
    return len(text)


def functions(text: str) -> list[tuple[str, int, int]]:
    """文件里每个具名函数的（名字, 声明起点, 函数体终点）。"""
    found = []
    for m in FUN_DECL.finditer(text):
        params_end = call_end(text, m.end() - 1)
        body = BODY_START.search(text, params_end + 1)
        if body is None:
            continue
        if body.group() == "{":
            end = matching_brace(text, body.start())
        else:
            line_start = text.rfind("\n", 0, m.start()) + 1
            end = expression_end(text, body.end(), m.start() - line_start)
        found.append((m.group(1), m.start(), end))
    return found


def outermost_function(spans: list[tuple[str, int, int]], index: int) -> tuple[str, int, int] | None:
    containing = [span for span in spans if span[1] <= index < span[2]]
    return min(containing, key=lambda span: span[1]) if containing else None


def find_violations(root: Path, allowed: dict[tuple[str, str], str] = INTRINSIC_ALLOWED) -> list[str]:
    violations: list[str] = []
    used: set[tuple[str, str]] = set()
    paths = main_sources(root)
    typealiases = kotlin_typealiases(paths)
    texts = {path: normalize_code(path.read_text(encoding="utf-8"), typealiases) for path in paths}
    rich = {
        m.group(1)
        for path, text in texts.items()
        if path.relative_to(root).as_posix().startswith(READER)
        for m in COMPOSABLE_DECL.finditer(text)
    }
    for path, text in texts.items():
        rel = path.relative_to(root).as_posix()
        spans = functions(text)
        for match in INTRINSIC.finditer(text):
            line = text.count("\n", 0, match.start()) + 1
            function = outermost_function(spans, match.start())
            name = function[0] if function else None
            key = (rel, name or "?")
            if key not in allowed:
                violations.append(
                    f"{rel}:{line}: {name or '函数外'} 查了固有尺寸（IntrinsicSize）——富文本外层不许查（docs/dev/guide.md §2.3）；"
                    "确认里面没有富文本才可加进 tools/guards/check_intrinsic_size.py 白名单"
                )
                continue
            if key in used or function is None:
                continue
            used.add(key)
            body = text[function[1]:function[2]]
            calls = sorted(n for n in rich if n != name and re.search(rf"\b{n}\s*\(", body))
            if calls:
                violations.append(
                    f"{rel}:{line}: 白名单函数 {name} 查固有尺寸，里面却调用了 core:reader 的 {', '.join(calls)}"
                    "——富文本外层不许查固有尺寸"
                )
    for key in sorted(set(allowed) - used):
        violations.append(f"白名单条目 {key[0]}::{key[1]} 已匹配不到 IntrinsicSize，删掉这条（死配置）")
    return violations


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    violations = find_violations(root)
    for v in violations:
        print(v, file=sys.stderr)
    if violations:
        print(f"\n固有尺寸门禁未通过：{len(violations)} 处（规矩见 docs/dev/guide.md §2.3）", file=sys.stderr)
        return 1
    print("固有尺寸门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
