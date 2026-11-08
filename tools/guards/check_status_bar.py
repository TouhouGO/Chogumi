"""状态栏门禁（唯一实现处；tools/ci/check.sh lint 运行，单跑：python3 tools/guards/check_status_bar.py）。

页面要么整页排在状态栏之下（statusBarsPadding、Scaffold + TopAppBar），要么头部从屏幕顶边画起、铺到状态栏下面。
后一种页面往上滚时，滚上去的内容会和状态栏的时间、电量叠在一起，必须由顶栏盖住：全 App 只有一个这样的顶栏，
core:ui 的 HeaderTopAppBar（叠在头部上、钉在顶上，内容滚到它下面时连状态栏一起填上页面底色 surface）。

检查项：
1. 自己读状态栏高度（WindowInsets 的 statusBars / systemBars / safeDrawing / safeContent / tappableElement，含
   `…IgnoringVisibility`）的文件，就是把内容画进了状态栏，同一个文件里必须调用 HeaderTopAppBar；
   不画进状态栏、只拿高度做别的用途的，按仓库路径列进白名单并写明理由。
2. 透明容器的 TopAppBar（顶栏配色 `…TopAppBarColors(…)` 及其后链上的 `.copy(…)` 里给 Color.Transparent，或
   `.copy(alpha = 0f)`）只许在 HeaderTopAppBar.kt：页面不得自己再拼一个「透明顶栏 + 滚动变色」。
3. 白名单里匹配不到的条目、不在了的归属文件（HeaderTopAppBar.kt、STICKY_HOMES）算死配置。
4. 不用 pinnedScrollBehavior：顶栏下面吸着一条（选项卡、排序行）时，往下读只留那一条，顶栏往上滚让位、往回滚出现
   （PageScaffold、CollapsibleHeader、HeaderTabsFrame 已经这样做）；一个永不让位的顶栏会和吸顶的那条叠成两层。
   会让位的顶栏（enterAlwaysScrollBehavior / exitUntilCollapsedScrollBehavior）在同一个文件里经 `heldBy(hold)` 接滚动，
   不用裸的连接：列表里的吸顶项吸住时，往回滚是在翻楼层，顶栏要等它落回原位。
5. 列表里有吸顶项（`stickyHeader`，或经 `floorCommentsSection` 带进来的评论区排序行）的文件，同一个文件里必须给列表挂
   `stickyHeaderFade`：正文滑到吸顶项下面时下沿出柔边，与列表外的吸顶条（PinnedRowFrame）同一个样子；
   并把 `overscrollEffect = ….overscrollEffect` 交给列表：回弹拉伸由柔边那一层画，柔边才跟着吸顶项一起走。
   只定义吸顶项、不建列表的文件（STICKY_HOMES）由调用方负责。
6. 顶栏、状态栏那一截、吸顶条这一摞永远是页面底色 surface，不随滚动换成任何一级 surfaceContainer：
   任何地方都不得在 surface 与 surfaceContainer…之间 lerp（哪个在前都一样）。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

from sources import call_end, kotlin_typealiases, main_sources, normalize_code

# 读状态栏高度但不把内容画进状态栏的文件（仓库路径 → 理由）。
STATUS_BAR_ALLOWED = {
    "core/ui/src/main/kotlin/io/github/touhougo/chogumi/core/ui/composables/PageModalSheet.kt":
        "面板全展开时顶边停在状态栏下沿，高度只用来算这个停靠位置",
    "app/src/main/kotlin/io/github/touhougo/chogumi/shell/AppShell.kt":
        "只取左右两侧的系统栏 inset（WindowInsetsSides.Horizontal），不碰状态栏高度",
    "feature/profile/src/main/kotlin/io/github/touhougo/chogumi/feature/profile/ProfileHeader.kt":
        "头部确实铺到状态栏下面；盖住它的 HeaderTopAppBar 在同包的 ProfileTopBar.kt，由 ProfileScreen 叠在头部上",
}

STATUS_BARS = re.compile(
    r"\bWindowInsets\s*\.\s*(?:Companion\s*\.\s*)?"
    r"(?:statusBars|systemBars|safeDrawing|safeContent|tappableElement)(?:IgnoringVisibility)?\b"
)
HEADER_BAR_CALL = re.compile(r"\bHeaderTopAppBar\s*\(")
HEADER_BAR_DECL = re.compile(r"\bfun\s+HeaderTopAppBar\s*\(")
TOP_BAR_COLORS = re.compile(r"\b(?:topAppBarColors|\w*TopAppBarColors)\s*\(")
CHAINED_COPY = re.compile(r"\s*\.\s*copy\s*\(")
# 透明的两种写法：Color.Transparent，或某个色 .copy(alpha = 0f)（alpha 是 Color.copy 的第一个参数）
TRANSPARENT = re.compile(r"\bColor\s*\.\s*Transparent\b|\.\s*copy\s*\(\s*(?:alpha\s*=\s*)?0(?:\.0*)?[fF]\s*[,)]")
HEADER_BAR_HOME = "core/ui/src/main/kotlin/io/github/touhougo/chogumi/core/ui/composables/HeaderTopAppBar.kt"
LERP = re.compile(r"\blerp\s*\(")
SURFACE = re.compile(r"\bsurface\b")
SURFACE_CONTAINER = re.compile(r"\bsurfaceContainer\w*")
PINNED_BAR = re.compile(r"\bpinnedScrollBehavior\s*\(")
YIELDING_BAR = re.compile(r"\b(?:enterAlwaysScrollBehavior|exitUntilCollapsedScrollBehavior)\s*\(")
HELD_BY = re.compile(r"\.\s*heldBy\s*\(")
STICKY_ROW = re.compile(r"\b(?:stickyHeader|floorCommentsSection)\s*[({]")
STICKY_FADE = re.compile(r"\bstickyHeaderFade\s*\(")
STICKY_STRETCH = re.compile(r"\boverscrollEffect\s*=\s*[\w.()\s]*?\.\s*overscrollEffect\b")
# 只把吸顶项写进 LazyListScope、不建列表的文件：柔边挂在调用方的列表上
STICKY_HOMES = {"core/reader/src/main/kotlin/io/github/touhougo/chogumi/core/reader/FloorComments.kt"}


def transparent_bars(code: str) -> list[int]:
    """顶栏配色调用（连同其后链上的 `.copy(…)`）的参数里写了透明色的，返回它们的起点。"""
    found = []
    for call in TOP_BAR_COLORS.finditer(code):
        end = call_end(code, call.end() - 1)
        span = code[call.end():end]
        while (chained := CHAINED_COPY.match(code, end + 1)) is not None:
            following = call_end(code, chained.end() - 1)
            span += "," + code[chained.end():following]
            end = following
        if TRANSPARENT.search(span):
            found.append(call.start())
    return found


def surface_container_lerps(code: str) -> list[int]:
    """在 surface 与任何一级 surfaceContainer 之间插值的 lerp（不论先后），返回起点。"""
    found = []
    for call in LERP.finditer(code):
        arguments = code[call.end():call_end(code, call.end() - 1)]
        if SURFACE.search(arguments) and SURFACE_CONTAINER.search(arguments):
            found.append(call.start())
    return found


def line_of(text: str, index: int) -> int:
    return text.count("\n", 0, index) + 1


def find_violations(root: Path, allowed: dict[str, str] = STATUS_BAR_ALLOWED) -> list[str]:
    violations: list[str] = []
    used: set[str] = set()
    paths = main_sources(root)
    typealiases = kotlin_typealiases(paths)
    homes = {HEADER_BAR_HOME: HEADER_BAR_DECL, **{home: STICKY_ROW for home in STICKY_HOMES}}
    for home, marker in sorted(homes.items()):
        path = root / home
        if not path.is_file() or not marker.search(normalize_code(path.read_text(encoding="utf-8"), typealiases)):
            violations.append(f"{home} 已不定义它被登记的那件东西：归属挪走了，同步改 tools/guards/check_status_bar.py（死配置）")
    for path in paths:
        rel = path.relative_to(root).as_posix()
        code = normalize_code(path.read_text(encoding="utf-8"), typealiases)
        reads = STATUS_BARS.search(code)
        if reads and rel != HEADER_BAR_HOME and not HEADER_BAR_CALL.search(code):
            if rel in allowed:
                used.add(rel)
            else:
                violations.append(
                    f"{rel}:{line_of(code, reads.start())}: 读了状态栏高度、把内容画进状态栏，却没有用 HeaderTopAppBar"
                    "——往上滚时内容会和状态栏叠在一起；改用 HeaderTopAppBar 盖住，或整页 statusBarsPadding。"
                    "确实不画进状态栏的才可加进 tools/guards/check_status_bar.py 白名单"
                )
        if rel != HEADER_BAR_HOME:
            for start in transparent_bars(code):
                violations.append(
                    f"{rel}:{line_of(code, start)}: 自己拼了透明容器的顶栏——头部铺到状态栏下面的页面只用 core:ui 的 HeaderTopAppBar"
                )
        for start in surface_container_lerps(code):
            violations.append(
                f"{rel}:{line_of(code, start)}: 在 surface 与 surfaceContainer 之间 lerp 顶栏填色——吸顶这一摞永远是页面底色 surface"
            )
        for pinned in PINNED_BAR.finditer(code):
            violations.append(
                f"{rel}:{line_of(code, pinned.start())}: 顶栏用了 pinnedScrollBehavior——下面吸着选项卡或排序行时会叠成两层；"
                "二级页用 PageScaffold，自带页头的页面用 CollapsibleHeader（页头进 collapsing、吸顶的那条进 pinned）"
            )
        yielding = YIELDING_BAR.search(code)
        if yielding and not HELD_BY.search(code):
            violations.append(
                f"{rel}:{line_of(code, yielding.start())}: 会让位的顶栏没有经 heldBy(hold) 接滚动——列表里的吸顶项吸住时往回滚会把顶栏拉出来"
                "、和吸顶项叠成两层；二级页用 PageScaffold，自带页头的页面用 CollapsibleHeader"
            )
        sticky = STICKY_ROW.search(code)
        if sticky and rel not in STICKY_HOMES and not STICKY_FADE.search(code):
            violations.append(
                f"{rel}:{line_of(code, sticky.start())}: 列表里有吸顶项，列表却没有挂 stickyHeaderFade"
                "——正文滑到吸顶项下面时下沿是一道硬线；接法见 core:ui rememberStickyHeaderEdge"
            )
        elif sticky and rel not in STICKY_HOMES and not STICKY_STRETCH.search(code):
            violations.append(
                f"{rel}:{line_of(code, sticky.start())}: 列表挂了 stickyHeaderFade，却没有把 overscrollEffect = edge.overscrollEffect"
                " 交给 LazyColumn——到底再拖时回弹把吸顶项拉走，柔边留在原地、和它分开"
            )
    for rel in sorted(set(allowed) - used):
        violations.append(f"白名单条目 {rel} 已匹配不到读状态栏高度的代码，删掉这条（死配置）")
    return violations


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    violations = find_violations(root)
    for v in violations:
        print(v, file=sys.stderr)
    if violations:
        print(f"\n状态栏门禁未通过：{len(violations)} 处（规矩见 docs/code-standards.md「状态栏」一节）", file=sys.stderr)
        return 1
    print("状态栏门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
