"""App 图标的唯一来源：Google Material Symbols（Apache-2.0）的一个固定快照。

    python3 tools/icons/material_symbols.py fetch   按清单从固定快照下载并重写 core:res 的 *_24.xml（需联网）
    python3 tools/icons/material_symbols.py check   不联网核对：清单与图标文件一一对应、文件名 = 符号名（填充的加 _filled）、
                                                    每个文件都是这个快照生成的、清单里的每个图标都有主源码在用；
                                                    清单以外不许有矢量图（资源 drawable* / mipmap* 目录与 Kotlin 的
                                                    ImageVector.Builder），非图标矢量图的放行条目要还在且有人用

全部图标取同一种样式（Rounded：圆角与 M3 Expressive 的圆润形状一致，见 foundations.md「Three styles」）、
默认的字重 400 / grade 0 / 24dp 光学尺寸。填充按用途定（[ICONS] 的第二项）：
- 默认不填充，同一组图标（类型、收藏状态、入口）观感一致；
- 填充只表示「选中 / 已开」——导航与分段的选中态、已收藏、已置顶、计数里的小图标（`*_filled`）；
- 顶栏动作图标填充（bars.md「Use filled icons when possible for best visibility」）。
文件名 = 符号名，填充的加 `_filled`；新增图标先加进清单再 fetch，不手写矢量图。
"""

from __future__ import annotations

import hashlib
import json
import re
import sys
import time
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "guards"))

from sources import kotlin_typealiases, main_sources, normalize_code  # noqa: E402

COMMIT = "49d4db35df873165d6bd6ba09b063c7dafbac2f4"
STYLE = "materialsymbolsrounded"
STYLE_LABEL = "Material Symbols Rounded"
DRAWABLES = "core/res/src/main/res/drawable"
CONTENT_HASHES = "tools/icons/material_symbols.sha256.json"
SOURCE = "https://raw.githubusercontent.com/google/material-design-icons/{commit}/symbols/android/{symbol}/{style}/{symbol}{fill}_24px.xml"

