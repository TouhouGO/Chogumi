"""时钟门禁（唯一实现处；tools/ci/check.sh lint 运行，单跑：python3 tools/guards/check_clock.py）。

规矩见 docs/code-standards.md「时间」：墙钟只经 core:foundation 的 `AppClock.SYSTEM` 读；要随时间走的「现在 / 今天」订阅 `AppTime`；
业务的日子按 `businessDateOf`（北京时间），显示的日子按 `displayZone()`（本机）。各处自己读系统时间，就会各自停在
不同的时刻：详情页还记着昨天、进度页已是今天，「刚刚」停在页面上永远是刚刚。

检查项（主源码 app / core / feature 的 src/main 下 .kt，去掉注释后）：读系统时间或本机时区的调用即拦——
`System.currentTimeMillis` / `System.nanoTime` / `SystemClock.*` / `Calendar.getInstance` / `TimeZone.getDefault` /
`ZoneId.systemDefault` / `Clock.system*` / `Clock.System` / `SimpleDateFormat` / `TimeSource.Monotonic` / `LocalDate.now()` 这类 `*.now()` / `Date()`。
接收者与成员之间可以换行，`System::currentTimeMillis` 这样的方法引用同样算。
放行按「文件 → 允许的那一种调用」登记，每条写明理由（[ALLOWED]）；文件不在了、或里面已没有那一种调用，这条放行也报出来。

另一项：时刻参数不带默认值（`now: Long = …`、`updatedAt: Long = 0L`、`now: Instant = …` 这类，名字是 `now` / `nowMillis` /
`timestamp` 或以 `At` 结尾）。写入的时刻由调用方每次操作取一个 `now` 传进来；带了默认值，漏传的地方就悄悄写进 0 或另一个时刻。
数据类的字段（`val` / `var`）不算，那是模型的初值，不是写入时刻。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

from sources import kotlin_typealiases, main_sources, normalize_code

# 文件 → 只许出现的那一种调用（理由见注释）
ALLOWED = {
    # 全 App 唯一的墙钟
    "core/foundation/src/main/kotlin/io/github/touhougo/chogumi/core/foundation/AppClock.kt": "System.currentTimeMillis",
    # 显示口径的时区：唯一入口 displayZone()
    "core/foundation/src/main/kotlin/io/github/touhougo/chogumi/core/foundation/BangumiZone.kt": "ZoneId.systemDefault",
    # 进度提交时刻的单调钟：界面倒计时与真正提交共用这一个
    "core/model/src/main/kotlin/io/github/touhougo/chogumi/core/model/CommitClock.kt": "System.nanoTime",
    # 网络页「检查连接」量一次请求的耗时（单调，只算差值，不是时刻）
    "core/netroute/src/main/kotlin/io/github/touhougo/chogumi/core/netroute/RouteCheck.kt": "System.nanoTime",
}

# 成员访问：`.` 或方法引用的 `::`，两侧可以换行
MEMBER = r"\s*(?:\.|::)\s*"
PATTERN = re.compile(
    r"\bSystem" + MEMBER + r"(?:currentTimeMillis|nanoTime)\b"
    r"|\bSystemClock" + MEMBER + r"\w+"
    r"|\bCalendar" + MEMBER + r"getInstance\b"
    r"|\bTimeZone" + MEMBER + r"getDefault\b"
    r"|\bZoneId" + MEMBER + r"systemDefault\b"
    r"|\bClock" + MEMBER + r"[sS]ystem\w*"
    r"|\bSimpleDateFormat\b"
    r"|\bTimeSource" + MEMBER + r"Monotonic\b"
    r"|\b(?:LocalDate|LocalDateTime|LocalTime|Instant|ZonedDateTime|OffsetDateTime|OffsetTime|Year|YearMonth|MonthDay)"
    + MEMBER + r"now\s*\("
    r"|\bDate\s*\(\s*\)"
)
HINT = "读「现在」只经 AppClock.SYSTEM；随时间走的现在 / 今天订阅 AppTime；显示时区用 displayZone()"

# 第 1 组是属性的 val / var：属性不算时刻参数
TIMESTAMP_DEFAULT = re.compile(r"(\b(?:val|var)\s+)?\b(now|nowMillis|timestamp|[a-z]\w*At)\s*:\s*(?:Long|Instant)\??\s*=")
TIMESTAMP_HINT = "时刻参数不给默认值：调用方每次操作取一个 now 传进来（docs/code-standards.md「时间」）"


def call_name(match: re.Match[str]) -> str:
    """命中的调用写成 `Receiver.member`：去掉空白与括号，方法引用的 `::` 记成 `.`。"""
    return re.sub(r"\s+", "", match.group(0)).rstrip("(").replace("::", ".")


def find_violations(root: Path, allowed: dict[str, str] = ALLOWED) -> list[str]:
    violations: list[str] = []
    paths = main_sources(root)
    typealiases = kotlin_typealiases(paths)
    for path in paths:
        rel = path.relative_to(root).as_posix()
        text = normalize_code(path.read_text(encoding="utf-8"), typealiases)
        for m in PATTERN.finditer(text):
            call = call_name(m)
            if allowed.get(rel) == call:
                continue
            line = text[: m.start()].count("\n") + 1
            violations.append(f"{rel}:{line}: 自己读了系统时间或本机时区（{call}）——{HINT}")
        for m in TIMESTAMP_DEFAULT.finditer(text):
            if m.group(1):
                continue
            line = text[: m.start()].count("\n") + 1
            violations.append(f"{rel}:{line}: 时刻参数 {m.group(2)} 带了默认值——{TIMESTAMP_HINT}")
    return violations


def unused_allowances(root: Path, allowed: dict[str, str] = ALLOWED) -> list[str]:
    """放行的文件不在了、或里面已没有登记的那一种调用。"""
    stale: list[str] = []
    for rel, call in sorted(allowed.items()):
        path = root / rel
        text = normalize_code(path.read_text(encoding="utf-8")) if path.is_file() else ""
        if not any(call_name(m) == call for m in PATTERN.finditer(text)):
            stale.append(f"{rel}: 放行用不上了（文件不在或已没有 {call}）——从 ALLOWED 删掉")
    return stale


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    violations = find_violations(root) + unused_allowances(root)
    for v in violations:
        print(v, file=sys.stderr)
    if violations:
        print(f"\n时钟门禁未通过：{len(violations)} 处", file=sys.stderr)
        return 1
    print("时钟门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
