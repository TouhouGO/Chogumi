"""门禁共用的源码范围、Kotlin 词法遮罩与别名规范化（[normalize_kotlin]）：主源码包含 app、core、feature、baselineprofile 与 build-logic；
测试源码包含常规 test / testFixtures / androidTest 与 baselineprofile 这个 com.android.test 模块的 src/main；
Actions 门禁读 workflow。"""

from __future__ import annotations

import os
import re
from pathlib import Path

MAIN_ROOTS = ["app/src/main", "core/*/src/main", "feature/*/src/main", "baselineprofile/src/main", "build-logic/src/main"]
TEST_SOURCE_SETS = ("test", "testFixtures", "androidTest")
BASELINE_PROFILE_MODULE = "baselineprofile"
PROJECT_ROOTS = ["app", "core/*", "feature/*", "baselineprofile", "build-logic"]
KDOC_LINK = re.compile(r"\[([\w.`]+)\]")
GITHUB_ROOTS = [
    ".github/workflows/*.yml",
    ".github/workflows/*.yaml",
]


# 一行里可以用分号接第二个 import；别名、类型别名名字都可以写成反引号形式。
IMPORT_ALIAS = re.compile(r"(?m)(?:^|;)\s*import\s+([\w.]+)\s+as\s+`?(\w+)`?")
TYPEALIAS = re.compile(
    r"(?m)^\s*(?:@[\w.]+(?:\([^)\n]*\))?\s+)*(?:(?:private|internal|public|actual|expect)\s+)*"
    r"typealias\s+`?(\w+)`?\s*(?:<[^>=]*>)?\s*=\s*([\w.]+)"
)
IDENTIFIER = re.compile(r"(?<![\w$])[A-Za-z_]\w*")
# 反引号包住的普通标识符（`TextField`）与不带反引号的是同一个名字；带空格的测试名不算。
BACKTICK_IDENTIFIER = re.compile(r"`([A-Za-z_]\w*)`")


def import_aliases(text: str) -> dict[str, str]:
    """一个文件的 `import a.b.C as D`：别名 → 原限定名；字符串里的示例声明不算。"""
    return {alias: target for target, alias in IMPORT_ALIAS.findall(mask_literals(text))}


def symbol_names(text: str, qualified_name: str) -> set[str]:
    """规范名称与其显式导入别名；调用方先去注释，使用原偏移输出行号。"""
    aliases = {alias for alias, target in import_aliases(text).items() if target == qualified_name}
    return {qualified_name.rsplit(".", 1)[-1], *aliases}


def kotlin_typealiases(paths: list[Path]) -> dict[str, str]:
    """这些文件里的 `typealias D = a.b.C`：别名 → 最终目标的简单名（别名链走到底）。
    同名别名指向不同目标、或别名链成环时不猜，不收录。"""
    targets: dict[str, set[str]] = {}
    for path in paths:
        for alias, target in TYPEALIAS.findall(mask_literals(path.read_text(encoding="utf-8"))):
            targets.setdefault(alias, set()).add(target.rsplit(".", 1)[-1])
    direct = {alias: next(iter(found)) for alias, found in targets.items() if len(found) == 1}
    resolved: dict[str, str] = {}
    for alias in direct:
        seen = {alias}
        target = direct[alias]
        while target in direct and target not in seen:
            seen.add(target)
            target = direct[target]
        if target not in direct:
            resolved[alias] = target
    return resolved