# 文件名（去掉 _24）→（符号名, 是否填充）
ICONS: dict[str, tuple[str, bool]] = {
    "account_circle_filled": ("account_circle", True),
    "add": ("add", False),
    "add_link": ("add_link", False),
    "arrow_back": ("arrow_back", False),
    "arrow_downward": ("arrow_downward", False),
    "arrow_forward": ("arrow_forward", False),
    "arrow_upward": ("arrow_upward", False),
    "article": ("article", False),
    "article_filled": ("article", True),
    "auto_awesome": ("auto_awesome", False),
    "bar_chart": ("bar_chart", False),
    "block": ("block", False),
    "book_2": ("book_2", False),
    "book_2_filled": ("book_2", True),
    "bookmark": ("bookmark", False),
    "bookmark_filled": ("bookmark", True),
    "broken_image": ("broken_image", False),
    "calendar_month": ("calendar_month", False),
    "cancel": ("cancel", False),
    "cancel_filled": ("cancel", True),
    "chat": ("chat", False),
    "chat_filled": ("chat", True),
    "check": ("check", False),
    "check_circle": ("check_circle", False),
    "check_circle_filled": ("check_circle", True),
    "chevron_right": ("chevron_right", False),
    "close": ("close", False),
    "code": ("code", False),
    "content_copy": ("content_copy", False),
    "delete": ("delete", False),
    "download": ("download", False),
    "drag_indicator": ("drag_indicator", False),
    "edit": ("edit", False),
    "expand_less": ("expand_less", False),
    "expand_more": ("expand_more", False),
    "explore": ("explore", False),
    "explore_filled": ("explore", True),
    "face": ("face", False),
    "face_filled": ("face", True),
    "favorite": ("favorite", False),
    "favorite_filled": ("favorite", True),
    "filter_list": ("filter_list", False),
    "flag": ("flag", False),
    "format_bold": ("format_bold", False),
    "format_italic": ("format_italic", False),
    "format_quote": ("format_quote", False),
    "format_size": ("format_size", False),
    "format_strikethrough": ("format_strikethrough", False),
    "format_underlined": ("format_underlined", False),
    "forum": ("forum", False),
    "forum_filled": ("forum", True),
    "grid_view": ("grid_view", False),
    "grid_view_filled": ("grid_view", True),
    "group": ("group", False),
    "group_filled": ("group", True),
    "home": ("home", False),
    "home_filled": ("home", True),
    "image": ("image", False),
    "info": ("info", False),
    "info_filled": ("info", True),
    "key": ("key", False),
    "link": ("link", False),
    "list_alt": ("list_alt", False),
    "list_alt_filled": ("list_alt", True),
    "lock": ("lock", False),
    "mail": ("mail", False),
    "menu_book": ("menu_book", False),
    "mood": ("mood", False),
    "mood_filled": ("mood", True),
    "more_vert": ("more_vert", False),
    "music_note": ("music_note", False),
    "music_note_filled": ("music_note", True),
    "notifications_filled": ("notifications", True),
    "open_in_new": ("open_in_new", False),
    "palette": ("palette", False),
    "pause_circle": ("pause_circle", False),
    "pause_circle_filled": ("pause_circle", True),
    "person": ("person", False),
    "person_add": ("person_add", False),
    "person_filled": ("person", True),
    "person_remove": ("person_remove", False),
    "photo_camera": ("photo_camera", False),
    "play_circle": ("play_circle", False),
    "play_circle_filled": ("play_circle", True),
    "playlist_add": ("playlist_add", False),
    "playlist_remove": ("playlist_remove", False),
    "preview": ("preview", False),
    "preview_filled": ("preview", True),
    "push_pin": ("push_pin", False),
    "push_pin_filled": ("push_pin", True),
    "refresh": ("refresh", False),
    "remove": ("remove", False),
    "reply": ("reply", False),
    "search": ("search", False),
    "segment": ("segment", False),
    "send": ("send", False),
    "settings_filled": ("settings", True),
    "share": ("share", False),
    "shield": ("shield", False),
    "sort": ("sort", False),
    "sports_esports": ("sports_esports", False),
    "sports_esports_filled": ("sports_esports", True),
    "star": ("star", False),
    "star_filled": ("star", True),
    "text_format": ("text_format", False),
    "theaters": ("theaters", False),
    "theaters_filled": ("theaters", True),
    "trophy": ("trophy", False),
    "tune": ("tune", False),
    "tv": ("tv", False),
    "tv_filled": ("tv", True),
    "undo": ("undo", False),
    "update": ("update", False),
    "visibility": ("visibility", False),
    "visibility_off": ("visibility_off", False),
}


def header(symbol: str, filled: bool) -> str:
    """每个图标文件的第一行：写明来源快照，内容摘要同时覆盖此来源声明。"""
    return (
        f"<!-- {STYLE_LABEL}「{symbol}」FILL {1 if filled else 0}，google/material-design-icons@{COMMIT[:8]}；"
        f"由 tools/icons/material_symbols.py 生成，不手改 -->"
    )


def render(symbol: str, filled: bool, official: str) -> str:
    """只取官方文件的几何（视口、路径、是否随 RTL 镜像），外壳统一成本仓库的写法。"""
    viewport_w = re.search(r'android:viewportWidth="([\d.]+)"', official).group(1)
    viewport_h = re.search(r'android:viewportHeight="([\d.]+)"', official).group(1)
    mirrored = 'android:autoMirrored="true"' in official
    paths = re.findall(r'android:pathData="([^"]+)"', official)
    if not paths:
        raise ValueError(f"{symbol}: 官方文件里没有 pathData")
    lines = [
        header(symbol, filled),
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="24dp"',
        '    android:height="24dp"',
        f'    android:viewportWidth="{viewport_w}"',
        f'    android:viewportHeight="{viewport_h}"',
    ]
    if mirrored:
        lines.append('    android:autoMirrored="true"')
    lines.append('    android:tint="?android:attr/colorControlNormal">')
    for d in paths:
        lines += ["    <path", '        android:fillColor="@android:color/white"', f'        android:pathData="{d}" />']
    lines.append("</vector>")
    return "\n".join(lines) + "\n"


