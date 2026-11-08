"""「封面只在 SubjectPoster 里裁」门禁（唯一实现处；tools/ci/check.sh lint 运行，单跑：python3 tools/guards/check_cover_clip.py）。

背景：封面图自己裁了圆角，页面却在旁边另叠一层（排行榜名次的渐变托底），那一层没裁，封面上边两个角露成直角；
长按浮起的那一份又裁了，于是一按就「变圆」、一落就「变直」。各页还各自 `clip` 一层封面框、各自给封面挂 `clickable`
（水波纹是方的），圆角各写各的。封面只有一种画法：core:ui 的 SubjectPoster 把封面图和压在上面的内容（overlay）
一起裁进 shape，点击走它的 onClick（水波纹裁在同一个形状里）。

规则（core:ui 的 SubjectPoster.kt 之外）：
- 调用了 `SubjectPoster(` 的函数里不能再裁切（`.clip(`、不为 false 的 `clip = …`、`.clipToBounds(`）：形状交给 shape，
  压在封面上的东西放进 overlay；
- 交给 SubjectPoster 的修饰符里不挂点击修饰符（`.clickable` / `.combinedClickable` / `.selectable` / `.toggleable`）：
  改用它的 onClick，能长按的传 mark。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

from check_press_area import owner_call
from sources import FUN_DECL, kotlin_typealiases, main_sources, normalize_code

POSTER_CALL = re.compile(r"\bSubjectPoster\s*\(")
# 图层与阴影的 clip 参数：除了明写 false，传进来的条件也会裁
CLIP = re.compile(r"\.\s*clip\s*\(|\bclip\s*=\s*(?!false\b)[^=\s]|\.\s*clipToBounds\s*\(")
# 点击、长按、选中与开关：都会让水波纹按修饰符的方框画
CLICKABLE = re.compile(r"\.\s*(?:clickable|combinedClickable|selectable|toggleable|triStateToggleable)\b")
OWNER_FILE = "SubjectPoster.kt"


def functions(text: str) -> list[tuple[str, int, int]]:
    """按顶层 / 成员 `fun` 声明把文件切成段：(函数名, 起, 止)。"""
    decls = list(FUN_DECL.finditer(text))
    return [(m.group(1), m.start(), decls[i + 1].start() if i + 1 < len(decls) else len(text)) for i, m in enumerate(decls)]


def find_violations(root: Path) -> list[str]:
    violations: list[str] = []
    paths = main_sources(root)
    typealiases = kotlin_typealiases(paths)
    for path in paths:
        if path.name == OWNER_FILE:
            continue
        text = normalize_code(path.read_text(encoding="utf-8"), typealiases)
        if not POSTER_CALL.search(text):
            continue
        rel = path.relative_to(root).as_posix()
        for name, start, end in functions(text):
            body = text[start:end]
            if not POSTER_CALL.search(body):
                continue
            for m in CLIP.finditer(body):
                line = text[: start + m.start()].count("\n") + 1
                violations.append(
                    f"{rel}:{line}: {name} 画了封面又自己裁切——封面的圆角只在 SubjectPoster 里裁："
                    "形状交给 shape，压在封面上的东西放进 overlay"
                )
        for m in CLICKABLE.finditer(text):
            if owner_call(text, m.start()) == "SubjectPoster":
                line = text[: m.start()].count("\n") + 1
                violations.append(f"{rel}:{line}: 给 SubjectPoster 的修饰符挂了点击修饰符（水波纹不随封面圆角）——改用它的 onClick（长按传 mark）")
    return violations


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    violations = find_violations(root)
    for v in violations:
        print(v, file=sys.stderr)
    if violations:
        print(f"\n「封面只在 SubjectPoster 里裁」门禁未通过：{len(violations)} 处（见 core:ui SubjectPoster）", file=sys.stderr)
        return 1
    print("「封面只在 SubjectPoster 里裁」门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