def normalize_kotlin(text: str, typealiases: dict[str, str] | None = None) -> str:
    """去注释，把代码里的导入别名和类型别名换回原来的简单名，并去掉普通标识符外的反引号（挪到名字前变成空格），
    让按名字找调用的门禁看穿别名与 `` `TextField`(…) ``。只换代码里的名字（字符串、字符字面量和注释里的不动），
    换行原样保留，行号不变；文件自己的导入别名优先于类型别名。"""
    stripped = strip_comments(text)
    renames = dict(typealiases or {})
    renames.update({alias: target.rsplit(".", 1)[-1] for alias, target in import_aliases(stripped).items()})
    renames = {alias: target for alias, target in renames.items() if alias != target}
    if not renames and "`" not in text:
        return stripped
    code = mask_literals(text)
    edits: list[tuple[int, int, str]] = []
    for match in BACKTICK_IDENTIFIER.finditer(code):
        # 两个反引号都挪到名字前面变成空格：长度不变，名字紧贴后面的 `.`、`(`、`<`。
        edits.append((match.start(), match.start() + 1, "  "))
        edits.append((match.end() - 1, match.end(), ""))
    if renames:
        for match in IDENTIFIER.finditer(code):
            target = renames.get(match.group())
            if target is not None:
                edits.append((match.start(), match.end(), target))
    if not edits:
        return stripped
    out: list[str] = []
    last = 0
    for start, end, replacement in sorted(edits):
        out.append(stripped[last:start])
        out.append(replacement)
        last = end
    out.append(stripped[last:])
    return "".join(out)


def normalize_code(text: str, typealiases: dict[str, str] | None = None) -> str:
    """门禁识别调用与比较时用的代码视图：先规范化别名，再由共用词法器遮蔽注释和字面量。
    字符串模板 `${…}` / `$name` 里的真实代码保留；普通字符串内容与注释不参与。替换别名可能改变字符偏移，
    但换行与行号保持不变；调用方不得拿这个视图去切原始源码。"""
    return mask_literals(normalize_kotlin(text, typealiases))


# 函数声明：注解与修饰符（inline、override、suspend……）任意组合，可带泛型与接收者；漏认一种修饰符，
# 它的函数体就会算到上一个函数名下，借走上一个函数的白名单。
FUN_DECL = re.compile(
    r"^\s*(?:(?:@[\w.]+(?:\([^)]*\))?|private|internal|public|protected|inline|suspend|override|operator|infix"
    r"|tailrec|external|actual|expect|open|abstract|final)\s+)*"
    r"fun\s+(?:<[^>]*>\s*)?(?:[\w.]+(?:<[^>()]*>)?\??\.)?`?(\w+)`?\s*\(",
    re.M,
)


def enclosing_function(text: str, pos: int) -> str:
    """[pos] 之前最近一个函数声明的名字；没有时为 `?`。"""
    name = "?"
    for m in FUN_DECL.finditer(text, 0, pos):
        name = m.group(1)
    return name


# 门禁读的是 normalize_code 视图：字符串与字符字面量已遮成空格，只剩代码、反引号名字与括号
ARGUMENT_TOKEN = re.compile(r"`[^`]*`|<(?=[\w.,? \t\r\n]*>\s*\()|[()\[\]{},>]")
NAMED_ARGUMENT = re.compile(r"^\s*(\w+)\s*=(?!=)")


def _scan_call(text: str, open_paren: int) -> tuple[list[str], int]:
    """按调用顶层逗号分实参，嵌套调用、lambda 与类型实参里的逗号不计；返回（实参, 右括号下标）。
    括号不配对时右括号下标是 -1。"""
    arguments: list[str] = []
    stack: list[str] = []
    start = open_paren + 1
    pairs = {")": "(", "]": "[", "}": "{", ">": "<"}
    for token in ARGUMENT_TOKEN.finditer(text, start):
        value = token.group(0)
        if value in {"(", "[", "{", "<"}:
            stack.append(value)
        elif value == ")" and not stack:
            argument = text[start:token.start()].strip()
            if argument:
                arguments.append(argument)
            return arguments, token.start()
        elif value in pairs and stack and stack[-1] == pairs[value]:
            stack.pop()
        elif value == "," and not stack:
            arguments.append(text[start:token.start()].strip())
            start = token.end()
    return [], -1


def call_arguments(text: str, open_paren: int) -> list[str]:
    """调用 `(` 之后的顶层实参（读 normalize_code 视图）；括号不配对时为空。"""
    return _scan_call(text, open_paren)[0]


def call_end(text: str, open_paren: int) -> int:
    """调用 `(` 对应的 `)` 下标（读 normalize_code 视图）；括号不配对时取文本末尾。"""
    end = _scan_call(text, open_paren)[1]
    return end if end >= 0 else len(text)


