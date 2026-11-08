"""文档链接门禁（唯一实现处；tools/ci/check.sh lint 运行，单跑：python3 tools/guards/check_doc_links.py）。

AGENTS.md 是路由表：做一件事之前读哪一页。路由表指向不存在的页、文档之间的链接断掉、代码注释或门禁说明里引用的章节名
在文档里已经改掉，读到的人就被引去找不存在的东西。

检查项：
1. 被跟踪的 .md 里每个相对链接（`[文字](路径)`、`![图](路径)`、引用式链接的定义 `[名字]: 路径`）指向的文件存在；带 `#锚点` 时，目标文件里有一个标题能生成这个锚点
   （GitHub 的生成法：去掉代码与加粗标记，小写，只留字母、数字、空格、连字符与下划线，空格换成连字符）。网址（http、https、mailto）不查。
2. docs/ 下的每份 .md 与 LESSONS.md 都被 AGENTS.md 直接链接到：没路由到的文档没人会读。
3. 源码、脚本、构建配置、工作流与文档（后缀见 REFERENCE_SUFFIXES）里写成 `<仓库里的 .md>「章节名」` 的引用（链接后面紧跟「章节名」也算；「规范」「流程」是
   docs/code-standards.md 与 docs/dev/workflow.md 的简称）：那份文档存在，且里面有一个标题、加粗的条目名或表格首列包含这个章节名。
   章节改名时这些引用跟着改，不留指向旧名的指针。只认仓库里的文档：根目录的几份、相对路径（`../LESSONS.md`）、第一段是仓库里
   被跟踪的目录的路径（`docs/…`）；光秃秃的文件名与别的目录（M3 规范副本的 `tokens.md「…」`、`references/…`）、没被跟踪的
   `.devdoc/` 下的记录都在仓库外，不查。

围栏代码块与行内代码里的 Markdown 不当链接看；第 3 项的引用写在行内代码里照样查，围栏代码块里的不查。门禁自测里故意写的样例（tools/guards/test_*.py）不查第 3 项。
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROUTER = "AGENTS.md"
ROUTED_PREFIX = "docs/"
ROUTED_FILES = ("LESSONS.md",)
REFERENCE_SUFFIXES = {".md", ".py", ".kt", ".kts", ".sh", ".yml", ".yaml", ".mjs", ".js", ".pro", ".xml", ".toml", ".properties"}
ALIASES = {"规范": "docs/code-standards.md", "流程": "docs/dev/workflow.md"}
ROOT_DOCS = {"AGENTS.md", "LESSONS.md", "README.md", "CLAUDE.md", "CONTRIBUTING.md", "SECURITY.md", "PRIVACY.md"}
GUARD_SELF_TESTS = "tools/guards/test_"
EXTERNAL = ("http://", "https://", "mailto:")

FENCE = re.compile(r"^(```|~~~)[^\n]*\n.*?^\1[ \t]*$", re.M | re.S)
INLINE_CODE = re.compile(r"`[^`\n]*`")
LINK = re.compile(r"!?\[[^\]\n]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
# 引用式链接的定义：`[名字]: 路径`
LINK_DEFINITION = re.compile(r"^ {0,3}\[[^\]\n]+\]:[ \t]*<?([^>\s]+)>?")
HEADING = re.compile(r"^#{1,6}[ \t]+(.+?)[ \t]*#*[ \t]*$", re.M)
BOLD = re.compile(r"\*\*([^*\n]+?)\*\*")
TABLE_CELL = re.compile(r"^\|[ \t]*([^|\n]+?)[ \t]*\|", re.M)
REFERENCE = re.compile(r"(?:((?:[\w.-]+/)*[\w.-]+\.md)\)?|(规范|流程))[ \t]*「([^」\n]+)」")


def tracked_files(root: Path) -> list[str]:
    return subprocess.run(["git", "ls-files"], cwd=root, capture_output=True, text=True, check=True).stdout.splitlines()


def anchor_of(heading: str) -> str:
    text = heading.replace("`", "").replace("*", "").lower()
    return "".join(ch for ch in text if ch.isalnum() or ch in " -_").replace(" ", "-")


def blank_lines(match: re.Match[str]) -> str:
    return "\n" * match.group(0).count("\n")


def prose_of(markdown: str) -> str:
    """去掉围栏代码块与行内代码之后的正文（行数不变，便于报行号）：找链接用。"""
    return INLINE_CODE.sub("", FENCE.sub(blank_lines, markdown))


def reference_prose_of(markdown: str) -> str:
    """去掉围栏代码块、只去掉行内代码的反引号（行数不变）：找「章节名」引用用，写在行内代码里的引用也是引用。"""
    return INLINE_CODE.sub(lambda match: match.group(0)[1:-1], FENCE.sub(blank_lines, markdown))


def link_targets(line: str) -> list[str]:
    return [*LINK.findall(line), *LINK_DEFINITION.findall(line)]


def titles_of(markdown: str) -> list[str]:
    """能被「章节名」引用的文字：标题、加粗的条目名、表格首列；都去掉代码标记。"""
    prose = FENCE.sub("", markdown)
    found = [*HEADING.findall(prose), *BOLD.findall(prose), *TABLE_CELL.findall(prose)]
    return [title.replace("`", "") for title in found]


def in_repo(doc: str, tracked_dirs: set[str]) -> bool:
    """引用的是仓库里的文档：根目录的那几份、相对路径、或第一段是被跟踪的目录；其余（光秃秃的文件名、仓库外的目录）不查。"""
    first = doc.split("/")[0]
    return doc in ROOT_DOCS or first in (".", "..") or first in tracked_dirs


def resolve(root: Path, referrer: str, target: str) -> Path | None:
    """先按引用文件所在目录解析（文档里的相对路径），再按仓库根解析（源码里的引用）。"""
    for base in (Path(referrer).parent, Path()):
        candidate = root / base / target
        if candidate.is_file():
            return candidate
    return None


def link_violations(root: Path, rel: str, text: str) -> list[str]:
    violations: list[str] = []
    for line_no, line in enumerate(text.splitlines(), 1):
        for target in link_targets(line):
            if target.startswith(EXTERNAL):
                continue
            path_part, _, anchor = target.partition("#")
            if path_part:
                found = (root / Path(rel).parent / path_part)
                if not found.is_file():
                    violations.append(f"{rel}:{line_no}: 链接 `{target}` 指向的文件不存在")
                    continue
            else:
                found = root / rel
            if anchor and anchor not in {anchor_of(h) for h in HEADING.findall(FENCE.sub("", found.read_text(encoding='utf-8')))}:
                violations.append(f"{rel}:{line_no}: 链接 `{target}` 的锚点在目标文件里没有对应的标题")
    return violations


def reference_violations(root: Path, rel: str, text: str, tracked_dirs: set[str]) -> list[str]:
    violations: list[str] = []
    for line_no, line in enumerate(text.splitlines(), 1):
        for match in REFERENCE.finditer(line):
            doc, alias, title = match.groups()
            if alias:
                doc = ALIASES[alias]
            if not in_repo(doc, tracked_dirs):
                continue
            found = resolve(root, rel, doc)
            if found is None:
                violations.append(f"{rel}:{line_no}: 引用的文档 `{doc}` 不存在")
                continue
            if not any(title in candidate for candidate in titles_of(found.read_text(encoding="utf-8"))):
                violations.append(f"{rel}:{line_no}: `{doc}` 里没有叫「{title}」的章节或条目——章节改名时把引用一起改")
    return violations


def routing_violations(root: Path, files: list[str]) -> list[str]:
    router = root / ROUTER
    if not router.is_file():
        return [f"{ROUTER}: 路由表不存在"]
    linked = {
        Path(target.partition("#")[0]).as_posix()
        for line in prose_of(router.read_text(encoding="utf-8")).splitlines()
        for target in link_targets(line)
    }
    routed = [f for f in files if f.endswith(".md") and (f.startswith(ROUTED_PREFIX) or f in ROUTED_FILES)]
    return [f"{ROUTER}: 没有路由到 {doc}——每份文档都要从路由表进得去" for doc in routed if doc not in linked]


def find_violations(root: Path, files: list[str]) -> list[str]:
    violations = routing_violations(root, files)
    tracked_dirs = {f.split("/")[0] for f in files if "/" in f}
    for rel in files:
        if Path(rel).suffix not in REFERENCE_SUFFIXES:
            continue
        path = root / rel
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8")
        if rel.endswith(".md"):
            violations.extend(link_violations(root, rel, prose_of(text)))
        if not rel.startswith(GUARD_SELF_TESTS):
            violations.extend(reference_violations(root, rel, reference_prose_of(text) if rel.endswith(".md") else text, tracked_dirs))
    return violations


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    violations = find_violations(root, tracked_files(root))
    for v in violations:
        print(v, file=sys.stderr)
    if violations:
        print(f"\n文档链接门禁未通过：{len(violations)} 处", file=sys.stderr)
        return 1
    print("文档链接门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
