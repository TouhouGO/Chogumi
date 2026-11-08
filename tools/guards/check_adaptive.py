"""屏宽自适应门禁（唯一实现处；tools/ci/check.sh lint 运行，单跑：python3 tools/guards/check_adaptive.py）。

380dp 小屏上的问题都出在「各页自己定宽度」：个人页收藏只剩 2 列（自定 112dp 格宽）、
好友头像被挤扁（自定固定 4 列）、搜索排序被截成「…」（直接往连通按钮组里塞文字、没有放不下时的排法）。
规则各只有一处实现：

1. 网格只在 core:ui 的 ContentGrid.kt 里画：列数（GridCell：海报卡 / 人物卡各一个最小格宽，按屏宽推列数）、
   左右留白与格距都在那里定。别处不得写 GridCells.Fixed / GridCells.Adaptive / GridCells.FixedSize，
   也不得自己写 LazyVerticalGrid——整页滚动的网格用 ContentGrid，夹在滚动列里的一段用 AdaptiveGridRows。
   各页自己拼网格时左右留白各写各的，两侧就可能不等。界面文件（导入了 androidx.compose）里用 `chunked(n)`
   切行同样是自定列数，`FlowRow(maxItemsInEachRow = n)` 也是；都只许白名单里的函数（按「文件::函数」列，写明为什么列数不该跟屏宽走）。
2. 组件不越出父级给的宽度：主代码里不写 wrapContentWidth / wrapContentSize / requiredWidth / requiredSize（及其 In 版本）。
   网格按格宽给每一项定宽约束，一项总是铺满一格；这些写法是唯一的绕过办法——自带定宽、靠起始边的卡片会把每格
   多出来的宽都堆在右边，最右一列外的留白比左边大。确需越出的按「文件::函数」进白名单并写明原因。
3. 连通按钮组里画文字只许走 core:ui 的 ConnectedChoiceGroup（任何屏宽、字号下都不截成「…」）；
   直接调 ConnectedButtonGroup 且格里画 Text 的，只许白名单里的函数（按「文件::函数」列，写明为什么放得下）。
4. 白名单里匹配不到的条目算死配置。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

from sources import enclosing_function, kotlin_typealiases, main_sources, normalize_code

GRID_HOME = "ContentGrid.kt"
CHOICE_HOME = "ConnectedChoiceGroup.kt"

# 自己画网格、定列数的地方（文件名, 函数名）
GRID_ALLOWED = {
    ("ProgressScreen.kt", "ProgressPageContent"): "进度页是横向列表行（不是卡片网格），按窗口尺寸类分 1 / 2 列；做平板适配时并入 GridCell",
}

# 界面文件里 chunked 切行（文件名, 函数名）
CHUNKED_ALLOWED = {
    ("BBCodeImages.kt", "ImageGrid"): "正文里的图片拼图：列数由图片张数定（版式属于内容），格宽按正文列宽均分",
    ("ReactionBar.kt", "ReactionGrid"): "表情选择器在下拉菜单里，菜单宽度由内容撑出（4 × 44dp），没有可推列数的父级宽度",
    ("SubjectStatsSection.kt", "balancedRows"): "每集讨论热度格是图表：一行至多 13 格、按行数均分，格边长随宽度缩放，不是卡片网格",
    ("DotStrip.kt", "DotStrip"): "按比例合并同一档的点（每 n 个合成一个），不是切行",
    ("ExploreWorksShelf.kt", "ChartPages"): "榜单一页固定三行（版式属于样式，原型 .cpage），横滑翻页；页数由作品数定，不是按宽度推列数的卡片网格",
    ("ExploreBentoShelf.kt", "BentoPages"): "拼盘一页固定一大两小（版式属于样式，原型 .bp），横滑翻页；页数由作品数定，不是按宽度推列数的卡片网格",
    ("HeatWidget.kt", "groupsOf"): "桌面小组件（Glance）的热力图：一周七天一列、几个月一组的月份标签、Glance 一层至多 10 个子项，"
    "组的大小由日期与 Glance 的限制定，格子边长另按小组件尺寸算",
    ("SearchAirtimeSection.kt", "MonthRow"): "十二个月固定排成两行六个（月份数属于内容），chip 宽按字定，不是按宽度推列数的卡片网格",
}

# 越出父级宽度约束的写法（文件名, 函数名）
WIDTH_ESCAPE_ALLOWED: dict[tuple[str, str], str] = {
    ("WidgetParts.kt", "ProgressButton"): "桌面小组件（Glance）的 wrapContentWidth 只是把宽写成按内容，不越出父级；"
    "桌面换尺寸时把新画法套在旧视图上，不写就留着上一个尺寸的宽",
}

# 直接调 ConnectedButtonGroup 且格里有文字（文件名, 函数名）
BUTTON_GROUP_TEXT_ALLOWED = {
    ("CalendarScreen.kt", "CalendarDaySelector"): "七格各一个汉字（一～日）加今日圆点，格宽恒大于一个字",
}

# 不要求紧跟括号：`import …GridCells.Fixed`（及其别名）之后裸写 `Fixed(2)` 也是自定列数，拦在 import 行上
GRID_CELLS = re.compile(r"\bGridCells\s*\.\s*(Fixed|Adaptive|FixedSize)\b")
LAZY_GRID = re.compile(r"\bLazyVertical(?:Staggered)?Grid\s*\(")
# 不要求前面有 `.`：扩展函数与 with / run 里不写接收者的 chunked(n) 一样是切行；声明（fun chunked）不算
CHUNKED = re.compile(r"(?<![\w$])(?<!fun )(?:chunked|windowed)\s*\(|\bmaxItemsInEachRow\s*=")
COMPOSE_IMPORT = re.compile(r"^import androidx\.compose\.", re.M)
WIDTH_ESCAPE = re.compile(r"\b(wrapContentWidth|wrapContentSize|requiredWidth|requiredWidthIn|requiredSize|requiredSizeIn)\s*\(")
BUTTON_GROUP_CALL = re.compile(r"\bConnectedButtonGroup\s*\(")
TEXT_CALL = re.compile(r"\bText\s*\(")


def matching(text: str, start: int, open_ch: str, close_ch: str) -> int:
    depth = 0
    for i in range(start, len(text)):
        if text[i] == open_ch:
            depth += 1
        elif text[i] == close_ch:
            depth -= 1
            if depth == 0:
                return i
    return len(text) - 1


def call_extent(text: str, call_end: int) -> int:
    """调用的参数括号加上紧跟的尾随 lambda（若有）的结束位置。"""
    end = matching(text, call_end - 1, "(", ")")
    rest = text[end + 1:]
    stripped = rest.lstrip()
    if stripped.startswith("{"):
        brace = end + 1 + (len(rest) - len(stripped))
        end = matching(text, brace, "{", "}")
    return end


def find_violations(
    root: Path,
    grid_allowed: dict[tuple[str, str], str] = GRID_ALLOWED,
    text_allowed: dict[tuple[str, str], str] = BUTTON_GROUP_TEXT_ALLOWED,
    escape_allowed: dict[tuple[str, str], str] = WIDTH_ESCAPE_ALLOWED,
    chunked_allowed: dict[tuple[str, str], str] = CHUNKED_ALLOWED,
) -> list[str]:
    violations: list[str] = []
    used: set[tuple[str, str]] = set()
    paths = main_sources(root)
    typealiases = kotlin_typealiases(paths)
    for path in paths:
        rel = path.relative_to(root).as_posix()
        text = normalize_code(path.read_text(encoding="utf-8"), typealiases)

        def at(pos: int) -> str:
            return f"{rel}:{text[:pos].count(chr(10)) + 1}"

        if path.name != GRID_HOME:
            for m in GRID_CELLS.finditer(text):
                key = (path.name, enclosing_function(text, m.start()))
                if key in grid_allowed:
                    used.add(key)
                    continue
                violations.append(
                    f"{at(m.start())}: {key[1]} 自己定了网格列数（GridCells.{m.group(1)}）——"
                    "用 core:ui 的 ContentGrid / AdaptiveGridRows（列数唯一出处 ContentGrid.kt）"
                )
            for m in LAZY_GRID.finditer(text):
                key = (path.name, enclosing_function(text, m.start()))
                if key in grid_allowed:
                    used.add(key)
                    continue
                violations.append(
                    f"{at(m.start())}: {key[1]} 自己写了 LazyVerticalGrid / LazyVerticalStaggeredGrid——用 core:ui 的 ContentGrid"
                    "（列数、左右留白、格距只在那里定，两侧留白恒等）"
                )
            if COMPOSE_IMPORT.search(text):
                for m in CHUNKED.finditer(text):
                    key = (path.name, enclosing_function(text, m.start()))
                    if key in chunked_allowed:
                        used.add(key)
                        continue
                    violations.append(
                        f"{at(m.start())}: {key[1]} 用 chunked / windowed / maxItemsInEachRow 自己切行定列数——用 core:ui 的 AdaptiveGridRows"
                        "（列数按可用宽度推，留白与格距只在 ContentGrid.kt 定）"
                    )
        for m in WIDTH_ESCAPE.finditer(text):
            key = (path.name, enclosing_function(text, m.start()))
            if key in escape_allowed:
                used.add(key)
                continue
            violations.append(
                f"{at(m.start())}: {key[1]} 用 {m.group(1)} 越出了父级给的宽度——宽度由所在位置给"
                "（网格里铺满一格，横排里由调用方传 width），组件自己不定宽"
            )
        if path.name != CHOICE_HOME:
            for m in BUTTON_GROUP_CALL.finditer(text):
                end = call_extent(text, m.end())
                if not TEXT_CALL.search(text, m.end(), end):
                    continue
                key = (path.name, enclosing_function(text, m.start()))
                if key in text_allowed:
                    used.add(key)
                    continue
                violations.append(
                    f"{at(m.start())}: {key[1]} 往 ConnectedButtonGroup 里直接画文字——改用 ConnectedChoiceGroup"
                    "（小屏放不下时自动改排法，不会截成「…」）"
                )
    for key in sorted((set(grid_allowed) | set(text_allowed) | set(escape_allowed) | set(chunked_allowed)) - used):
        violations.append(f"白名单条目 {key[0]}::{key[1]} 已匹配不到，删掉这条（死配置）")
    return violations


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    violations = find_violations(root)
    for v in violations:
        print(v, file=sys.stderr)
    if violations:
        print(f"\n屏宽自适应门禁未通过：{len(violations)} 处（规则见 core:ui ContentGrid.kt / ConnectedChoiceGroup.kt）", file=sys.stderr)
        return 1
    print("屏宽自适应门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