def is_import_line(text: str, offset: int) -> bool:
    """[offset] 是否落在 import 行；用于区分声明与真正使用被导入的符号。"""
    start = text.rfind("\n", 0, offset) + 1
    return text[start:offset].lstrip().startswith("import ")


def main_sources(root: Path) -> list[Path]:
    return sorted(p for pattern in MAIN_ROOTS for base in root.glob(pattern) for p in base.rglob("*.kt"))


def test_source_scope(root: Path, path: Path) -> tuple[str, str] | None:
    """测试源码的模块与源集；只有 baselineprofile 的 src/main 按 com.android.test 的主测试源码处理。"""
    parts = path.relative_to(root).parts
    if parts[:3] == (BASELINE_PROFILE_MODULE, "src", "main"):
        return BASELINE_PROFILE_MODULE, "main"
    for index in range(len(parts) - 1):
        if parts[index] == "src" and parts[index + 1] in TEST_SOURCE_SETS:
            module = Path(*parts[:index]).as_posix() if index else "."
            return module, parts[index + 1]
    return None


def project_sources(root: Path) -> list[Path]:
    """项目模块任意源码集中的 Kotlin；隐藏与 build 目录不参与。"""
    sources: list[Path] = []
    for pattern in PROJECT_ROOTS:
        for module in root.glob(pattern):
            if module.name.startswith(".") or module.name == "build":
                continue
            source_root = module / "src"
            if not source_root.is_dir():
                continue
            for directory, dirs, files in os.walk(source_root):
                dirs[:] = sorted(name for name in dirs if name != "build" and not name.startswith("."))
                path = Path(directory)
                sources.extend(path / name for name in files if name.endswith(".kt"))
    return sorted(sources)


def github_sources(root: Path) -> list[Path]:
    """Actions 门禁的 workflow 范围；local action 只沿实际 uses 递归读取。"""
    return sorted({path for pattern in GITHUB_ROOTS for path in root.glob(pattern) if path.is_file()})


def local_action_candidates(root: Path, reference: str) -> list[Path]:
    """把 ./ 或 $/ 引用解析为仓内 action.yml|action.yaml 候选，路径逃逸直接拒绝。"""
    if reference.startswith("./"):
        relative = reference[2:]
    elif reference.startswith("$/"):
        relative = reference[2:]
    else:
        raise ValueError("不是 local action 路径")
    target = (root / relative).resolve()
    try:
        target.relative_to(root.resolve())
    except ValueError as error:
        raise ValueError("local action 路径逃出仓库") from error
    candidates = [(target / "action.yml").resolve(), (target / "action.yaml").resolve()]
    for candidate in candidates:
        try:
            candidate.relative_to(root.resolve())
        except ValueError as error:
            raise ValueError("local action metadata 路径逃出仓库") from error
    return candidates


def test_sources(root: Path) -> list[Path]:
    """任意深度模块的常规测试源集与 baselineprofile/src/main 下 Kotlin；隐藏与 build 目录不参与。"""
    sources: list[Path] = []
    for directory, dirs, files in os.walk(root):
        dirs[:] = sorted(name for name in dirs if name != "build" and not name.startswith("."))
        path = Path(directory)
        for name in files:
            source = path / name
            if name.endswith(".kt") and test_source_scope(root, source) is not None:
                sources.append(source)
    return sorted(sources)


