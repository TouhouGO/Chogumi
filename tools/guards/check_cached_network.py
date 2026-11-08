"""缓存流 flowOn 门禁（唯一实现处；tools/ci/check.sh lint 运行，单跑：python3 tools/guards/check_cached_network.py）。

`cachedNetworkCall` 的读写与映射离开主线程只靠它的 `workContext` 参数。在它返回的流上接 `flowOn` 会在上下游之间加缓冲：
缓存拍一交出去上游就接着发网络请求，只要一个答案的 `settled()` 随后取消它，过期缓存的每次读取都白发一个请求
（见 core:data `util/CachedNetwork.kt`）。

检查项：同一个函数（或同一个类成员 / 顶层属性的初值）里既调用 `cachedNetworkCall(` 又出现 `.flowOn(`（点号前后可换行，
可是 `?.`）即失败。后处理要离开主线程时，在 `transform` / `map` 里用 `withContext(...)` 包住计算、在外面发射。

一段从一个声明开始，到下一个声明为止。声明是任意修饰符的 `fun`、属性 `val` / `var`、`init` 与 `class` / `object` /
`interface`；比上一个函数、属性或 `init` 缩进更深的属性和类型是它里面的局部声明，不切开它。只查 `cachedNetworkCall`
与 `flowOn` 写在同一段里的；把流交给另一个函数再接 `flowOn` 看不出来。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

from sources import kotlin_typealiases, main_sources, normalize_code

CALL = re.compile(r"\bcachedNetworkCall\s*\(")
FLOW_ON = re.compile(r"\.\s*flowOn\s*\(")
# 行首的声明：第 1 组缩进，第 2 组种类，第 3 组名字（init 没有）；注解与任意小写修饰符在前
DECL = re.compile(
    r"^([ \t]*)(?:@[\w.]+(?:\([^)\n]*\))?\s+)*(?:[a-z]+\s+)*?(fun|val|var|init|class|object|interface)\b"
    r"(?:\s+(?:<[^>]*>\s+)?(?:[\w.<>, ?]+\.)?(\w+))?",
    re.M,
)
TYPE_KINDS = {"class", "object", "interface"}
CHECKED_KINDS = {"fun", "val", "var"}


def segments(text: str) -> list[tuple[str, int, int]]:
    """函数与属性各自的一段：(名字, 起点, 终点)。局部声明不切段，类型声明只结束上一段。"""
    starts: list[re.Match[str]] = []
    body_indent: int | None = None
    for decl in DECL.finditer(text):
        indent = len(decl.group(1).expandtabs(4))
        kind = decl.group(2)
        if kind != "fun" and body_indent is not None and indent > body_indent:
            continue
        body_indent = None if kind in TYPE_KINDS else indent
        starts.append(decl)
    found = []
    for i, decl in enumerate(starts):
        if decl.group(2) in CHECKED_KINDS:
            end = starts[i + 1].start() if i + 1 < len(starts) else len(text)
            found.append((decl.group(3) or decl.group(2), decl.start(), end))
    return found


def find_violations(root: Path) -> list[str]:
    violations: list[str] = []
    paths = main_sources(root)
    typealiases = kotlin_typealiases(paths)
    for path in paths:
        text = normalize_code(path.read_text(encoding="utf-8"), typealiases)
        for name, start, end in segments(text):
            body = text[start:end]
            if not CALL.search(body):
                continue
            flow_on = FLOW_ON.search(body)
            if flow_on is None:
                continue
            line = text.count("\n", 0, start + flow_on.start()) + 1
            violations.append(
                f"{path.relative_to(root).as_posix()}:{line}: {name} 在 cachedNetworkCall 的流上接了 flowOn"
                "——过期缓存被 settled() 取用时会白发一个网络请求；改传 workContext，后处理在 withContext 里算、在外面发射"
            )
    return violations


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    violations = find_violations(root)
    for v in violations:
        print(v, file=sys.stderr)
    if violations:
        print(f"\n缓存流 flowOn 门禁未通过：{len(violations)} 处", file=sys.stderr)
        return 1
    print("缓存流 flowOn 门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
