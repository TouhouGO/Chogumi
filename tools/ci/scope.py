"""按改动选检查范围（唯一实现处；tools/ci/check.sh changed 运行，远端 CI 的 PR 检查就是它）。

    python3 tools/ci/scope.py <基准提交> < 依赖图

改动的文件是工作区相对分叉点（`git merge-base <基准> HEAD`）的全部改动：已提交、已暂存、未暂存，加上没被忽略的新文件。
Gradle 跑的是工作区，范围也按工作区选，否则没提交的改动（比如一处构建脚本）不进范围，检查却照样报绿。CI 的检出是干净的，
结果就是分支相对基准的提交。改名按删掉旧路径加上新路径算，读旧路径的测试也要跑。依赖图同 affected.py。输出一行：

- `full`：整仓检查。改到构建本身（build-logic、gradle/ 下的版本与包装器、根构建脚本、gradle.properties）、只进正式包的东西
  （app 的构建脚本、R8 规则 `*.pro`、app 的 release 源集：按模块不打 release 与 benchmark 包，R8 只在整仓跑）、Room schema、
  主题与 tokens、检查本身（tools/ci、.github 的 workflows 与 actions），或不在任何有单元测试的模块里的 Gradle 模块（baselineprofile）。
- `modules <模块>…`：改到的模块，加上单元测试经 `RepoFiles` 按路径读到改动文件的模块；依赖它们的模块与扫整仓源码的模块
  由 affected.py 再补上。
- `lint`：只改模块以外的文件（文档、tools/），也没有单元测试读它们：门禁与合同就够了。

决定的理由逐条打到标准错误，CI 日志里看得到为什么选了这一档。
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

from affected import parse_graph

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools/guards"))
from sources import strip_comments  # noqa: E402  门禁共用的 Kotlin 注释扫描

# 改到就跑整仓的位置：目录以 / 结尾按前缀比，其余按整个路径比
FULL = (
    "build-logic/", "gradle/", "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat",
    "app/build.gradle.kts", "app/src/release/",
    "core/data/schemas/", "core/ui/src/main/kotlin/io/github/touhougo/chogumi/core/ui/theme/",
    "tools/ci/", ".github/workflows/", ".github/actions/",
)
# 改到就跑整仓的文件后缀：R8 规则只在正式包与 benchmark 包里生效
FULL_SUFFIXES = (".pro",)

# 测试源码里的单行字符串字面量；取到第一个 `$` 为止（模板里的路径只认前面的固定部分）
LITERAL = re.compile(r'"((?:[^"\\\n]|\\.)*)"')


def full_reason(path: str) -> bool:
    return path.endswith(FULL_SUFFIXES) or any(
        path.startswith(entry) if entry.endswith("/") else path == entry for entry in FULL)


def module_of(path: str, directories: dict[str, str]) -> str | None:
    """路径所在的模块（目录最长的那个），不在任何模块里时为 None。"""
    owners = [name for name, directory in directories.items() if path.startswith(directory + "/")]
    return max(owners, key=lambda name: len(directories[name])) if owners else None


def gradle_project_of(path: str, root: Path) -> str | None:
    """路径所在、根目录以下最近的 Gradle 项目目录（有 build.gradle.kts），没有时为 None。"""
    parts = Path(path).parts[:-1]
    for end in range(len(parts), 0, -1):
        directory = Path(*parts[:end])
        if (root / directory / "build.gradle.kts").is_file():
            return directory.as_posix()
    return None


def reads(literals: set[str], path: str, root: Path) -> bool:
    """路径相同、带尾斜杠的模板前缀，或仓库中真实目录的后代；同名文件前缀不算。"""
    return path in literals or any(
        path.startswith(literal if literal.endswith("/") else literal + "/")
        for literal in literals
        if literal.endswith("/") or (root / literal).is_dir()
    )


def scope(changed: list[str], directories: dict[str, str], test_literals: dict[str, set[str]], root: Path):
    """(档位, 点名模块, 理由)。directories：模块 → 相对仓库根的目录；test_literals：模块 → 其单元测试里的字符串字面量。"""
    named, reasons = set(), []
    for path in changed:
        if full_reason(path):
            return "full", [], [f"{path}：改到构建或检查本身"]
        module = module_of(path, directories)
        if module:
            named.add(module)
        else:
            project = gradle_project_of(path, root)
            if project:
                return "full", [], [f"{path}：属于没有单元测试的 Gradle 模块 {project}"]
        readers = sorted(name for name, literals in test_literals.items() if reads(literals, path, root))
        named.update(readers)
        owner = f"属于 {module}" if module else "模块以外"
        reasons.append(f"{path}：{owner}" + (f"，{'、'.join(readers)} 的单元测试读它" if readers else ""))
    if named:
        return "modules", sorted(named), reasons
    return "lint", [], reasons


def test_literals_of(directories: dict[str, str], root: Path) -> dict[str, set[str]]:
    literals = {}
    for name, directory in directories.items():
        found = set()
        for source in (root / directory / "src/test").rglob("*.kt"):
            found.update(literal.split("$", 1)[0] for literal in LITERAL.findall(strip_comments(source.read_text(encoding="utf-8"))))
        literals[name] = {literal for literal in found if literal}
    return literals


def git(*args: str) -> str:
    result = subprocess.run(["git", "-C", str(REPO), *args], capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(result.stderr.strip())
    return result.stdout


def changed_paths(base: str) -> list[str]:
    """工作区相对 base 与 HEAD 分叉点的改动（已提交、已暂存、未暂存）加上没被忽略的新文件，去重后按出现顺序。"""
    fork = git("merge-base", base, "HEAD").strip()
    tracked = git("diff", "--name-only", "--no-renames", "-z", fork)
    untracked = git("ls-files", "--others", "--exclude-standard", "-z")
    return list(dict.fromkeys(path for path in (tracked + untracked).split("\0") if path))


def main(argv, stdin) -> int:
    if len(argv) != 1:
        print("用法：scope.py <基准提交> < 依赖图", file=sys.stderr)
        return 2
    modules, _ = parse_graph(stdin)
    directories = {name: Path(directory).resolve().relative_to(REPO).as_posix() for name, (directory, _) in modules.items()}
    try:
        changed = changed_paths(argv[0])
    except RuntimeError as error:
        print(f"取不到相对 {argv[0]} 的改动：{error}", file=sys.stderr)
        return 2
    if not changed:
        print(f"相对 {argv[0]} 没有改动，没有可检查的范围", file=sys.stderr)
        return 2
    level, named, reasons = scope(changed, directories, test_literals_of(directories, REPO), REPO)
    for reason in reasons:
        print(reason, file=sys.stderr)
    print(" ".join([level, *named]))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:], sys.stdin))
