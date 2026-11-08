"""时间线大类门禁（唯一实现处；tools/ci/check.sh lint 运行，单跑：python3 tools/guards/check_timeline_category.py）。

规矩见 docs/code-standards.md 红线 1「单一真源」：服务端给时间线动态分的大类（`cat`）编号只在 core:model 的
`timelineCategoryOf` 里认，得到 `TimelineCategory`。渲染、合并、数据层各自拿 `cat` 去比裸数字，服务端改编号时
要改好几处，漏一处就是把动态画成另一种。

检查项（主源码 app / core / feature 的 src/main 下 .kt，去掉注释后）：`cat` 与数字字面量或全大写常量（`CAT_STATUS`）比较
（`==` / `!=` / `>` / `<`，数字在哪边都算）、`.equals(数字)`、`in` / `!in` 区间或 `setOf(…)` / `arrayOf(…)` 这类 `…Of(…)` 集合，
以及 `when (cat)` / `when (item?.a.cat!!)` / `when (val c = item.cat)` 直接分支；点号与运算符两侧可以换行。
认的是名字 `cat`：别的接口里同名的 `cat`（小组、目录收录的分类）也按这条写，先经各自的解析函数。
没有放行：编号的定义处（Timeline.kt）也不写这些形状。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

from sources import kotlin_typealiases, main_sources, normalize_code

# 数字字面量，或全大写的常量名（后面不接成员访问与调用）
NUMBER = r"(?:-?\d|[A-Z][A-Z0-9_]*\b(?!\s*[.(]))"
CAT = r"(?<![\w])cat\s*(?:!!\s*)?"
COMPARE = re.compile(
    CAT + r"(?:(?:[!=]==?|[<>]=?)\s*" + NUMBER + r"|!?in\s+(?:-?\d|\w*Of\s*\())"
    r"|" + CAT + r"\.\s*equals\s*\(\s*" + NUMBER
    + r"|(?<![\w.])(?:-?\d+|[A-Z][A-Z0-9_]*)\s*(?:[!=]==?|[<>]=?)\s*(?:\w+\s*(?:!!)?\s*\??\s*\.\s*)*cat\b"
)
WHEN = re.compile(
    r"\bwhen\s*\(\s*(?:val\s+\w+\s*(?::\s*\w+\??\s*)?=\s*)?(?:\w+\s*(?:!!)?\s*\??\s*\.\s*)*cat\s*(?:!!\s*)?\)"
)
HINT = "大类编号只在 core:model 的 timelineCategoryOf 里认：先 timelineCategoryOf(cat)，再比 TimelineCategory"


def find_violations(root: Path) -> list[str]:
    violations: list[str] = []
    paths = main_sources(root)
    typealiases = kotlin_typealiases(paths)
    for path in paths:
        rel = path.relative_to(root).as_posix()
        text = normalize_code(path.read_text(encoding="utf-8"), typealiases)
        for pattern in (COMPARE, WHEN):
            for m in pattern.finditer(text):
                line = text[: m.start()].count("\n") + 1
                violations.append(f"{rel}:{line}: 拿 cat 比裸数字——{HINT}")
    return violations


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    violations = find_violations(root)
    for v in violations:
        print(v, file=sys.stderr)
    if violations:
        print(f"\n时间线大类门禁未通过：{len(violations)} 处（规矩见 docs/code-standards.md 红线 1「单一真源」）", file=sys.stderr)
        return 1
    print("时间线大类门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
