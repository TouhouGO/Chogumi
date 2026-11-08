"""图片加载结果门禁（唯一实现处；tools/ci/check.sh lint 运行，单跑：python3 tools/guards/check_image_result_state.py）。

图片加载出来才知道多大时，这份结果决定所在列表项的高度。只记在组合里（`remember`）的话，列表项划出屏幕就丢了，
回来先按占位排、下一帧才换成真实尺寸；往回滑时这一项从顶上进来、随即缩出屏幕被丢掉，再进来又是占位，列表卡在那一段来回跳
（docs/code-standards.md「懒加载行回来时还是离开时的大小」）。所以加载结果跟着列表项存档（`rememberSaveable`）。

检查项：
1. `AsyncImage(…)` 与 Coil 请求 `.listener(…)` 的 `onSuccess` / `onState` 回调里赋值的状态（委托变量，或 `remember { mutable…StateOf }`
   的 `.value` / `.floatValue` 等），声明不得是 `remember`（要用 `rememberSaveable`）；声明可以带类型。
   只在 `onError` 里改的失败标记不拦：画失败的格子与图同大时（头像、标志、宫格）不改高度。
2. 不用 `SubcomposeAsyncImage` / `rememberAsyncImagePainter`：内容或画笔状态随加载变化，尺寸跟着走、存不了档。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

from sources import kotlin_typealiases, main_sources, normalize_code

RESULT_CALLBACK = re.compile(r"\bon(?:Success|State)\s*=\s*\{")
ASSIGNMENT = re.compile(r"(?<![\w.])(\w+)\s*=(?!=)")
VALUE_ASSIGNMENT = re.compile(r"(?<![\w.])(\w+)\s*\.\s*(?:value|floatValue|intValue|longValue|doubleValue)\s*=(?!=)")
# 加载结果从这些调用的参数里的回调出来
RESULT_SOURCE = re.compile(r"\bAsyncImage\s*\(|\.\s*listener\s*\(")
LOAD_FOLLOWING = re.compile(r"\b(SubcomposeAsyncImage|rememberAsyncImagePainter)\s*\(")
DECLARED_TYPE = r"\s*(?::[^=\n]*?)?"
REMEMBERED_VAR = re.compile(r"\bvar\s+(\w+)" + DECLARED_TYPE + r"\s+by\s+(?:\w+\.)*(remember|rememberSaveable)\b")
REMEMBERED_OBJECT = re.compile(
    r"\b(?:val|var)\s+(\w+)" + DECLARED_TYPE + r"\s*=\s*(?:[\w.]+\s*\.\s*)*remember\b[^{]*\{\s*"
    r"(?:[\w.]+\s*\.\s*)*mutable(?:Float|Int|Long|Double)?StateOf\b"
)


def closing(text: str, open_index: int, opening: str, closing_char: str) -> int:
    depth = 0
    for i in range(open_index, len(text)):
        if text[i] == opening:
            depth += 1
        elif text[i] == closing_char:
            depth -= 1
            if depth == 0:
                return i
    return len(text) - 1


def line_of(text: str, index: int) -> int:
    return text.count("\n", 0, index) + 1


def find_violations(root: Path) -> list[str]:
    violations: list[str] = []
    paths = main_sources(root)
    typealiases = kotlin_typealiases(paths)
    for path in paths:
        rel = path.relative_to(root).as_posix()
        text = normalize_code(path.read_text(encoding="utf-8"), typealiases)
        for m in LOAD_FOLLOWING.finditer(text):
            violations.append(
                f"{rel}:{line_of(text, m.start())}: {m.group(1)} 的内容随加载变化、尺寸存不了档"
                "——用 AsyncImage，尺寸由存档的加载结果决定"
            )
        remembered = {m.group(1): m.group(2) == "remember" for m in REMEMBERED_VAR.finditer(text)}
        remembered.update({m.group(1): True for m in REMEMBERED_OBJECT.finditer(text)})
        for call in RESULT_SOURCE.finditer(text):
            args_end = closing(text, call.end() - 1, "(", ")")
            for callback in RESULT_CALLBACK.finditer(text, call.end(), args_end):
                body_end = closing(text, callback.end() - 1, "{", "}")
                body = text[callback.end():body_end]
                direct = {a.group(1) for a in ASSIGNMENT.finditer(body)}
                delegated = {a.group(1) for a in VALUE_ASSIGNMENT.finditer(body)}
                for name in sorted(direct | delegated):
                    if remembered.get(name):
                        violations.append(
                            f"{rel}:{line_of(text, callback.start())}: 图片加载结果写进了 remember 的 {name}"
                            "——列表项划出屏幕就丢，回来先按占位排、再缩，往回滑会卡住；改用 rememberSaveable"
                        )
    return violations


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    violations = find_violations(root)
    for v in violations:
        print(v, file=sys.stderr)
    if violations:
        print(f"\n图片加载结果门禁未通过：{len(violations)} 处", file=sys.stderr)
        return 1
    print("图片加载结果门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
