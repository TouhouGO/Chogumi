"""显示名门禁（唯一实现处；tools/ci/check.sh lint 运行，单跑：python3 tools/guards/check_display_name.py）。

规矩见 docs/code-standards.md 红线 1「单一真源」：作品、章节、角色、人物的显示名「中文名优先，空白时回退原名」只由
core:model 的 `displayNameOf` 定（实体实现 `NamedEntity`，接口 DTO 传两个字符串）；用户的显示名「昵称优先，空白时回退
用户名，再空写 #uid」只由同一文件里的 `displayNameOf(user)` / `userDisplayNameOf` 定。各处手写一份，有的判空白、有的判空串
（`ifEmpty`），有的不回退，同一个名字在一页显示空白、在另一页显示原名。

检查项（主源码 app / core / feature 的 src/main 下 .kt，去掉注释后）：
1. `nameCn` / `nameCN`、`nickname` / `creatorNickname` 后接 `ifBlank` / `ifEmpty` / `takeIf` / `takeUnless`
   （含 `?.`、换行、中间隔几个无参调用如 `.trim().unescapeHtmlEntities()`）；`nameCn ?: name` 这样用 elvis 回退到原名或用户名；
2. 在两个名字之间挑：`if (…)` 的整个条件（可跨行、可嵌套括号）或 `when { … }` 某个分支的条件里有
   `nameCn / nickname .isBlank() / isNotBlank() / isEmpty() / isNotEmpty() / isNullOrBlank() …`，或 `when (….nameCn)` 直接按名字分支；
3. 作品的 `displayNameOf(name, nameCn)` 拿昵称当译名用（整个实参表里出现 `nickname`，可跨行）；
4. 原始昵称直接当显示文字：`text =` / `description =` 的实参、或 `Text(…)` 的第一个位置实参就是 `….nickname`。
放行：规则本身所在的 DisplayName.kt；文件不在了、或里面已没有这些写法，这条放行也报出来。
合并译名、按两个名字搜索、数据层把接口的昵称原样存进模型这类不挑显示名的
用法不在上面几种写法里，不拦。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

from sources import kotlin_typealiases, main_sources, normalize_code

ALLOWED = {
    "core/model/src/main/kotlin/io/github/touhougo/chogumi/core/model/DisplayName.kt",
}

NAME = r"\b(?:nameC[nN]|nickname|creatorNickname)"
STEP = r"\s*(?:!!)?\??\.\s*"
FALLBACK = re.compile(NAME + r"(?:" + STEP + r"\w+\s*\(\s*\))*" + STEP + r"(?:ifBlank|ifEmpty|takeIf|takeUnless)\b")
ELVIS_NAME = re.compile(NAME + r"\s*\?:\s*(?:\w+" + STEP + r")*(?:name|username)\b(?!\s*\()")
IS_BLANK = re.compile(NAME + STEP + r"is(?:Null)?(?:Or(?:Not)?)?(?:Not)?(?:Blank|Empty)\s*\(\s*\)")
IF_OPEN = re.compile(r"\bif\s*\(")
WHEN_OPEN = re.compile(r"\bwhen\s*\{")
WHEN_NAME = re.compile(r"\bwhen\s*\(\s*(?:\w+" + STEP + r")*" + NAME + r"\s*\)")
ARROW_AHEAD = re.compile(r"[^\n{}]*?->")
ELVIS_BLANK = re.compile(r"\(\s*[\w.]*" + NAME + r"\s*\?:\s*\)\s*\.\s*(?:ifBlank|ifEmpty)\b")
NAME_OF_OPEN = re.compile(r"\bdisplayNameOf\s*\(")
NICKNAME = re.compile(r"\b(?:nickname|creatorNickname)\b")
RAW_NICKNAME_TEXT = re.compile(
    r"\b(?:(?:text|description)\s*=|Text\s*\()\s*[\w.?!]*\.(?:nickname|creatorNickname)\s*[,)]"
)
BRACKETS = {"(": ")", "{": "}"}
HINT = (
    "显示名只经 core:model 的 displayNameOf（实体传本身，接口 DTO 传 name = …, nameCn = …；"
    "用户传 UserBo，只有字段时用 userDisplayNameOf(username, nickname, uid)）"
)


def closing(text: str, open_at: int) -> int:
    """[open_at] 处括号的配对位置；没有配对时到文末。调用方给的是遮掉字面量与注释的代码视图。"""
    opener, closer = text[open_at], BRACKETS[text[open_at]]
    depth = 0
    for index in range(open_at, len(text)):
        if text[index] == opener:
            depth += 1
        elif text[index] == closer:
            depth -= 1
            if depth == 0:
                return index
    return len(text)


def rule_offsets(text: str) -> set[int]:
    """一个文件里手写显示名规则的位置（去重：嵌套的 when 只报一次）。"""
    found: set[int] = set()
    for pattern in (FALLBACK, ELVIS_NAME, ELVIS_BLANK, WHEN_NAME, RAW_NICKNAME_TEXT):
        found.update(m.start() for m in pattern.finditer(text))
    for opened in IF_OPEN.finditer(text):
        start = opened.end()
        condition = text[start:closing(text, opened.end() - 1)]
        found.update(start + m.start() for m in IS_BLANK.finditer(condition))
    for opened in WHEN_OPEN.finditer(text):
        start = opened.end()
        body = text[start:closing(text, opened.end() - 1)]
        for m in IS_BLANK.finditer(body):
            line_start = max(body.rfind(mark, 0, m.start()) for mark in "\n{;")
            if "->" not in body[line_start + 1:m.start()] and ARROW_AHEAD.match(body, m.end()):
                found.add(start + m.start())
    for opened in NAME_OF_OPEN.finditer(text):
        if NICKNAME.search(text, opened.end(), closing(text, opened.end() - 1)):
            found.add(opened.start())
    return found


def find_violations(root: Path, allowed: set[str] = ALLOWED) -> list[str]:
    violations: list[str] = []
    paths = main_sources(root)
    typealiases = kotlin_typealiases(paths)
    for path in paths:
        rel = path.relative_to(root).as_posix()
        if rel in allowed:
            continue
        text = normalize_code(path.read_text(encoding="utf-8"), typealiases)
        for offset in sorted(rule_offsets(text)):
            line = text[:offset].count("\n") + 1
            violations.append(f"{rel}:{line}: 手写了显示名规则——{HINT}")
    return violations


def unused_allowances(root: Path, allowed: set[str] = ALLOWED) -> list[str]:
    """放行的文件不在了、或里面已没有显示名规则本身。"""
    stale: list[str] = []
    for rel in sorted(allowed):
        path = root / rel
        if not path.is_file() or not rule_offsets(normalize_code(path.read_text(encoding="utf-8"))):
            stale.append(f"{rel}: 放行用不上了（文件不在或已没有显示名规则）——从 ALLOWED 删掉")
    return stale


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    violations = find_violations(root) + unused_allowances(root)
    for v in violations:
        print(v, file=sys.stderr)
    if violations:
        print(f"\n显示名门禁未通过：{len(violations)} 处", file=sys.stderr)
        return 1
    print("显示名门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
