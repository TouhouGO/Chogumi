"""底部面板门禁（唯一实现处；tools/ci/check.sh lint 运行，单跑：python3 tools/guards/check_sheets.py）。

规矩见 docs/code-standards.md「底部面板只有一种」。背景：官方 ModalBottomSheet 是独立系统窗口，
压在所有页面之上；从面板进子页再返回，窗口要重建（约 100ms 空档），跳走时又会盖住新页；
平板上它按整个窗口居中、遮罩盖住导航轨，与画在页面区里的面板位置不一致。
所以所有底部面板都画进所在页的面板层（PageModalSheet / ScrollableModalSheet），不跳页的也一样。

检查项：
1. 主源码不得用官方 ModalBottomSheet 或 BottomSheetScaffold（换个名字导入也算）。
2. 主源码不得读 LocalNavAnimatedContentScope：页面元素不许自己按导航转场做显隐时序（那是调窗口时序的补丁）。
   只有两处可以读，都不碰面板：PageExpand.kt 给容器转换取共享元素需要的可见性作用域；
   EnteringBack.kt 看这一页是否还在进场，进场途中的返回按普通返回走（不从预测性返回的预览起点跳过去）。
3. AppNavigator 必须调用页面面板层装饰器（rememberPageSheetNavEntryDecorator(…)；只导入不算），否则页面面板无处可画。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

from sources import kotlin_typealiases, main_sources, normalize_code

WINDOW_SHEET = re.compile(r"\b(ModalBottomSheet|BottomSheetScaffold)\s*\(")
NAV_TRANSITION_READ = re.compile(r"\bLocalNavAnimatedContentScope\b")
NAV_TRANSITION_READERS = {
    "core/ui/src/main/kotlin/io/github/touhougo/chogumi/core/ui/common/navigation/PageExpand.kt",
    "core/ui/src/main/kotlin/io/github/touhougo/chogumi/core/ui/common/navigation/EnteringBack.kt",
}
DECORATOR = "rememberPageSheetNavEntryDecorator"
DECORATOR_CALL = re.compile(r"\b" + DECORATOR + r"\s*(?:<[^<>()]*>\s*)?\(")
NAVIGATOR = "core/ui/src/main/kotlin/io/github/touhougo/chogumi/core/ui/common/navigation/AppNavigator.kt"


def find_violations(root: Path) -> list[str]:
    violations: list[str] = []
    paths = main_sources(root)
    typealiases = kotlin_typealiases(paths)
    for path in paths:
        rel = path.relative_to(root).as_posix()
        text = normalize_code(path.read_text(encoding="utf-8"), typealiases)
        if rel not in NAV_TRANSITION_READERS:
            for m in NAV_TRANSITION_READ.finditer(text):
                violations.append(
                    f"{rel}:{text.count(chr(10), 0, m.start()) + 1}: 读了 LocalNavAnimatedContentScope——页面元素不许按导航转场自己做显隐时序；"
                    "面板用 PageModalSheet，转场只在 ChogumiTransitions 定义"
                )
        for sheet in WINDOW_SHEET.finditer(text):
            violations.append(
                f"{rel}:{text.count(chr(10), 0, sheet.start()) + 1}: 用了官方 {sheet.group(1)}——底部面板一律用 PageModalSheet / "
                "ScrollableModalSheet（画在页面的面板层里），缺什么就扩展 PageModalSheet"
            )
    for rel in sorted(NAV_TRANSITION_READERS):
        reader = root / rel
        if not reader.exists():
            violations.append(f"放行文件 {rel} 不存在，从 NAV_TRANSITION_READERS 删掉这条（死配置）")
        elif not NAV_TRANSITION_READ.search(normalize_code(reader.read_text(encoding="utf-8"), typealiases)):
            violations.append(f"放行文件 {rel} 已不读 LocalNavAnimatedContentScope，从 NAV_TRANSITION_READERS 删掉这条（死配置）")
    navigator = root / NAVIGATOR
    if not navigator.exists() or not DECORATOR_CALL.search(normalize_code(navigator.read_text(encoding="utf-8"), typealiases)):
        violations.append(f"{NAVIGATOR}: 没挂 {DECORATOR}——页面面板无处可画")
    return violations


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    violations = find_violations(root)
    for v in violations:
        print(v, file=sys.stderr)
    if violations:
        print(f"\n底部面板门禁未通过：{len(violations)} 处（规矩见 docs/code-standards.md「底部面板只有一种」）", file=sys.stderr)
        return 1
    print("底部面板门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
