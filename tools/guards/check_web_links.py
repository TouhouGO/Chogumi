"""网页地址门禁（唯一实现处；tools/ci/check.sh lint 运行，单跑：python3 tools/guards/check_web_links.py）。

规矩见 docs/code-standards.md「导航与接线」：一个实体的 bgm.tv 网页地址只由 core:model 的 `BgmWeb` 拼，
它与 `inAppRoute` 共用路径段名、对用户名等路径段统一编码。各处手拼的地址会在编码与路径上各写各的
（同一个用户主页，有的编码用户名、有的不编码）。

检查项（主源码 app / core / feature 的 src/main 下 .kt，去掉注释后）：
1. 字符串里出现主站地址 `https://bgm.tv`（或 `bangumi.tv`、`chii.in` 主站；不分大小写，也算 `http://`、协议相对的 `//bgm.tv`，
   主机名后接路径、查询、锚点、端口或字符串结束都算）即拦。图片 CDN（lain.bgm.tv）、接口（api / next.bgm.tv）不是网页，不拦。
   放行：站点常量所在的 BangumiSite.kt、BgmWeb.kt。
   用站点常量手拼路径（`"$BGM_SITE/…"`、`"${BGM_SITE}$PATH"` 这类模板后面还接着内容，`BGM_SITE + …`、`BGM_SITE.plus(…)`）
   同样拦：换了写法的手拼地址；只把 `BGM_SITE` 本身当参数传（网页解析的基准地址）不拦。放行 BgmWeb.kt，
   以及 core:network（OAuth 与网页登录的端点在那里，与接口地址同处）。
2. 打开网页只经 `LocalUriHandler`（App 外壳放进去的是 CustomTabUriHandler：Custom Tab，没有再退普通浏览器）：
   `CustomTabs*`（CustomTabsIntent、CustomTabsSession……）、`ACTION_VIEW` 与字符串 `"android.intent.action.VIEW"`
   只许出现在 CustomTabUriHandler.kt。各页自己起浏览器，有的进 Custom Tab、有的跳出 App，返回时的表现就各不相同。
3. 分享只经 core:ui 的 Share.kt（`shareLink` 分享链接，正文「标题\n链接」；`shareFile` 分享文件）：`ACTION_SEND*`、
   `ACTION_CHOOSER`、`createChooser`、`ShareCompat` 与字符串 `"android.intent.action.SEND…"` / `"…CHOOSER"` 只许出现在
   Share.kt。各处自己拼分享 Intent，正文格式、文件授权就各写各的。
放行的文件不在了、或里面已没有被放行的写法，这条放行也报出来。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

from sources import kotlin_typealiases, main_sources, mask_literals, normalize_kotlin

ALLOWED = {
    "core/foundation/src/main/kotlin/io/github/touhougo/chogumi/core/foundation/BangumiSite.kt",
    "core/model/src/main/kotlin/io/github/touhougo/chogumi/core/model/BgmWeb.kt",
}

PATTERN = re.compile(r"(?:https?:)?//(?:www\.)?(?:bgm\.tv|bangumi\.tv|chii\.in)(?![\w.-])", re.I)
# 模板里的站点常量后面还接着字符串内容（不是紧跟收尾引号）
SITE_TEMPLATE = re.compile(r"\$(?:BGM_SITE\b|\{\s*BGM_SITE\s*\})(?!\")")
SITE_CONCAT = re.compile(r"\bBGM_SITE\s*(?:\+|\.\s*plus\s*\()")
NETWORK_MAIN = "core/network/src/main/"
HINT = "网页地址只经 core:model 的 BgmWeb 拼（BgmWeb.user / subject / mono …）；站点本身用 core:foundation 的 BGM_SITE"

# 代码里的标识符，与字符串里的 Intent 动作名，分两种视图找
OPENER = re.compile(r"\bCustomTabs\w*|\bACTION_VIEW\b")
OPENER_ACTION = re.compile(r"\bandroid\.intent\.action\.VIEW\b")
OPENER_HOME = "app/src/main/kotlin/io/github/touhougo/chogumi/shell/CustomTabUriHandler.kt"
OPENER_HINT = "打开网页只经 LocalUriHandler.current.openUri（外壳给的是 Custom Tab）"

SHARER = re.compile(r"\bACTION_SEND\w*|\bACTION_CHOOSER\b|\bcreateChooser\b|\bShareCompat\b")
SHARER_ACTION = re.compile(r"\bandroid\.intent\.action\.(?:SEND\w*|CHOOSER)\b")
SHARE_HOME = "core/ui/src/main/kotlin/io/github/touhougo/chogumi/core/ui/common/Share.kt"
SHARE_HINT = "分享只经 core:ui 的 Share.kt（shareLink 分享链接、shareFile 分享文件）"


def site_matches(rel: str, literals: str, code: str) -> list[re.Match[str]]:
    """手拼的主站地址：字面的地址，core:network 以外再加用站点常量拼的路径。"""
    found = list(PATTERN.finditer(literals))
    if not rel.startswith(NETWORK_MAIN):
        found += [*SITE_TEMPLATE.finditer(literals), *SITE_CONCAT.finditer(code)]
    return sorted(found, key=lambda m: m.start())


def entry_matches(code_pattern: re.Pattern[str], action_pattern: re.Pattern[str], literals: str, code: str) -> list[re.Match[str]]:
    """起浏览器或分享：代码里的标识符，加字符串里的 Intent 动作名。"""
    return sorted([*code_pattern.finditer(code), *action_pattern.finditer(literals)], key=lambda m: m.start())


def views(path: Path, typealiases: dict[str, str] | None = None) -> tuple[str, str]:
    """(留着字面量的视图, 只留代码的视图)，都去掉注释、规范化别名。"""
    literals = normalize_kotlin(path.read_text(encoding="utf-8"), typealiases)
    return literals, mask_literals(literals)


def find_violations(
    root: Path,
    allowed: set[str] = ALLOWED,
    opener_home: str | None = OPENER_HOME,
    share_home: str | None = SHARE_HOME,
) -> list[str]:
    violations: list[str] = []
    paths = main_sources(root)
    typealiases = kotlin_typealiases(paths)

    def line_of(text: str, m: re.Match[str]) -> int:
        return text[: m.start()].count("\n") + 1

    for path in paths:
        rel = path.relative_to(root).as_posix()
        literals, code = views(path, typealiases)
        if rel not in allowed:
            for m in site_matches(rel, literals, code):
                violations.append(f"{rel}:{line_of(literals, m)}: 手拼了 bgm.tv 网页地址——{HINT}")
        for home, code_pattern, action_pattern, what, hint in (
            (opener_home, OPENER, OPENER_ACTION, "自己起浏览器", OPENER_HINT),
            (share_home, SHARER, SHARER_ACTION, "自己拼分享", SHARE_HINT),
        ):
            if rel == home:
                continue
            for m in entry_matches(code_pattern, action_pattern, literals, code):
                violations.append(f"{rel}:{line_of(literals, m)}: {what}（{m.group(0)}）——{hint}")
    return violations


def unused_allowances(
    root: Path,
    allowed: set[str] = ALLOWED,
    opener_home: str = OPENER_HOME,
    share_home: str = SHARE_HOME,
) -> list[str]:
    """放行的文件与两个入口文件不在了、或里面已没有被放行的写法。"""
    stale: list[str] = []
    for rel in sorted({*allowed, opener_home, share_home}):
        path = root / rel
        found: list[re.Match[str]] = []
        if path.is_file():
            literals, code = views(path)
            if rel in allowed:
                found += site_matches(rel, literals, code)
            if rel == opener_home:
                found += entry_matches(OPENER, OPENER_ACTION, literals, code)
            if rel == share_home:
                found += entry_matches(SHARER, SHARER_ACTION, literals, code)
        if not found:
            stale.append(f"{rel}: 放行用不上了（文件不在或已没有被放行的写法）——从放行里删掉")
    return stale


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    violations = find_violations(root) + unused_allowances(root)
    for v in violations:
        print(v, file=sys.stderr)
    if violations:
        print(f"\n网页地址门禁未通过：{len(violations)} 处", file=sys.stderr)
        return 1
    print("网页地址门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