def _mask_kotlin(text: str, *, literals: bool, comment_links: bool) -> str:
    """Kotlin 词法遮罩的唯一实现；调用方选择是否遮蔽字面量、保留注释链接。"""
    out = list(text)

    def mask(start: int, end: int) -> None:
        for index in range(start, min(end, len(text))):
            if text[index] not in "\r\n":
                out[index] = " "

    # 每层记录模式与参数：代码 / 块注释的嵌套深度，或字符串的模板美元数。
    contexts = [("code", 0)]
    kdoc = False
    i = 0
    while i < len(text):
        mode, depth = contexts[-1]
        c = text[i]
        if mode == "line":
            if c not in "\r\n":
                out[i] = " "
            else:
                contexts.pop()
            i += 1
        elif mode == "block":
            if text.startswith("/*", i):
                mask(i, i + 2)
                contexts[-1] = (mode, depth + 1)
                i += 2
            elif text.startswith("*/", i):
                mask(i, i + 2)
                if depth == 1:
                    contexts.pop()
                else:
                    contexts[-1] = (mode, depth - 1)
                i += 2
            elif kdoc and (link := KDOC_LINK.match(text, i)):
                i = link.end()
            else:
                if c not in "\r\n":
                    out[i] = " "
                i += 1
        elif mode == "template_identifier":
            if c == "_" or c.isalnum():
                i += 1
            else:
                contexts.pop()
        elif mode in {"string", "raw", "char", "identifier"}:
            if mode in {"string", "char"} and c == "\\":
                if literals:
                    mask(i, i + 2)
                i += 2
            elif mode == "raw" and text.startswith('"""', i):
                if literals:
                    mask(i, i + 3)
                contexts.pop()
                i += 3
                # 结束前可有连续引号作为内容，整段引号都属于同一个字符串。
                while i < len(text) and text[i] == '"':
                    if literals:
                        out[i] = " "
                    i += 1
            elif (mode == "string" and c == '"') or (mode == "char" and c == "'") or (mode == "identifier" and c == "`"):
                if literals and mode != "identifier":
                    out[i] = " "
                contexts.pop()
                i += 1
            elif mode in {"string", "raw"} and c == "$":
                start = i
                while i < len(text) and text[i] == "$":
                    i += 1
                if literals:
                    mask(start, i)
                if i < len(text) and i - start >= depth:
                    if text[i] == "{":
                        if literals:
                            out[i] = " "
                        contexts.append(("code", 1))
                        i += 1
                    elif text[i] == "`":
                        contexts.append(("identifier", 0))
                        i += 1
                    elif text[i] == "_" or text[i].isalpha():
                        contexts.append(("template_identifier", 0))
            else:
                if literals and mode != "identifier" and c not in "\r\n":
                    out[i] = " "
                i += 1
        elif text.startswith("//", i):
            mask(i, i + 2)
            contexts.append(("line", 0))
            i += 2
        elif text.startswith("/*", i):
            mask(i, i + 2)
            contexts.append(("block", 1))
            # 只有 KDoc（`/** … */`）里的 `[Name]` 是链接；普通注释里的方括号是文字。
            kdoc = comment_links and text.startswith("/**", i) and not text.startswith("/**/", i)
            i += 2
        elif c == "$":
            start = i
            while i < len(text) and text[i] == "$":
                i += 1
            if i < len(text) and text[i] == '"':
                raw = text.startswith('"""', i)
                contexts.append(("raw" if raw else "string", i - start))
                if literals:
                    mask(start, i + (3 if raw else 1))
                i += 3 if raw else 1
        elif text.startswith('"""', i):
            contexts.append(("raw", 1))
            if literals:
                mask(i, i + 3)
            i += 3
        elif c in {'"', "'", "`"}:
            contexts.append(({'"': "string", "'": "char", "`": "identifier"}[c], 1 if c == '"' else 0))
            if literals and c != "`":
                out[i] = " "
            i += 1
        else:
            if depth and c == "{":
                contexts[-1] = (mode, depth + 1)
            elif depth and c == "}":
                if literals:
                    out[i] = " "
                if depth == 1:
                    contexts.pop()
                else:
                    contexts[-1] = (mode, depth - 1)
            i += 1
    return "".join(out)


def strip_comments(text: str) -> str:
    """真实注释按原长度替换为空格，保留 CR / LF、字面量与代码。"""
    return _mask_kotlin(text, literals=False, comment_links=False)


def mask_literals(text: str) -> str:
    """只留代码：注释和字面量都按原长度替换为空格，模板里的名称与表达式保留，保留 CR / LF。"""
    return _mask_kotlin(text, literals=True, comment_links=False)


def import_usage(text: str) -> str:
    """保留代码、模板名称/表达式与 KDoc 链接（`/** [Name] */`），遮蔽其余注释和字面量。"""
    return _mask_kotlin(text, literals=True, comment_links=True)
