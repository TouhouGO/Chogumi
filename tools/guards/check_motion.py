"""动效规格门禁（唯一实现处；tools/ci/check.sh lint 运行，单跑：python3 tools/guards/check_motion.py）。

动效只有一套，系统关掉动画时一切瞬时。动画规格只在 core:ui `theme/Motion.kt` 的 `appMotionFor` 里
取值一次，`ChogumiTheme` 把方案交给 `MaterialTheme.motionScheme`、整套取值放进 `LocalAppMotion`（docs/code-standards.md「动效」）。
调用处自己写的弹簧或时长不跟系统的动画开关走：用户关了动画，它还在动。

共享元素 / 共享边界（容器转换）只在 core:ui `common/navigation/PageExpand.kt`：起点由 `SubjectPoster` 自带，
调用处不得自己写 `sharedElement(` / `sharedBounds(` / `sharedElementWithCallerManagedVisibility(`（键、认领、时长都在那一处）。

全局只有 M3 standard；会过冲的 expressive 只有 `AppMotion.expressive`（和包它的 `ExpressiveMotion { }`）一个名字，
只许出现在 `EXPRESSIVE_ALLOWED` 列的文件里（选集格子标上时弹一下与看完荡到 1.1、长按圆环展开、选择 FAB 菜单），
白名单里匹配不到的条目算死配置；放行的文件里也不拿它做 `animateContentSize`（过冲在缩小时切掉尾部内容）。

检查项：`theme/Motion.kt` 之外的主源码里不得出现 `spring(…)`、`tween(…)`、`keyframes`、`SpringSpec(…)`、`TweenSpec(…)`
（含 `Float…` 前缀的），也不得直接拿 `MotionScheme.standard()` / `MotionScheme.expressive()`；`theme/Motion.kt` 本身必须存在。

滚动和列表重排同理：`animateScrollToItem` / `animateScrollToPage` 不接受规格，程序触发的滚动只走 `AppMotion.scrollToItem` /
`scrollToPage`（系统关掉动画时直接到位）。凡是规格参数有默认值的动画（`CALL_PARAMETERS`：`animate…AsState`、`Animatable.animateTo`、
`animateScrollBy` / `animateScrollTo`、`animateItem`、`animateContentSize`、进出场的 `fadeIn` / `expand…` / `slide…` / `scale…`、
`Crossfade`、`AnimatedContent`、`Transition.animate…`）都必须显式传主题的规格：不写参数就是 Compose 自带的弹簧，
不来自主题的那两处。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

from sources import NAMED_ARGUMENT, call_arguments, kotlin_typealiases, main_sources, normalize_code

OWNER = "core/ui/src/main/kotlin/io/github/touhougo/chogumi/core/ui/theme/Motion.kt"
SHARED_OWNER = "core/ui/src/main/kotlin/io/github/touhougo/chogumi/core/ui/common/navigation/PageExpand.kt"
SHARED_TRANSITION = re.compile(r"\b(?:sharedElement|sharedBounds|sharedElementWithCallerManagedVisibility)\s*\(")

# 允许用 expressive 的文件（仓库路径）：写明哪一处动效按会过冲设计
EXPRESSIVE_ALLOWED = {
    "core/ui/src/main/kotlin/io/github/touhougo/chogumi/core/ui/composables/EpisodeTile.kt": "选集格子：标上弹一下、看完荡到 1.1 倍",
    "core/ui/src/main/kotlin/io/github/touhougo/chogumi/core/ui/collection/PosterMarkRing.kt": "长按圆环展开",
    "core/ui/src/main/kotlin/io/github/touhougo/chogumi/core/ui/composables/SelectionFabMenu.kt": "选择 FAB 菜单的展开收起",
    "core/glass/src/main/kotlin/io/github/touhougo/chogumi/core/glass/GlassTouch.kt": "玻璃按下浮起、松手落回时略过冲",
    "core/glass/src/main/kotlin/io/github/touhougo/chogumi/core/glass/GlassControls.kt": "玻璃胶囊里的放大镜浮成气泡、跟着手指滑动、到位回弹",
}
# `MotionScheme.expressive()` 由 LITERAL 报；这里抓的是 AppMotion 上的那份方案（点名或在 with 里直接取）
EXPRESSIVE_USE = re.compile(r"(?<![\w$])expressive\b(?!\s*\()|\bExpressiveMotion\b")

LITERAL = re.compile(
    r"\b(?:spring|tween|keyframes|keyframesWithSpline)\s*[<({]"
    r"|\b(?:Float)?(?:SpringSpec|TweenSpec|KeyframesSpec)\s*[<(]"
    r"|\bMotionScheme\s*\.\s*(?:standard|expressive)\s*\("
)


SCROLL_ANIMATION = re.compile(r"\banimateScrollTo(?:Item|Page)\s*\(")

# 规格参数有默认值的动画：（按位置的参数顺序, 必须显式给的规格参数）。顺序来自当前 Compose API；
# animateItem 的三种动画各自有默认规格，都必须显式指定。
_TARGET_THEN_SPEC = (("targetValue", "animationSpec"), {"animationSpec"})
_SPEC_FIRST = (("animationSpec",), {"animationSpec"})
_TRANSITION_SPEC_FIRST = (("transitionSpec",), {"transitionSpec"})
CALL_PARAMETERS = {
    "animateScrollBy": (("value", "animationSpec"), {"animationSpec"}),
    "animateScrollTo": (("value", "animationSpec"), {"animationSpec"}),
    "animateContentSize": (("animationSpec", "alignment", "finishedListener"), {"animationSpec"}),
    "animateItem": (("fadeInSpec", "placementSpec", "fadeOutSpec"), {"fadeInSpec", "placementSpec", "fadeOutSpec"}),
    "animateTo": _TARGET_THEN_SPEC,
    **{
        name: _TARGET_THEN_SPEC
        for name in (
            "animateFloatAsState", "animateDpAsState", "animateColorAsState", "animateIntAsState", "animateOffsetAsState",
            "animateIntOffsetAsState", "animateSizeAsState", "animateIntSizeAsState", "animateRectAsState",
        )
    },
    "animateValueAsState": (("targetValue", "typeConverter", "animationSpec"), {"animationSpec"}),
    **{
        name: _SPEC_FIRST
        for name in (
            "fadeIn", "fadeOut", "expandIn", "shrinkOut", "expandVertically", "shrinkVertically", "expandHorizontally",
            "shrinkHorizontally", "scaleIn", "scaleOut", "slideIn", "slideOut", "slideInVertically", "slideOutVertically",
            "slideInHorizontally", "slideOutHorizontally",
        )
    },
    "Crossfade": (("targetState", "modifier", "animationSpec"), {"animationSpec"}),
    "AnimatedContent": (("targetState", "modifier", "transitionSpec"), {"transitionSpec"}),
    # Transition.animate…：规格是第一个参数，目标值是尾随 lambda；只写尾随 lambda 的调用也是缺规格
    **{
        name: _TRANSITION_SPEC_FIRST
        for name in (
            "animateFloat", "animateDp", "animateColor", "animateInt", "animateOffset", "animateIntOffset", "animateSize",
            "animateIntSize", "animateRect",
        )
    },
    "animateValue": (("typeConverter", "transitionSpec"), {"transitionSpec"}),
}
SPEC_PARAMETER = re.compile(r"\b(" + "|".join(sorted(CALL_PARAMETERS)) + r")\s*([({])")
DECLARATION_PREFIX = re.compile(r"\bfun\s+[\w.<>,? ]*$")
EXPRESSIVE_SIZE = re.compile(r"(?<![\w$])expressive\b")
def has_explicit_specs(name: str, arguments: list[str]) -> bool:
    """命名实参与合法位置实参映射到同一 API 参数，非规格参数不能代替缺省规格。"""
    parameters, required = CALL_PARAMETERS[name]
    explicit: set[str] = set()
    for index, argument in enumerate(arguments):
        named = NAMED_ARGUMENT.match(argument)
        if named and argument[named.end():].strip():
            explicit.add(named.group(1))
        elif not named and argument and index < len(parameters):
            explicit.add(parameters[index])
    return required <= explicit


def line_of(text: str, index: int) -> int:
    return text.count("\n", 0, index) + 1


def find_violations(root: Path, expressive_allowed: dict[str, str] = EXPRESSIVE_ALLOWED) -> list[str]:
    violations: list[str] = []
    if not (root / OWNER).is_file():
        violations.append(f"{OWNER} 不存在：动效取值的唯一出处挪走了，同步改这条门禁的 OWNER")
    expressive_used: set[str] = set()
    paths = main_sources(root)
    typealiases = kotlin_typealiases(paths)
    for path in paths:
        rel = path.relative_to(root).as_posix()
        if rel == OWNER:
            continue
        text = normalize_code(path.read_text(encoding="utf-8"), typealiases)
        for match in EXPRESSIVE_USE.finditer(text):
            if rel in expressive_allowed:
                expressive_used.add(rel)
                continue
            violations.append(
                f"{rel}:{line_of(text, match.start())}: `{match.group(0)}` 只给 EXPRESSIVE_ALLOWED 列的位置用；其余用 MaterialTheme.motionScheme"
            )
        violations.extend(find_scroll_violations(rel, text))
        if rel != SHARED_OWNER:
            for match in SHARED_TRANSITION.finditer(text):
                violations.append(
                    f"{rel}:{line_of(text, match.start())}: 自己写的共享转场 `{match.group(0).rstrip('( ')}`——封面到详情页的容器转换只在 {SHARED_OWNER}"
                )
        for match in LITERAL.finditer(text):
            violations.append(
                f"{rel}:{line_of(text, match.start())}: 自己写的动画规格 `{match.group(0).rstrip('<({ ' + chr(9) + chr(10) + chr(13))}`"
                "——它不跟系统的动画开关走；改用 MaterialTheme.motionScheme 或 LocalAppMotion.current（scheme / shake / stagger / page）"
            )
    for name in sorted(set(expressive_allowed) - expressive_used):
        violations.append(f"EXPRESSIVE_ALLOWED 的 {name} 已经不用 expressive，删掉这条（死配置）")
    return violations


def find_scroll_violations(rel: str, text: str) -> list[str]:
    violations: list[str] = []
    for match in SCROLL_ANIMATION.finditer(text):
        name = match.group(0).rstrip("( \t\r\n")
        violations.append(
            f"{rel}:{line_of(text, match.start())}: `{name}` 自带的弹簧不跟系统的动画开关走；"
            "改用 LocalAppMotion.current.scrollToItem / scrollToPage"
        )
    for match in SPEC_PARAMETER.finditer(text):
        name = match.group(1)
        line_start = text.rfind("\n", 0, match.start()) + 1
        if DECLARATION_PREFIX.search(text, line_start, match.start()):
            continue
        arguments = call_arguments(text, match.start(2)) if match.group(2) == "(" else []
        line = line_of(text, match.start())
        if name == "animateContentSize" and any(EXPRESSIVE_SIZE.search(argument) for argument in arguments):
            violations.append(f"{rel}:{line}: `animateContentSize` 用了 expressive 规格——过冲的弹簧在缩小时切掉尾部内容；取 scheme 的空间规格")
        if not has_explicit_specs(name, arguments):
            violations.append(f"{rel}:{line}: `{name}` 没传规格，用的是 Compose 自带的弹簧；传 MaterialTheme.motionScheme 或 LocalAppMotion.current 的规格")
    return violations


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    violations = find_violations(root)
    for v in violations:
        print(v, file=sys.stderr)
    if violations:
        print(f"\n动效规格门禁未通过：{len(violations)} 处", file=sys.stderr)
        return 1
    print("动效规格门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
