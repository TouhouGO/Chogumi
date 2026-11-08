"""下拉菜单门禁（唯一实现处；tools/ci/check.sh lint 运行，单跑：python3 tools/guards/check_menus.py）。

全 App 的下拉菜单只有一个：core:ui 的 AppDropdownMenu（M3 vertical menu：圆角 large、surfaceContainerLow；分组的版本也在那里）。
菜单的底层零件（DropdownMenuPopup、DropdownMenuGroup）同样只在那里用。
页面直接调 M3 的 DropdownMenu，就会出现方角 baseline 菜单、不同底色。SelectableDropdownMenu 等也建在 AppDropdownMenu 上。
菜单项同样只有一种：AppMenuItem（文字 + 可选前置图标，点了先收起菜单再执行）；单选菜单的选中项在 SelectableDropdownMenu.kt。
页面直接写 DropdownMenuItem，图标色、收起时机就各写各的。

页面顶栏的 ⋮ 也只有一个：core:ui 的 PageMoreMenu（固定「网页」「分享」，页面的项排在后面）。`more_vert` 图标只许出现在
PageMoreMenu.kt 与 TextChoiceGroup.kt（按钮组放不下的选项收进的溢出菜单）；各页自己画 ⋮，菜单项、顺序和分享格式就各写各的。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

from sources import kotlin_typealiases, main_sources, normalize_code

HOMES = {"AppDropdownMenu.kt"}

# 任何尺寸、填充与否的 ⋮
MORE_ICON = re.compile(r"\bR\s*\.\s*drawable\s*\.\s*more_vert\w*")
MORE_ICON_HOMES = {"PageMoreMenu.kt", "TextChoiceGroup.kt"}

ITEM_HOMES = {"AppDropdownMenu.kt", "SelectableDropdownMenu.kt"}
ITEM_CALL = re.compile(r"(?<![\w.])(?:[a-z]\w*\.)*DropdownMenuItem\s*\(")

FIELD_CALL = re.compile(r"(?<![\w.])(?:[a-z]\w*\.)*(ExposedDropdownMenu|DropdownMenuPopup|DropdownMenuGroup|DropdownMenu)\s*\(")


def find_violations(root: Path) -> list[str]:
    violations: list[str] = []
    paths = main_sources(root)
    typealiases = kotlin_typealiases(paths)
    for path in paths:
        text = normalize_code(path.read_text(encoding="utf-8"), typealiases)
        if path.name not in ITEM_HOMES:
            for m in ITEM_CALL.finditer(text):
                line = text[: m.start()].count("\n") + 1
                violations.append(
                    f"{path.relative_to(root).as_posix()}:{line}: 直接写了 DropdownMenuItem——菜单项改用 core:ui 的 AppMenuItem"
                )
        if path.name in HOMES:
            continue
        if path.name not in MORE_ICON_HOMES:
            for m in MORE_ICON.finditer(text):
                line = text[: m.start()].count("\n") + 1
                violations.append(
                    f"{path.relative_to(root).as_posix()}:{line}: 自己画了顶栏 ⋮——改用 core:ui 的 PageMoreMenu（页面的项传进 extra）"
                )
        for m in FIELD_CALL.finditer(text):
            line = text[: m.start()].count("\n") + 1
            violations.append(
                f"{path.relative_to(root).as_posix()}:{line}: 直接用 {m.group(1)}——下拉菜单改用 core:ui 的 AppDropdownMenu"
            )
    return violations


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    violations = find_violations(root)
    for v in violations:
        print(v, file=sys.stderr)
    if violations:
        print(f"\n下拉菜单门禁未通过：{len(violations)} 处（见 core:ui AppDropdownMenu）", file=sys.stderr)
        return 1
    print("下拉菜单门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
