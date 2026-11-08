"""容器底色门禁（唯一实现处；tools/ci/check.sh lint 运行，单跑：python3 tools/guards/check_surface_roles.py）。

`surfaceContainerLowest` 在浅色下是最亮的容器色（近白），在深色下却是最暗的一档（接近纯黑）：拿它当「浮在面板上的一块」，
深色下就是面板上的一块黑。浮起的块只经 core:ui `raisedContainer`（theme/Theme.kt）取色，它按明暗各取一档
（docs/code-standards.md「Material Design 3 / M3 Expressive」）。

检查项：主源码里除了配色的定义处（theme/Theme.kt、theme/PresetSchemes.kt），不出现 `surfaceContainerLowest`
（`colorScheme.surfaceContainerLowest`、`with(colorScheme) { surfaceContainerLowest }`、属性引用都算；注释与字符串不算）。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

from sources import kotlin_typealiases, main_sources, normalize_code

LOWEST = re.compile(r"\bsurfaceContainerLowest\b")
THEME = "core/ui/src/main/kotlin/io/github/touhougo/chogumi/core/ui/theme/"
ALLOWED = {THEME + "Theme.kt", THEME + "PresetSchemes.kt"}


def find_violations(root: Path) -> list[str]:
    violations: list[str] = []
    paths = main_sources(root)
    typealiases = kotlin_typealiases(paths)
    for path in paths:
        relative = path.relative_to(root).as_posix()
        if relative in ALLOWED:
            continue
        text = normalize_code(path.read_text(encoding="utf-8"), typealiases)
        for m in LOWEST.finditer(text):
            line = text.count("\n", 0, m.start()) + 1
            violations.append(
                f"{relative}:{line}: surfaceContainerLowest 在深色下接近纯黑——浮在容器上的块用 core:ui raisedContainer"
            )
    return violations


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    violations = find_violations(root)
    for v in violations:
        print(v, file=sys.stderr)
    if violations:
        print(f"\n容器底色门禁未通过：{len(violations)} 处", file=sys.stderr)
        return 1
    print("容器底色门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
