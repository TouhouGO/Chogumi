"""FAB 位置门禁（唯一实现处；tools/ci/check.sh lint 运行，单跑：python3 tools/guards/check_fabs.py）。

规则见 core:ui FabPlacement.kt：全 App FAB 在右下角、距尾边 / 底边 16dp（M3 fabs.md），只有两种放法——
Scaffold 的 floatingActionButton 槽位（Scaffold 留 16dp、避让系统栏），或 SelectionFabMenu（自己贴右下角）。
调用处自己摆就会一页一个位置：菜单 FAB 调用处再加 16dp、作品详情「在看」再补一次导航条高度。

检查项：
1. floatingActionButton = { … } 槽位里不得出现 padding / offset / align / 系统栏与窗口内边距（位置是 Scaffold 的事）。
2. SelectionFabMenu(…) 调用处不得传 align / padding / offset（位置在组件里定）。
3. FAB 组件只许出现在 floatingActionButton 槽位里，或白名单里的 FAB 组件定义处（按「文件::函数」列）。
4. FAB 让出的列表留白全 App 只许定义一次（core:ui 的 FabClearance）。
5. 白名单里匹配不到的条目算死配置。
6. FAB 不随滚动变形：扩展 FAB（含 Expressive 的小 / 中 / 大扩展 FAB）不得传 expanded（收成图标 / 展开靠滚动方向切换）。
   要文字就一直是扩展 FAB，不要文字就是只有图标的 FloatingActionButton，尺寸固定。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

from sources import enclosing_function, kotlin_typealiases, main_sources, normalize_code

# 定义 FAB 组件的地方（文件名, 函数名）：它们被放进 Scaffold 槽位或自己负责位置
FAB_COMPONENT_ALLOWED = {
    ("ScrollToTopFab.kt", "ScrollToTopFab"): "回顶 FAB，放进 Scaffold 槽位用",
    ("SelectionFabMenu.kt", "SelectionFabMenu"): "选项菜单 FAB，自己贴右下角",
    ("SubjectDetailsScreen.kt", "CollectionFab"): "作品详情收藏扩展 FAB，放进 Scaffold 槽位用",
    ("ReplyComposer.kt", "ReplyFab"): "话题与评论区的「回复」FAB，放进 Scaffold 槽位用",
}

# M3 的 FAB 与 M3 Expressive 的变体（小 / 中 / 大的扩展 FAB、可切换的 FAB、FAB 菜单）
FAB_CALL = re.compile(
    r"\b(FloatingActionButton|(?:Small|Medium|Large)?ExtendedFloatingActionButton|SmallFloatingActionButton|"
    r"MediumFloatingActionButton|LargeFloatingActionButton|ToggleFloatingActionButton|FloatingActionButtonMenu)\s*\("
)
SLOT = re.compile(r"\bfloatingActionButton\s*=\s*\{")
MENU_CALL = re.compile(r"\bSelectionFabMenu\s*\(")
# 定位修饰符：内边距（含 absolutePadding 与各种系统栏 / 窗口内边距）、偏移（含 lambda 版 offset { }）、对齐
POSITIONING = re.compile(r"\.\s*(padding|absolutePadding|\w+Padding|offset|absoluteOffset|align)\s*[({]")
# 紧挨着名字前面的 `fun`（可带泛型与接收者）：这是声明，不是调用
DECLARATION_PREFIX = re.compile(r"\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+(?:<[^>()]*>)?\??\.)?\Z")
EXPANDED_ARG = re.compile(r"\bexpanded\s*=")
CLEARANCE_DEF = re.compile(r"\bval\s+(\w*fab\w*clearance\w*)\s*[=:]", re.I)
CLEARANCE_HOME = "FabPlacement.kt"


def matching(text: str, start: int, open_ch: str, close_ch: str) -> int:
    depth = 0
    for i in range(start, len(text)):
        if text[i] == open_ch:
            depth += 1
        elif text[i] == close_ch:
            depth -= 1
            if depth == 0:
                return i
    return len(text) - 1


def find_violations(root: Path, allowed: dict[tuple[str, str], str] = FAB_COMPONENT_ALLOWED) -> list[str]:
    violations: list[str] = []
    used: set[tuple[str, str]] = set()
    clearance_defs: list[str] = []
    paths = main_sources(root)
    typealiases = kotlin_typealiases(paths)
    for path in paths:
        rel = path.relative_to(root).as_posix()
        text = normalize_code(path.read_text(encoding="utf-8"), typealiases)

        def at(pos: int) -> str:
            return f"{rel}:{text[:pos].count(chr(10)) + 1}"

        slots = []
        for m in SLOT.finditer(text):
            end = matching(text, m.end() - 1, "{", "}")
            slots.append((m.end(), end))
            pos = POSITIONING.search(text, m.end(), end)
            if pos:
                violations.append(f"{at(pos.start())}: FAB 槽位里自己定位（.{pos.group(1)}）——位置是 Scaffold 的事，删掉")
        for m in MENU_CALL.finditer(text):
            if DECLARATION_PREFIX.search(text, 0, m.start()):
                continue
            end = matching(text, m.end() - 1, "(", ")")
            pos = POSITIONING.search(text, m.end(), end)
            if pos:
                violations.append(f"{at(pos.start())}: SelectionFabMenu 调用处自己定位（.{pos.group(1)}）——位置在组件里定，删掉")
        for m in FAB_CALL.finditer(text):
            if m.group(1).endswith("ExtendedFloatingActionButton"):
                args_end = matching(text, m.end() - 1, "(", ")")
                if EXPANDED_ARG.search(text, m.end(), args_end):
                    violations.append(
                        f"{at(m.start())}: 扩展 FAB 传了 expanded——FAB 不随滚动变形：要文字就一直带文字，"
                        "不要就用只有图标的 FloatingActionButton（规则见 core:ui FabPlacement.kt）"
                    )
            if any(start <= m.start() < end for start, end in slots):
                continue
            key = (path.name, enclosing_function(text, m.start()))
            if key in allowed:
                used.add(key)
                continue
            violations.append(
                f"{at(m.start())}: {key[1]} 直接用了 {m.group(1)}——FAB 只能放进 Scaffold 的 floatingActionButton 槽位，"
                "或用 SelectionFabMenu（全 App FAB 规则见 core:ui FabPlacement.kt）"
            )
        for m in CLEARANCE_DEF.finditer(text):
            clearance_defs.append(f"{at(m.start())} {m.group(1)}")
            if path.name != CLEARANCE_HOME:
                violations.append(f"{at(m.start())}: 又定义了一份 FAB 留白 {m.group(1)}——全 App 只用 core:ui 的 FabClearance")
    if len(clearance_defs) != 1:
        violations.append(f"FAB 留白应恰好定义一次（core:ui {CLEARANCE_HOME} 的 FabClearance），实际：{clearance_defs or '没有'}")
    for key in sorted(set(allowed) - used):
        violations.append(f"白名单条目 {key[0]}::{key[1]} 已匹配不到 FAB，删掉这条（死配置）")
    return violations


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    violations = find_violations(root)
    for v in violations:
        print(v, file=sys.stderr)
    if violations:
        print(f"\nFAB 位置门禁未通过：{len(violations)} 处（规则见 core:ui FabPlacement.kt）", file=sys.stderr)
        return 1
    print("FAB 位置门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
