"""字阶与圆角门禁（唯一实现处；tools/ci/check.sh lint 运行，单跑：python3 tools/guards/check_type_shape.py）。

规矩见 docs/design/typography.md 与 docs/code-standards.md：字号、字重只取 M3 字阶（强调态用 `*Emphasized`），圆角等于 M3 圆角阶梯的
一律取 token（`MaterialTheme.shapes.*`，非组合处用 `ShapeDefaults.*`）。各处自造的字重与圆角字面量迟早与字阶、
阶梯对不上，改主题时也跟不过来。

检查项（主源码 app / core / feature 的 src/main 下 .kt，去掉注释后）：
1. 写死字号：`fontSize = 14.sp`、`1.2.em`、`TextUnit(…)`，或先放进同文件常量再传（`val Big = 20.sp` + `fontSize = Big`）。
   随内容走的字号（BBCode 的 [size] 按 em 换算的变量）不拦。
2. 自造字重：`Text(…)`、`TextStyle(…)`、字阶样式 `.copy(…)` / `.merge(…)` 的顶层实参里有 `fontWeight = …`（实参按括号配对切分，
   嵌套多深都一样）。正文里的局部加粗（`SpanStyle(fontWeight = …)`：BBCode 的 [b]、句中的人名与数字）是内容排版，不拦，
   嵌在上面几种调用的参数里也不拦。
3. 圆角字面量等于 M3 阶梯（4 / 8 / 12 / 16 / 28 dp，以及 M3 Expressive 加的 20 / 32 / 48 dp，`8.0.dp`、`8f.dp` 同值）：
   `RoundedCornerShape(8.dp)`（含 `AbsoluteRoundedCornerShape`、`size =`、`CornerSize(…)` 的写法）应写 token；
   先把阶梯值放进同文件的大写常量再传（`val Corner = 8.dp` + `RoundedCornerShape(Corner)`）也一样。
   常量本身可以留着给要 Dp 的地方用（如 `posterCoverShape`），只是画形状时取 token。
   分角写法同样：`RoundedCornerShape(topStart = …, bottomEnd = 16.dp)` 里某一角等于阶梯，就从 token 改角
   （`ShapeDefaults.Large.copy(topStart = …)`），阶梯上的那几角留给 token。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

from sources import NAMED_ARGUMENT, call_arguments, kotlin_typealiases, main_sources, normalize_code

STEPS = r"(4|8|12|16|20|28|32|48)(?:\.0+)?[fF]?\.dp"
CORNER_ARGUMENT = r"\(\s*(?:size\s*=\s*)?(?:CornerSize\s*\(\s*)?"
RULES = [
    (re.compile(r"\bfontSize\s*=\s*(?:\d+(?:\.\d+)?[fF]?\.(?:sp|em)\b|TextUnit\s*\()"), "写死了字号——用 MaterialTheme.typography 的字阶"),
    (re.compile(r"RoundedCornerShape\s*" + CORNER_ARGUMENT + STEPS + r"\s*\)?\s*\)"), "圆角字面量等于 M3 阶梯——用 MaterialTheme.shapes.*（非组合处 ShapeDefaults.*）"),
]
# 顶层实参里有 fontWeight 就是自造字重；SpanStyle 不在这里（内容排版）
WEIGHT_CALLS = {
    "Text": "给 Text 单独传 fontWeight——强调态用对应的 *Emphasized 样式",
    "TextStyle": "TextStyle 自带 fontWeight 自造字重——强调态用对应的 *Emphasized 样式",
    "copy": "在字阶上 copy(fontWeight = …) 自造字重——强调态用对应的 *Emphasized 样式",
    "merge": "在字阶上 merge(fontWeight = …) 自造字重——强调态用对应的 *Emphasized 样式",
}
CORNER_SHAPE_CALL = re.compile(r"\b(?:Absolute)?RoundedCornerShape\s*\(")
CORNER_NAMES = {"topStart", "topEnd", "bottomStart", "bottomEnd", "topLeft", "topRight", "bottomLeft", "bottomRight"}
STEP_VALUE = re.compile(r"^\s*(?:CornerSize\s*\(\s*)?" + STEPS + r"\s*\)?\s*$")
WEIGHT_CALL = re.compile(r"\b(Text|TextStyle)\s*\(|\.\s*(copy|merge)\s*\(")
STEP_CONSTANT = re.compile(r"\bval\s+([A-Z]\w*)\s*(?::\s*Dp\s*)?=\s*" + STEPS + r"\b")
SHAPE_OF_CONSTANT = re.compile(r"RoundedCornerShape\s*" + CORNER_ARGUMENT + r"([A-Z]\w*)\s*\)?\s*\)")
SIZE_CONSTANT = re.compile(r"\bval\s+([A-Z]\w*)\s*(?::\s*TextUnit\s*)?=\s*\d+(?:\.\d+)?[fF]?\.(?:sp|em)\b")
SIZE_OF_CONSTANT = re.compile(r"\bfontSize\s*=\s*([A-Z]\w*)\b(?!\s*[.(])")
SHAPE_TOKEN = {
    "4": "extraSmall", "8": "small", "12": "medium", "16": "large", "20": "largeIncreased", "28": "extraLarge",
    "32": "extraLargeIncreased", "48": "extraExtraLarge",
}


def line_of(text: str, index: int) -> int:
    return text.count("\n", 0, index) + 1


def find_violations(root: Path) -> list[str]:
    violations: list[str] = []
    paths = main_sources(root)
    typealiases = kotlin_typealiases(paths)
    for path in paths:
        rel = path.relative_to(root).as_posix()
        text = normalize_code(path.read_text(encoding="utf-8"), typealiases)
        for pattern, what in RULES:
            for m in pattern.finditer(text):
                hint = what
                if m.lastindex and m.group(1) in SHAPE_TOKEN:
                    hint += f"（{m.group(1)}dp = {SHAPE_TOKEN[m.group(1)]}）"
                violations.append(f"{rel}:{line_of(text, m.start())}: {hint}")
        for m in CORNER_SHAPE_CALL.finditer(text):
            for argument in call_arguments(text, m.end() - 1):
                named = NAMED_ARGUMENT.match(argument)
                if not named or named.group(1) not in CORNER_NAMES:
                    continue
                step = STEP_VALUE.match(argument[named.end():])
                if step:
                    violations.append(
                        f"{rel}:{line_of(text, m.start())}: 分角圆角 {named.group(1)} 等于 M3 阶梯（{step.group(1)}dp = "
                        f"{SHAPE_TOKEN[step.group(1)]}）——从 token 改角：ShapeDefaults.*.copy(…) / MaterialTheme.shapes.*.copy(…)"
                    )
        for m in WEIGHT_CALL.finditer(text):
            arguments = call_arguments(text, m.end() - 1)
            if any((named := NAMED_ARGUMENT.match(argument)) and named.group(1) == "fontWeight" for argument in arguments):
                violations.append(f"{rel}:{line_of(text, m.start())}: {WEIGHT_CALLS[m.group(1) or m.group(2)]}")
        steps = {m.group(1): m.group(2) for m in STEP_CONSTANT.finditer(text)}
        for m in SHAPE_OF_CONSTANT.finditer(text):
            step = steps.get(m.group(1))
            if step is not None:
                violations.append(
                    f"{rel}:{line_of(text, m.start())}: 圆角常量 {m.group(1)} = {step}.dp 等于 M3 阶梯——画形状用 MaterialTheme.shapes."
                    f"{SHAPE_TOKEN[step]}（非组合处 ShapeDefaults.*）"
                )
        sizes = {m.group(1) for m in SIZE_CONSTANT.finditer(text)}
        for m in SIZE_OF_CONSTANT.finditer(text):
            if m.group(1) in sizes:
                violations.append(f"{rel}:{line_of(text, m.start())}: 字号常量 {m.group(1)} 写死了字号——用 MaterialTheme.typography 的字阶")
    return violations


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    violations = find_violations(root)
    for v in violations:
        print(v, file=sys.stderr)
    if violations:
        print(f"\n字阶与圆角门禁未通过：{len(violations)} 处（见 docs/design/typography.md）", file=sys.stderr)
        return 1
    print("字阶与圆角门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
