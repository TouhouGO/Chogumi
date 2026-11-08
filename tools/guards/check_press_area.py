"""「整块可点」门禁（唯一实现处；tools/ci/check.sh lint 运行，单跑：python3 tools/guards/check_press_area.py）。

背景：封面卡等「带文字的一整块」为了让按下水波纹有圆角，各处自己给整块 `clip(圆角).clickable`，
标题第二行落在左下角被圆角切掉首字；也有页面另写 `Surface(onClick)` 拿第二套整块交互。统一用 core:ui 的
PressArea：整块可点、只裁水波纹、不裁内容，容器底色 / 边框仍由这个 owner 画。

规则：修饰符链里有 `.clip(…)` 后接点击修饰符（`.clickable` / `.combinedClickable` / `.selectable` / `.toggleable` /
`.triStateToggleable`），且 clip 之后没有 `.background(`（即「透明的、只为给水波纹圆角」的
裁切），就要改用 PressArea。clip 之后的 `.padding(` 不算例外：内边距只是把文字往里缩，裁切本身仍是透明的、只为水波纹。
Material 3 可点的 `Surface` / `Card` / `ElevatedCard` / `OutlinedCard`（具名 `onClick` 或开关重载的 `onCheckedChange` 在任意位置，
或第一个位置参数是回调或选中值）的内容里出现 `Text` 调用或 `label` 插槽也算旁路；官方 Button / Chip 等组件自己的 Surface 不在此列。例外：
- 裁的是一张纯图（这条修饰符属于 SubjectPoster / AsyncImage / PortraitImage / CircleAvatar / Icon / Image 调用），
  或裁成圆（头像）——裁切就是图本身的形状；
- 裁切本身就是内容形状的特例，以及共享 chip / 搜索容器，进白名单（按「文件::函数」列，写明理由）；白名单里匹配不到的
  条目算死配置。
"""

from __future__ import annotations

import re
import sys
from dataclasses import dataclass
from pathlib import Path

from sources import enclosing_function, kotlin_typealiases, main_sources, mask_literals, normalize_code

# 裁切是内容本身的形状、不是为了水波纹的地方（文件名, 函数名）：理由
CONTENT_CLIP_ALLOWED = {
    ("SubjectPoster.kt", "SubjectPoster"): "封面本身的形状：封面图与压在上面的内容都裁在里面，只有一张封面的点击目标水波纹随它",
}

SURFACE_ALLOWED = {
    ("HeaderChip.kt", "HeaderChip"): "详情页头部的共享 chip：可点与禁用共用 Surface 的布局和 48dp 触控区",
    ("ContainedSearchField.kt", "SearchContainer"): "输入框与搜索入口共用的容器，输入态本身不是可点块",
}

IMAGE_ONLY_CALLS = {"SubjectPoster", "AsyncImage", "PortraitImage", "CircleAvatar", "Icon", "Image"}
CLIP = re.compile(r"\.\s*clip\s*\(")
BACKGROUND = re.compile(r"\.\s*background\s*\(")
PRESS = re.compile(r"\.\s*(clickable|combinedClickable|selectable|toggleable|triStateToggleable)\b")
CALL = re.compile(r"\b([A-Z]\w*)\s*\($")
TOKEN = re.compile(r"`[^`\n]*`|[A-Za-z_]\w*|::|\S")
# 自带可点重载的 M3 容器：Surface 与三种 Card 的 onClick 版都是第二套整块交互
CLICKABLE_CONTAINERS = {"Surface", "Card", "ElevatedCard", "OutlinedCard"}
# 让容器变成可点块的具名参数：点击（含 selected 重载）与开关重载（checked + onCheckedChange）
CLICK_PARAMETERS = {"onClick", "onCheckedChange"}


def modifier_chain(text: str, start: int) -> str:
    """从 `.clip(` 起，沿着 `.xxx(...)` / `.then(...)` 连续调用取整条修饰符链（括号配平）。"""
    i = start
    while True:
        m = re.compile(r"\s*\.\s*\w+").match(text, i)
        if not m:
            return text[start:i]
        j = m.end()
        while j < len(text) and text[j].isspace():
            j += 1
        if j < len(text) and text[j] == "(":
            depth = 0
            while j < len(text):
                if text[j] == "(":
                    depth += 1
                elif text[j] == ")":
                    depth -= 1
                    if depth == 0:
                        break
                j += 1
            j += 1
        elif j < len(text) and text[j] == "{":
            depth = 0
            while j < len(text):
                if text[j] == "{":
                    depth += 1
                elif text[j] == "}":
                    depth -= 1
                    if depth == 0:
                        break
                j += 1
            j += 1
        i = j


def owner_call(text: str, pos: int) -> str | None:
    """这条修饰符写在哪个调用的括号里：往前找第一个没配平的 `(`，取它前面的名字。"""
    depth = 0
    for i in range(pos - 1, -1, -1):
        c = text[i]
        if c == ")":
            depth += 1
        elif c == "(":
            if depth == 0:
                m = CALL.search(text[max(0, i - 80): i + 1])
                return m.group(1) if m else None
            depth -= 1
    return None


@dataclass(frozen=True)
class Token:
    value: str
    start: int


def code_tokens(text: str) -> list[Token]:
    """规范化后的 Kotlin 切成代码 token：注释与字面量先由共用遮罩抹掉，反引号名称取里面的名字。"""
    code = mask_literals(text)
    return [Token(m.group().strip("`"), m.start()) for m in TOKEN.finditer(code)]


