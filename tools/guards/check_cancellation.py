"""数据层取消门禁（唯一实现处；tools/ci/check.sh lint 运行，单跑：python3 tools/guards/check_cancellation.py）。

core:data 里「一个异常算什么失败」只在 `util/OutcomeOf.kt`（`outcomeOf` / `failureOf`）判定：取消照常抛出，途中换号记成
`Failure.SessionChanged`，其余经 core:network `toFailure()`。各处自己写「先接取消、再接其余」，漏一个分支就把取消吞掉，
协程该停不停；`runCatching { }` 包住挂起调用更是连取消一起收成失败。

检查项（只看 core/data/src/main）：
- 接住取消的 `catch` 只许出现在 `util/OutcomeOf.kt`、`token/SessionRequestHelper.kt`
  （要在 `requireCurrent` 之后再抛，见那里的注释）与 `util/SharedFetch.kt`（等着的一方要分清「自己被取消」和
  「发起方被取消」，后者换自己重发，不是把异常收成失败）。`CancellationException` 与它的父类型（`IllegalStateException`、
  `RuntimeException`、`Exception`、`Throwable`）都算：接住父类型就连取消一起接住了。这三个文件里没有这样的 `catch` 时报死配置。
- `runCatching` 只许出现在下面 ALLOWED_RUN_CATCHING 列出的不挂起的函数里（解析本地数据、同步的文件操作），各带理由；
  登记的名字是 `suspend fun` 也拦下。别处要收成结果，用 `outcomeOf { }`。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

from sources import is_import_line, kotlin_typealiases, main_sources, normalize_code

DATA_MAIN = "core/data/src/main"
CATCH_CANCELLATION = re.compile(
    r"\bcatch\s*\(\s*\w+\s*:\s*(?:[\w.]+\.)?"
    r"(CancellationException|IllegalStateException|RuntimeException|Exception|Throwable)\s*\)"
)
RUN_CATCHING = re.compile(r"\brunCatching\b")
# 拿到异常后按类型判断它是不是取消（`.catch { e -> if (e is CancellationException) … }`），和 catch 住它是同一件事
IS_CANCELLATION = re.compile(r"\bis\s+(?:[\w.]+\.)?CancellationException\b")
MODIFIERS = (
    "private|internal|public|protected|override|suspend|inline|operator|open|final|abstract|tailrec|external"
    "|actual|expect|infix|data|enum|sealed|inner|companion|lateinit|const|value|annotation"
)
# 任意层级的函数、属性、init 块与类型声明（类成员也算）：组 2 是函数名，组 3 是属性名，组 4 是 init 块或类型声明
DECL = re.compile(
    r"^([ \t]*)(?:@[\w.:]+(?:\([^)]*\))?\s+)*(?:(?:" + MODIFIERS + r")\s+)*"
    r"(?:fun\s+(?:<[^>]*>\s+)?(?:[\w.<>, ?]+\.)?(\w+)\s*\(|(?:val|var)\s+(\w+)|(init\s*\{|(?:class|object|interface)\b))",
    re.M,
)
SUSPEND = re.compile(r"\bsuspend\b")


def owner_of(decls: list[re.Match[str]], offset: int) -> re.Match[str] | None:
    """[offset] 处的代码归哪个声明：前面最近的函数；函数之后若有缩进不比它深的属性、init 块或类型声明，
    函数到那里已经结束，归最近的那一个。函数里更深的局部变量不改变归属。"""
    before = [d for d in decls if d.start() < offset]
    fun = next((d for d in reversed(before) if d.group(2)), None)
    for decl in reversed(before):
        if decl is fun:
            break
        if fun is None or len(decl.group(1)) <= len(fun.group(1)):
            return decl
    return fun


CANCELLATION_FILES = {
    "util/OutcomeOf.kt",
    "token/SessionRequestHelper.kt",
    "util/SharedFetch.kt",
}

# （相对 core/data 包根的文件, 声明名）→ 为什么这里可以用 runCatching
ALLOWED_RUN_CATCHING = {
    ("db/mapper/ColumnJson.kt", "toStringList"): "解码本地 JSON 列，同步；读不懂的旧行按空列表",
    ("db/mapper/ColumnJson.kt", "toTagList"): "同上",
    ("db/mapper/ColumnJson.kt", "toInfoboxList"): "同上",
    ("db/mapper/ColumnJson.kt", "toIntList"): "同上",
    ("db/mapper/CollectionMapper.kt", "toDomain"): "解析接口给的 ISO 时刻，同步；格式不对记 0",
    ("db/mapper/MonoMapper.kt", "toFactRow"): "资料表单条容错，同步",
    ("repository/HomeRepository.kt", "windowEpisodes"): "解码本地的章节 JSON 列，同步",
    ("db/explore/ShelfEntryJson.kt", "toShelfEntryOrNull"): "解码本地的货架 JSON 列，同步；读不懂的行跳过",
    ("db/explore/ShelfEntryJson.kt", "toSearchCriteriaOrNull"): "迁移时解码旧表的条件 JSON 列，同步；读不懂的行不迁",
    ("repository/SmileyAssetStore.kt", "cleanUp"): "启动时删旧文件，同步的文件操作",
    ("repository/SmileyAssetStore.kt", "writeAtomically"): "写临时文件再改名，同步的文件操作",
    ("repository/SmileyAssetStore.kt", "isUrlAllowed"): "解析 URL，同步",
}


# （相对 core/data 包根的文件, 声明名）→ 为什么这里可以自己判断收到的异常是不是取消
ALLOWED_IS_CANCELLATION = {
    ("repository/CollectionObserver.kt", "closeOnStrayCancellation"):
        "内层流抛出、而本协程仍活着的取消不是本协程被取消：关掉通道交给收集方，其余照常抛出",
}


def package_relative(path: Path, base: Path) -> str:
    rel = path.relative_to(base).as_posix()
    marker = "io/github/touhougo/chogumi/core/data/"
    return rel.split(marker, 1)[1] if marker in rel else rel


def find_violations(
    root: Path,
    allowed: dict[tuple[str, str | None], str] = ALLOWED_RUN_CATCHING,
    report_dead: bool = True,
    is_allowed: dict[tuple[str, str | None], str] = ALLOWED_IS_CANCELLATION,
) -> list[str]:
    base = root / DATA_MAIN
    violations: list[str] = []
    used: set[tuple[str, str | None]] = set()
    used_is: set[tuple[str, str | None]] = set()
    used_files: set[str] = set()
    paths = main_sources(root)
    typealiases = kotlin_typealiases(paths)
    for path in sorted(path for path in paths if base in path.parents):
        rel = package_relative(path, base)
        shown = path.relative_to(root).as_posix()
        text = normalize_code(path.read_text(encoding="utf-8"), typealiases)
        catches = list(CATCH_CANCELLATION.finditer(text))
        if rel in CANCELLATION_FILES:
            if catches:
                used_files.add(rel)
        else:
            for match in catches:
                line = text.count("\n", 0, match.start()) + 1
                violations.append(
                    f"{shown}:{line}: catch ({match.group(1)}) 接住了取消——改用 outcomeOf {{ }}"
                    "（取消照常抛出、换号记成 SessionChanged，见 util/OutcomeOf.kt）"
                )
        decls = list(DECL.finditer(text))
        if rel not in CANCELLATION_FILES:
            for match in IS_CANCELLATION.finditer(text):
                owner = owner_of(decls, match.start())
                name = owner.group(2) or owner.group(3) if owner else None
                if (rel, name) in is_allowed:
                    used_is.add((rel, name))
                    continue
                line = text.count("\n", 0, match.start()) + 1
                violations.append(
                    f"{shown}:{line}: 自己判断收到的异常是不是取消——改用 outcomeOf {{ }} 或 failuresAsLoad / storageFailuresAsLoad"
                    "（取消照常抛出）；确有理由就在 tools/guards/check_cancellation.py 的 ALLOWED_IS_CANCELLATION 里登记"
                )
        for match in RUN_CATCHING.finditer(text):
            if is_import_line(text, match.start()):
                continue
            owner = owner_of(decls, match.start())
            name = owner.group(2) or owner.group(3) if owner else None
            line = text.count("\n", 0, match.start()) + 1
            if (rel, name) in allowed:
                used.add((rel, name))
                if owner.group(2) and SUSPEND.search(owner.group(0)):
                    violations.append(
                        f"{shown}:{line}: 登记的 {name} 是挂起函数——runCatching 会把取消一起收成失败，改用 outcomeOf {{ }}"
                    )
                continue
            violations.append(
                f"{shown}:{line}: runCatching 会把取消一起收成失败——改用 outcomeOf {{ }}；"
                "确实是不挂起的解析或文件操作，就在 tools/guards/check_cancellation.py 的 ALLOWED_RUN_CATCHING 里登记并写明理由"
            )
    if report_dead:
        for rel, name in sorted(set(allowed) - used, key=str):
            violations.append(f"白名单条目 {rel}::{name} 已匹配不到 runCatching，删掉这条（死配置）")
        for rel, name in sorted(set(is_allowed) - used_is, key=str):
            violations.append(f"白名单条目 {rel}::{name} 已不判断取消，从 ALLOWED_IS_CANCELLATION 删掉（死配置）")
        for rel in sorted(CANCELLATION_FILES - used_files):
            violations.append(f"取消判定文件 {rel} 里已没有接取消的 catch，从 CANCELLATION_FILES 删掉（死配置）")
    return violations


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    violations = find_violations(root)
    for v in violations:
        print(v, file=sys.stderr)
    if violations:
        print(f"\n数据层取消门禁未通过：{len(violations)} 处", file=sys.stderr)
        return 1
    print("数据层取消门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
