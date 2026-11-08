"""分隔号门禁（唯一实现处；tools/ci/check.sh lint 运行，单跑：python3 tools/guards/check_separators.py）。

分隔点不用：「·」难看，能折行的句子里它会悬在行尾；单行元信息的段与段之间只留空白。全 App 的约定：
- R1 单行元信息（不会也不该折行的一行短信息）：用 core:ui 的 `MetaText`，各段分别传入，段间空白由它留；
- R2 可能折行的句子（提示、说明、tooltip、标题）：不用任何分隔点——并列用「、」，句子之间用「，」或「；」，
  补充说明用全角括号，信息多的拆成两行。

检查项（圆点：「·」与同类的「•」「・」「⋅」「∙」「‧」「･」）：
1. 主源码（sources.main_sources，去掉注释后）里不得出现圆点或其 `\\u` 转义。
2. 各模块任意源集 res/values*/ 下的资源 XML（去掉 XML 注释后）里不得出现圆点、其 `\\u` 转义或字符实体。
没有放行的文件：MetaText 自己也不用分隔点。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

from sources import PROJECT_ROOTS, main_sources, strip_comments

# 「·」与它的同类圆点（• 项目符号、・ 片假名中点、⋅ 点运算符、∙ 圆点运算符、‧ 连字点、･ 半角中点）：
# 换个字符写的分隔点一样会悬在行尾。按码位列一次，字符、转义与字符实体都由它推出。
DOT_CODES = (0x00B7, 0x2022, 0x30FB, 0x22C5, 0x2219, 0x2027, 0xFF65)
DOT_CHARS = "[" + "".join(chr(code) for code in DOT_CODES) + "]"
# Kotlin 与 Android 资源都认的 `\u00b7` 转义（大小写不限）
DOT_ESCAPE = r"\\u(?:" + "|".join(f"{code:04x}" for code in DOT_CODES) + ")"
# XML 字符实体，十进制与十六进制都可带前导零
DOT_ENTITY = (
    r"&#(?:0*(?:" + "|".join(str(code) for code in DOT_CODES) + r")"
    r"|x0*(?:" + "|".join(f"{code:x}" for code in DOT_CODES) + r"));"
)
KOTLIN_MIDDLE_DOT = re.compile(f"{DOT_CHARS}|{DOT_ESCAPE}", re.I)
XML_MIDDLE_DOT = re.compile(f"{DOT_CHARS}|{DOT_ESCAPE}|{DOT_ENTITY}", re.I)
HINT = "不用分隔点：单行元信息用 MetaText 分段（R1，段间空白由它留），会折行的句子改用「、」「，」「；」或全角括号（R2）"


def string_resources(root: Path) -> list[Path]:
    """各模块任意源集（main、debug……）的 values*/ 资源 XML：字符串、复数、数组都可能带文字。"""
    return sorted({p for pattern in PROJECT_ROOTS for p in root.glob(f"{pattern}/src/*/res/values*/*.xml")})


def strip_xml_comments(text: str) -> str:
    return re.sub(r"<!--.*?-->", lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S)


def find_violations(root: Path) -> list[str]:
    violations: list[str] = []
    scans = [(p, strip_comments, KOTLIN_MIDDLE_DOT) for p in main_sources(root)]
    scans += [(p, strip_xml_comments, XML_MIDDLE_DOT) for p in string_resources(root)]
    for path, strip, pattern in scans:
        rel = path.relative_to(root).as_posix()
        text = strip(path.read_text(encoding="utf-8"))
        hits = [text[: m.start()].count("\n") + 1 for m in pattern.finditer(text)]
        for line in sorted(set(hits)):
            violations.append(f"{rel}:{line}: 出现了分隔点——{HINT}")
    return violations


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    violations = find_violations(root)
    for v in violations:
        print(v, file=sys.stderr)
    if violations:
        print(f"\n分隔号门禁未通过：{len(violations)} 处（规则见 core:ui MetaText.kt）", file=sys.stderr)
        return 1
    print("分隔号门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
