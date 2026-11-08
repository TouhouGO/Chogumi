"""ViewModel 与接线规矩门禁（唯一实现处；tools/ci/check.sh lint 运行，单跑：python3 tools/guards/check_viewmodel_rules.py）。

规矩见 docs/code-standards.md「ViewModel 状态与失败提示」「导航与接线」「测试」：这几条都是
「不许」，写法固定，用正则一条一条守住。

检查项（去掉注释后）：
1. feature 主源码：不写 `catch (`（调用包进 core:data 的 `outcomeOf`），不碰 `TokenStore`（登录与否由 appEntries 传下来）。
   feature 之外声明了 ViewModel（继承 `StateViewModel<…>` 或 `ViewModel()`）的主源码文件同样不写 `catch (`。
2. AppEntries.kt 不创建 ViewModel（`koinViewModel`）：页面自己取。
3. 全仓不用 Koin 注解：主源码不导入 `org.koin.core.annotation`，构建脚本、版本目录与 build-logic 源码（去掉注释）不出现 `koin-annotations`。
4. 主源码不靠异常消息嗅探失败文本：`….message` 上的 `contains` / `startsWith` / `endsWith` / `matches` / `equals`（可隔着
   `orEmpty()` 这类无参调用、可换行）、`… in e.message`、`localizedMessage`。失败是 `Failure`，只在 core:network 的 `toFailure()` 里转。
5. 测试源码（test / testFixtures / androidTest 与 baselineprofile/src/main）里调 `setMain`（`Dispatchers.setMain`，
   或在 `with(Dispatchers)` 这类作用域里直接调）只许出现在 core:data testFixtures 的 TestMain.kt；TestMain.kt 不在了、
   或里面已不调 `setMain`，这条放行也报出来。
6. 数据层主源码（core:data、core:network、core:model、core:foundation）不新建也不持有 `FailureNotices`（属性、构造参数）：
   它是页面的提示槽，归 ViewModel（或 ViewModel 持有的控制器）；放在单例上，关掉的页面的重试和上一个账号的提示会留给
   下一次打开的页面。ViewModel 把自己的 `failures` 当函数参数交给数据层的辅助函数（`launchWrite`）可以。
7. feature 不构造页面目的地，也不接收 Route 导航回调：页面交出语义，只有 AppEntries 把语义变成目的地。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

from sources import is_import_line, kotlin_typealiases, main_sources, normalize_code, strip_comments, test_sources

FEATURE_MAIN = "feature/"
CATCH_RULE = (re.compile(r"\bcatch\s*\("), "ViewModel 与页面代码不写 catch——调用包进 outcomeOf { }")
FEATURE_RULES = [
    CATCH_RULE,
    (re.compile(r"\bTokenStore\b"), "feature 不读 TokenStore——登录与否由 appEntries 传下来的 isLoggedIn 决定"),
    (re.compile(r"\bRoute\s*\.\s*[A-Z]\w*\b"), "feature 不构造页面目的地——导航语义由 AppEntries 映射"),
    # 函数类型的参数表里任一个参数是 Route（可带参数名、限定名、可空），不管前后还有几个参数
    (re.compile(
        r"\((?:[^()]*,)?\s*(?:\w+\s*:\s*)?(?:\w+\s*\.\s*)*Route\s*\??\s*(?:,[^()]*)?\)\s*->\s*Unit\b"),
     "feature 不接收 Route 回调——导航语义由 AppEntries 映射"),
    (re.compile(r"\bFunction1\s*<\s*(?:\w+\s*\.\s*)*Route\s*\??\s*,\s*Unit\s*>|\bRoute\s*\??\s*\.\s*\(\s*\)\s*->\s*Unit\b"),
     "feature 不接收 Route 回调——导航语义由 AppEntries 映射"),
]
DESTINATION_IMPORT = re.compile(
    r"(?m)^\s*import\s+io\.github\.touhougo\.chogumi\.core\.ui\.common\.navigation\.Route\s*\.\s*(?:[A-Z]\w*|\*)"
)
ENTRIES = "AppEntries.kt"
KOIN_ANNOTATION_IMPORT = re.compile(r"\borg\.koin\.core\.annotation\b")
KOIN_ANNOTATIONS_DEP = "koin-annotations"
CALL_STEP = r"\s*(?:!!|\?)?\s*\.\s*"
FAILURE_SNIFF = re.compile(
    r"\.\s*message" + CALL_STEP + r"(?:\w+\s*\(\s*\)" + CALL_STEP + r")*(?:contains|startsWith|endsWith|matches|equals)\s*\("
    r"|\bin\s+(?:\w+" + CALL_STEP + r")*message\b(?:" + CALL_STEP + r"orEmpty\s*\(\s*\)|\s*!!)?(?!\s*(?:!!|\?)?\s*\.)"
    r"|\blocalizedMessage\b"
)
SET_MAIN_HOME = "core/data/src/testFixtures/kotlin/io/github/touhougo/chogumi/core/data/repository/TestMain.kt"
DATA_LAYER_MAIN = ("core/data/", "core/network/", "core/model/", "core/foundation/")
VIEW_MODEL_CLASS = re.compile(r"\bclass\s+\w+[^{]*?:\s*(?:\w+\s*\.\s*)*(?:StateViewModel\s*<|ViewModel\s*\()")
SET_MAIN = re.compile(r"\bsetMain\b")
FAILURE_NOTICES = re.compile(r"\bFailureNotices\s*\(|\b(?:val|var)\s+\w+\s*:\s*FailureNotices\b")
BUILD_FILES = ["gradle/libs.versions.toml", "**/build.gradle.kts", "**/settings.gradle.kts"]
BUILD_LOGIC_MAIN = "build-logic/src/main/"


def find_violations(root: Path) -> list[str]:
    violations: list[str] = []

    def report(rel: str, text: str, m: re.Match[str], what: str) -> None:
        violations.append(f"{rel}:{text[: m.start()].count(chr(10)) + 1}: {what}")

    main = main_sources(root)
    all_sources = main + test_sources(root)
    typealiases = kotlin_typealiases(all_sources)
    for path in main:
        rel = path.relative_to(root).as_posix()
        text = normalize_code(path.read_text(encoding="utf-8"), typealiases)
        if rel.startswith(FEATURE_MAIN):
            for pattern, what in FEATURE_RULES:
                for m in pattern.finditer(text):
                    if is_import_line(text, m.start()):
                        continue
                    report(rel, text, m, what)
            for m in DESTINATION_IMPORT.finditer(text):
                report(rel, text, m, "feature 不导入具体页面目的地——导航语义由 AppEntries 映射")
        elif VIEW_MODEL_CLASS.search(text):
            pattern, what = CATCH_RULE
            for m in pattern.finditer(text):
                report(rel, text, m, what)
        if rel.startswith(BUILD_LOGIC_MAIN):
            code = strip_comments(path.read_text(encoding="utf-8"))
            at = code.find(KOIN_ANNOTATIONS_DEP)
            if at >= 0:
                violations.append(f"{rel}:{code[:at].count(chr(10)) + 1}: 不依赖 koin-annotations")
        if path.name == ENTRIES:
            for m in re.finditer(r"\bkoinViewModel\b", text):
                report(rel, text, m, "AppEntries 不创建 ViewModel——页面自己用 koinViewModel { parametersOf(…) } 取")
        for m in KOIN_ANNOTATION_IMPORT.finditer(text):
            report(rel, text, m, "不用 Koin 注解——用经典 DSL 注册（di/<Feature>Module.kt）")
        for m in FAILURE_SNIFF.finditer(text):
            report(rel, text, m, "不嗅探失败文本——失败是 Failure，异常转 Failure 只在 core:network 的 toFailure()")
        if rel.startswith(DATA_LAYER_MAIN):
            for m in FAILURE_NOTICES.finditer(text):
                report(rel, text, m, "数据层不持有 FailureNotices——返回 Outcome / Load，由 ViewModel 自己的 failures 提示")
    for pattern in BUILD_FILES:
        for path in sorted(root.glob(pattern)):
            text = path.read_text(encoding="utf-8")
            at = text.find(KOIN_ANNOTATIONS_DEP)
            if at >= 0:
                violations.append(f"{path.relative_to(root).as_posix()}:{text[:at].count(chr(10)) + 1}: 不依赖 koin-annotations")
    for path in test_sources(root):
        rel = path.relative_to(root).as_posix()
        if rel == SET_MAIN_HOME:
            continue
        text = normalize_code(path.read_text(encoding="utf-8"), typealiases)
        for m in SET_MAIN.finditer(text):
            if is_import_line(text, m.start()):
                continue
            report(rel, text, m, "Dispatchers.setMain 只许在 TestMain 里（install / uninstall，拆除用 cancelAndAwait）")
    return violations


def unused_home(root: Path, home: str = SET_MAIN_HOME) -> list[str]:
    """setMain 的唯一放行文件不在了、或已不调 setMain：放行用不上了。"""
    path = root / home
    if path.is_file():
        text = normalize_code(path.read_text(encoding="utf-8"))
        if any(not is_import_line(text, m.start()) for m in SET_MAIN.finditer(text)):
            return []
    return [f"{home}: 放行用不上了（文件不在或已不调 setMain）——更新 SET_MAIN_HOME"]


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    stale = unused_home(root)
    for line in stale:
        print(line, file=sys.stderr)
    violations = find_violations(root)
    for v in violations:
        print(v, file=sys.stderr)
    if violations or stale:
        print(f"\nViewModel 规矩门禁未通过：{len(violations) + len(stale)} 处", file=sys.stderr)
        return 1
    print("ViewModel 规矩门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