def download(url: str) -> str:
    for attempt in range(4):
        try:
            with urllib.request.urlopen(url, timeout=20) as response:
                return response.read().decode("utf-8")
        except OSError:
            if attempt == 3:
                raise
            time.sleep(2)
    raise AssertionError("unreachable")


def content_hash(text: str) -> str:
    """规范化 CRLF / CR 为 LF 后计算完整生成内容摘要，其余字节不忽略。"""
    normalized = text.replace("\r\n", "\n").replace("\r", "\n")
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


def fetch(root: Path) -> None:
    drawables = root / DRAWABLES
    hashes: dict[str, str] = {}
    for name, (symbol, filled) in sorted(ICONS.items()):
        url = SOURCE.format(commit=COMMIT, symbol=symbol, style=STYLE, fill="_fill1" if filled else "")
        generated = render(symbol, filled, download(url))
        (drawables / f"{name}_24.xml").write_text(generated, encoding="utf-8")
        hashes[name] = content_hash(generated)
        print(f"{name}_24.xml ← {symbol}{' (filled)' if filled else ''}")
    manifest = root / CONTENT_HASHES
    manifest.parent.mkdir(parents=True, exist_ok=True)
    manifest.write_text(json.dumps({"commit": COMMIT, "icons": hashes}, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    for stale in sorted(set(p.name for p in drawables.glob("*_24.xml")) - {f"{n}_24.xml" for n in ICONS}):
        (drawables / stale).unlink()
        print(f"删除不在清单里的 {stale}")


# 主源码里的资源与清单 XML（测试源码与 build/ 不算「在用」）；Kotlin 经 sources.main_sources
MAIN_SOURCES = ("app/src/main", "core/*/src/main", "feature/*/src/main")
REFERENCE = re.compile(r"(?:R\.drawable\.|@drawable/)([a-z0-9_]+)\b")
HAND_WRITTEN_VECTOR = re.compile(r"\bImageVector\s*\.\s*Builder\s*\(")


def main_xml(root: Path) -> list[Path]:
    return sorted(
        path for pattern in MAIN_SOURCES for path in root.glob(f"{pattern}/**/*.xml") if path.is_file()
    )


def kotlin_sources(root: Path) -> dict[Path, str]:
    paths = main_sources(root)
    aliases = kotlin_typealiases(paths)
    return {path: normalize_code(path.read_text(encoding="utf-8"), aliases) for path in paths}


def referenced(root: Path, kotlin: dict[Path, str]) -> set[str]:
    """主源码里引用到的 drawable 名（Kotlin 的 R.drawable.x 与资源 XML 的 @drawable/x）。"""
    names: set[str] = set()
    for text in kotlin.values():
        names.update(REFERENCE.findall(text))
    for path in main_xml(root):
        names.update(REFERENCE.findall(re.sub(r"<!--.*?-->", "", path.read_text(encoding="utf-8"), flags=re.S)))
    return names


# 不是界面图标的矢量图，按文件名放行并写明原因（启动图标是 mipmap 位图，不在这里）
NON_ICON_VECTORS: dict[str, str] = {
    name: "探索页平台方块的平台标志：第三方品牌图形，不是界面图标（来源与许可见 docs/third-party-notices.md）"
    for name in (
        "platform_nintendo_switch.xml",
        "platform_ps5.xml",
        "platform_windows.xml",
        "platform_xbox.xml",
        "platform_android.xml",
    )
}


def is_vector(path: Path) -> bool:
    return "<vector" in path.read_text(encoding="utf-8")


def stray_vectors(root: Path, non_icons: dict[str, str]) -> list[Path]:
    """图标唯一目录以外的矢量图不能靠同名、资源变体或 mipmap 目录绕过清单与摘要。"""
    found: list[Path] = []
    for pattern in MAIN_SOURCES:
        for path in sorted(root.glob(f"{pattern}/res/*/*.xml")):
            if not path.parent.name.startswith(("drawable", "mipmap")):
                continue
            if path.parent == root / DRAWABLES and path.name in non_icons:
                continue
            if path.parent == root / DRAWABLES and path.name.endswith("_24.xml"):
                continue
            if is_vector(path):
                found.append(path)
    return found


def check(
    root: Path, icons: dict[str, tuple[str, bool]] = ICONS, non_icons: dict[str, str] = NON_ICON_VECTORS,
) -> list[str]:
    drawables = root / DRAWABLES
    present = {p.name[: -len("_24.xml")] for p in drawables.glob("*_24.xml")}
    problems = [f"{DRAWABLES}/{n}_24.xml 不在 tools/icons/material_symbols.py 的清单里——先加进清单再 fetch" for n in sorted(present - set(icons))]
    problems += [f"清单里的 {n} 没有对应的 {DRAWABLES}/{n}_24.xml——跑一次 fetch" for n in sorted(set(icons) - present)]
    problems += [
        f"清单里的 {n} 文件名与符号不符——文件名 = 符号名（填充的加 _filled），应为 {symbol}{'_filled' if filled else ''}"
        for n, (symbol, filled) in sorted(icons.items())
        if n != symbol + ("_filled" if filled else "")
    ]
    manifest_path = root / CONTENT_HASHES
    hashes: dict[str, str] = {}
    if not manifest_path.is_file():
        problems.append(f"{CONTENT_HASHES} 不存在——跑一次 fetch 登记固定快照的内容摘要")
    else:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        if manifest.get("commit") != COMMIT:
            problems.append(f"{CONTENT_HASHES} 快照提交不符——跑一次 fetch")
        hashes = manifest.get("icons", {})
        if set(hashes) != set(icons):
            problems.append(f"{CONTENT_HASHES} 与图标清单不一致——跑一次 fetch")
    for name in sorted(present & set(icons)):
        symbol, filled = icons[name]
        content = (drawables / f"{name}_24.xml").read_text(encoding="utf-8")
        if content.split("\n", 1)[0] != header(symbol, filled):
            problems.append(f"{DRAWABLES}/{name}_24.xml 不是从清单与固定快照生成的（首行不符）——跑一次 fetch，不手改")
        elif name in hashes and content_hash(content) != hashes[name]:
            problems.append(f"{DRAWABLES}/{name}_24.xml 内容摘要不符（路径、视口、镜像与完整外壳均须来自固定快照）——跑一次 fetch，不手改")
    problems += [
        f"{p.relative_to(root).as_posix()} 是手写的矢量图——图标只来自清单（文件名 *_24.xml）；不是图标就进 NON_ICON_VECTORS 并写明原因"
        for p in stray_vectors(root, non_icons)
    ]
    kotlin = kotlin_sources(root)
    for path, text in kotlin.items():
        for m in HAND_WRITTEN_VECTOR.finditer(text):
            problems.append(
                f"{path.relative_to(root).as_posix()}:{text.count(chr(10), 0, m.start()) + 1}: 在 Kotlin 里手写矢量图（ImageVector.Builder）"
                "——图标只来自 tools/icons/material_symbols.py 的清单"
            )
    used = referenced(root, kotlin)
    problems += [f"清单里的 {n} 主源码没有引用（R.drawable.{n}_24）——不用了就从清单删掉再 fetch" for n in sorted(set(icons) - {u[: -len("_24")] for u in used if u.endswith("_24")})]
    for name in sorted(non_icons):
        path = drawables / name
        if not path.is_file() or not is_vector(path) or name[: -len(".xml")] not in used:
            problems.append(f"NON_ICON_VECTORS 的 {name} 已不是 {DRAWABLES} 里有人引用的矢量图，删掉这条（死配置）")
    return problems


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    command = sys.argv[1] if len(sys.argv) > 1 else "check"
    if command == "fetch":
        fetch(root)
        return 0
    problems = check(root)
    for p in problems:
        print(p, file=sys.stderr)
    if problems:
        print(f"\n图标门禁未通过：{len(problems)} 处", file=sys.stderr)
        return 1
    print("图标门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
