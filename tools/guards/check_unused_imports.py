"""未使用 import 门禁（唯一实现处；tools/ci/check.sh lint 运行，单跑：python3 tools/guards/check_unused_imports.py）。

Kotlin 编译器不报未使用的 import，替换、删除代码后它们会一直留着。
docs/code-standards.md 红线 2「不留死代码」：import 也算。

检查项：app / core / feature / baselineprofile / build-logic 下所有源码集的 .kt 文件里，
- 每条 import 的名字（或 `as` 别名）必须在文件正文的**代码**里出现过：注释与字符串的文字不算（只在注释里提到的名字
  照样是死 import），KDoc 的 `[Name]` 链接、字符串模板里的 `$name` / `${…}` 算。
- 不许通配导入（`import x.*`）：看不出用了哪些名字，这条门禁也就查不了；展开成具体名字。
- 不许导入与文件同包的名字：同包本来就可见，这行 import 多余。
运算符与委托约定函数（`getValue`、`plus`、`component1`…）由编译器隐式调用、正文里看不到名字，放行。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

from sources import import_usage, mask_literals, project_sources

HEADER_LINE = re.compile(r"^\s*(?:import|package)\s")
IMPORT = re.compile(r"^\s*import\s+([\w.`]+(?:\.\*)?)(?:\s+as\s+(`[^`]+`|\w+))?\s*$")
PACKAGE = re.compile(r"^\s*package\s+([\w.`]+)")

# 编译器按约定隐式调用的函数名：正文里看不到它们的名字，不能据此判为未使用
IMPLICIT = {
    "getValue", "setValue", "provideDelegate",
    "plus", "minus", "times", "div", "rem", "rangeTo", "rangeUntil",
    "unaryPlus", "unaryMinus", "not", "inc", "dec",
    "plusAssign", "minusAssign", "timesAssign", "divAssign", "remAssign",
    "contains", "get", "set", "invoke", "compareTo", "iterator", "next", "hasNext",
} | {f"component{i}" for i in range(1, 11)}

HINT = "删掉这行（红线 2：不留死代码）"


def unused_imports(text: str) -> list[tuple[int, str]]:
    """返回 (行号, 原行, 原因) 中的前两项：未用到、通配、同包导入。"""
    return [(number, line) for number, line, _ in import_problems(text)]


def import_problems(text: str) -> list[tuple[int, str, str]]:
    """导入行按代码视图认（字符串里像导入的行不算），一行里分号隔开的几条逐条检查；正文是去掉导入与包声明行之后的部分。"""
    lines = text.split("\n")
    code_lines = mask_literals(text).split("\n")
    header = {index for index, code in enumerate(code_lines) if HEADER_LINE.match(code)}
    package = next(filter(None, (PACKAGE.match(code_lines[index]) for index in sorted(header))), None)
    package_name = package.group(1).replace("`", "") if package else ""
    body = import_usage("\n".join("" if index in header else line for index, line in enumerate(lines)))
    found = []
    for index in sorted(header):
        number, line = index + 1, lines[index]
        for segment in code_lines[index].split(";"):
            m = IMPORT.match(segment)
            if not m:
                continue
            path = m.group(1).replace("`", "")
            if path.endswith(".*"):
                found.append((number, line, "通配导入——展开成用到的具体名字"))
                continue
            if path.rsplit(".", 1)[0] == package_name and not m.group(2):
                found.append((number, line, "导入与文件同包的名字——同包本来就可见，删掉这行"))
                continue
            name = (m.group(2) or path.split(".")[-1]).strip("`")
            if name in IMPLICIT:
                continue
            if not re.search(r"(?<!\w)" + re.escape(name) + r"(?!\w)", body):
                found.append((number, line, f"未使用——{HINT}"))
    return found


def find_violations(root: Path) -> list[str]:
    violations = []
    for path in project_sources(root):
        rel = path.relative_to(root).as_posix()
        for number, line, reason in import_problems(path.read_text(encoding="utf-8")):
            violations.append(f"{rel}:{number}: {line}：{reason}")
    return violations


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    violations = find_violations(root)
    for v in violations:
        print(v, file=sys.stderr)
    if violations:
        print(f"\n未使用 import 门禁未通过：{len(violations)} 处", file=sys.stderr)
        return 1
    print("未使用 import 门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
