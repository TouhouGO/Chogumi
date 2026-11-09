"""网络线路边界门禁（唯一实现处；tools/ci/check.sh lint 运行，单跑：python3 tools/guards/check_netroute.py）。

直连核心（Go，gomobile 生成的 echcore.aar）是实验性的，受网络与地区影响：整个应用里只有 core:netroute 认识它。网络层只认
core:network 的 `OkHttpCustomizer`，设置页与登录页只认 core:netroute 的公开接口（`NetRoute`、`RouteMode`、
`directRouteSupported`）。要拿掉直连，删掉这个模块与它的接入点即可，别处没有它的影子。

检查项：
1. core/netroute 以外的 Kotlin（主源码与测试源码）不引用核心的包 `io.github.touhougo.chogumi.ech`、也不写 `Echcore`
   （import、全限定名、起别名都算；注释与字符串不算）。
2. core/netroute/build.gradle.kts 以外的构建脚本不依赖 echcore.aar。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

from sources import kotlin_typealiases, main_sources, normalize_code, strip_comments, test_sources

OWNER = "core/netroute/"
CORE = re.compile(r"\bio\s*\.\s*github\s*\.\s*touhougo\s*\.\s*chogumi\s*\.\s*ech\b|\bEchcore\b")
AAR = re.compile(r"echcore\.aar")
HINT = "直连核心只有 core:netroute 认识；网络层用 OkHttpCustomizer，界面用 NetRoute / directRouteSupported"


def _kotlin(root: Path) -> list[Path]:
    return sorted(set(main_sources(root)) | set(test_sources(root)))


def find_violations(root: Path) -> list[str]:
    violations: list[str] = []
    paths = _kotlin(root)
    typealiases = kotlin_typealiases(paths)
    for path in paths:
        relative = path.relative_to(root).as_posix()
        if relative.startswith(OWNER):
            continue
        text = normalize_code(path.read_text(encoding="utf-8"), typealiases)
        for m in CORE.finditer(text):
            line = text.count("\n", 0, m.start()) + 1
            violations.append(f"{relative}:{line}: {HINT}")
    for script in sorted(root.glob("**/*.gradle.kts")):
        relative = script.relative_to(root).as_posix()
        if relative.startswith(OWNER) or "/build/" in f"/{relative}":
            continue
        text = strip_comments(script.read_text(encoding="utf-8"))
        for m in AAR.finditer(text):
            line = text.count("\n", 0, m.start()) + 1
            violations.append(f"{relative}:{line}: echcore.aar 只由 core/netroute/build.gradle.kts 依赖")
    return violations


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    violations = find_violations(root)
    for v in violations:
        print(v, file=sys.stderr)
    if violations:
        print(f"\n网络线路边界门禁未通过：{len(violations)} 处", file=sys.stderr)
        return 1
    print("网络线路边界门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