def token_call_end(tokens: list[Token], opening: int) -> int:
    depth = 0
    for i in range(opening, len(tokens)):
        if tokens[i].value == "(":
            depth += 1
        elif tokens[i].value == ")":
            depth -= 1
            if depth == 0:
                return i
    return len(tokens) - 1


def split_top_level(tokens: list[Token], start: int, end: int) -> list[list[Token]]:
    result: list[list[Token]] = []
    current: list[Token] = []
    depths = {"(": 0, "[": 0, "{": 0}
    pairs = {")": "(", "]": "[", "}": "{"}
    for token in tokens[start:end + 1]:
        if token.value in depths:
            depths[token.value] += 1
        elif token.value in pairs:
            depths[pairs[token.value]] = max(0, depths[pairs[token.value]] - 1)
        if token.value == "," and not any(depths.values()):
            result.append(current)
            current = []
        else:
            current.append(token)
    result.append(current)
    return [part for part in result if part]


def is_clickable_surface(arguments: list[list[Token]]) -> bool:
    """Surface 调用的参数里有具名的 onClick（任意位置；selected 重载也是它）或开关重载的 onCheckedChange，
    或第一个位置参数就是点击回调或选中值。"""
    if any(len(tokens) > 1 and tokens[0].value in CLICK_PARAMETERS and tokens[1].value == "=" for tokens in arguments):
        return True
    if not arguments:
        return False
    tokens = arguments[0]
    if len(tokens) > 1 and tokens[1].value == "=":
        return False
    first = tokens[0]
    if first.value == "{":
        return True
    if first.value not in {"modifier", "Modifier"} and (first.value[0].isalpha() or first.value[0] == "_"):
        return len(tokens) == 1 or (len(tokens) > 1 and tokens[1].value == "::")
    return False


def calls_in_tokens(tokens: list[Token], names: set[str], start: int, end: int) -> bool:
    for i in range(start, min(end, len(tokens) - 1)):
        if tokens[i].value in names and tokens[i + 1].value == "(":
            return True
    return False


def surface_violations(root: Path, allowed: dict[tuple[str, str], str], paths: list[Path], typealiases: dict[str, str]) -> list[str]:
    violations: list[str] = []
    used: set[tuple[str, str]] = set()
    for path in paths:
        text = normalize_code(path.read_text(encoding="utf-8"), typealiases)
        tokens = code_tokens(text)
        for i in range(len(tokens) - 1):
            if tokens[i].value not in CLICKABLE_CONTAINERS or tokens[i + 1].value != "(":
                continue
            closing = token_call_end(tokens, i + 1)
            arguments = split_top_level(tokens, i + 2, closing - 1)
            if not is_clickable_surface(arguments):
                continue
            key = (path.name, enclosing_function(text, tokens[i].start))
            if key in allowed:
                used.add(key)
                continue
            content_end = closing
            if closing + 1 < len(tokens) and tokens[closing + 1].value == "{":
                depth = 0
                for j in range(closing + 1, len(tokens)):
                    if tokens[j].value == "{":
                        depth += 1
                    elif tokens[j].value == "}":
                        depth -= 1
                        if depth == 0:
                            content_end = j
                            break
            has_text = calls_in_tokens(tokens, {"Text"}, i + 2, content_end)
            has_text = has_text or any(token.value == "label" for token in tokens[i + 2:content_end + 1])
            if not has_text:
                continue
            line = text[:tokens[i].start].count("\n") + 1
            violations.append(
                f"{path.relative_to(root).as_posix()}:{line}: 可点的 {tokens[i].value} 内含文字，块级交互有第二套实现——"
                "改用 PressArea（整块可点、只裁水波纹、容器外观仍保留）"
            )
    for key in sorted(set(allowed) - used):
        violations.append(f"Surface 白名单条目 {key[0]}::{key[1]} 已匹配不到，删掉这条（死配置）")
    return violations


def find_violations(
    root: Path,
    allowed: dict[tuple[str, str], str] = CONTENT_CLIP_ALLOWED,
    surface_allowed: dict[tuple[str, str], str] = SURFACE_ALLOWED,
) -> list[str]:
    paths = main_sources(root)
    typealiases = kotlin_typealiases(paths)
    violations = surface_violations(root, surface_allowed, paths, typealiases)
    used: set[tuple[str, str]] = set()
    for path in paths:
        text = normalize_code(path.read_text(encoding="utf-8"), typealiases)
        for m in CLIP.finditer(text):
            chain = modifier_chain(text, m.start())
            if not PRESS.search(chain) or BACKGROUND.search(chain):
                continue
            if "CircleShape" in chain.split(")")[0]:
                continue
            if owner_call(text, m.start()) in IMAGE_ONLY_CALLS:
                continue
            key = (path.name, enclosing_function(text, m.start()))
            if key in allowed:
                used.add(key)
                continue
            line = text[: m.start()].count("\n") + 1
            violations.append(
                f"{path.relative_to(root).as_posix()}:{line}: {key[1]} 用透明的 clip + clickable 给一整块圆角水波纹——"
                "块里的文字会被圆角切掉；改用 PressArea（只裁水波纹、不裁内容）"
            )
    for key in sorted(set(allowed) - used):
        violations.append(f"白名单条目 {key[0]}::{key[1]} 已匹配不到，删掉这条（死配置）")
    return violations


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    violations = find_violations(root)
    for v in violations:
        print(v, file=sys.stderr)
    if violations:
        print(f"\n「整块可点」门禁未通过：{len(violations)} 处（见 core:ui PressArea）", file=sys.stderr)
        return 1
    print("「整块可点」门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
