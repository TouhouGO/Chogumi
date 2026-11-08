"""按模块检查的范围（唯一实现处；tools/ci/check.sh modules 运行）。

    python3 tools/ci/affected.py <模块>… < 依赖图

依赖图是 tools/ci/project-graph.init.gradle.kts 打出的行。要跑的模块是：
1. 点名的模块，以及所有直接、间接依赖它们的模块（改了下层，上层可能编不过、测不过）；
2. 单元测试扫整仓源码的模块（经 `RepoFiles.mainKotlinFiles` / `RepoFiles.offenders` / `RepoFiles.sourceFiles`，
   「某种写法只许一处」「资源没人引用」的契约）：
   它们的断言覆盖别的模块的源码，点名的模块不在依赖链上也会被它们拦下。

输出两行：第一行是这些模块的路径（交给 count_tests.py 计数），第二行是 Gradle 任务。
模块可以写成 `:core:ui` 或 `core/ui`。
"""
import sys
from pathlib import Path

# 扫整仓源码的入口（core:foundation 测试夹具 RepoFiles，test_affected.py 核对它们还在）
REPO_SCANS = ("RepoFiles.mainKotlinFiles(", "RepoFiles.offenders(", "RepoFiles.sourceFiles(")


def parse_graph(lines):
    """依赖图 → ({模块路径: (目录, [检查任务])}, {被依赖方: {使用方}})。不认识的行（Gradle 自己的输出）跳过。"""
    modules, users = {}, {}
    for line in lines:
        fields = line.rstrip("\n").split("\t")
        if fields[0] == "chogumi-module":
            modules[fields[1]] = (Path(fields[2]), fields[3:])
        elif fields[0] == "chogumi-edge" and fields[1] != fields[2]:
            users.setdefault(fields[2], set()).add(fields[1])
    return modules, users


def normalize(name):
    """`core/ui`、`:core:ui` 都是 `:core:ui`。"""
    return name if name.startswith(":") else ":" + name.strip("/").replace("/", ":")


def scans_repo(directory):
    """该模块的单元测试是否扫整仓源码。"""
    return any(
        any(scan in path.read_text(encoding="utf-8") for scan in REPO_SCANS)
        for path in (directory / "src" / "test").rglob("*.kt")
    )


def affected(named, modules, users):
    """点名模块 + 依赖它们的模块 + 扫整仓的模块，按模块路径排序。"""
    unknown = [name for name in named if name not in modules]
    if unknown:
        raise ValueError(f"不是有单元测试的模块：{'、'.join(unknown)}（可选：{'、'.join(sorted(modules))}）")
    result, pending = set(), list(named)
    while pending:
        name = pending.pop()
        if name not in result:
            result.add(name)
            pending.extend(users.get(name, ()))
    result.update(name for name, (directory, _) in modules.items() if scans_repo(directory))
    return sorted(result)


def main(argv, stdin):
    if not argv:
        print("用法：affected.py <模块>… < 依赖图", file=sys.stderr)
        return 2
    modules, users = parse_graph(stdin)
    try:
        selected = affected([normalize(name) for name in argv], modules, users)
    except ValueError as error:
        print(error, file=sys.stderr)
        return 2
    print(" ".join(selected))
    print(" ".join(f"{name}:{task}" for name in selected for task in modules[name][1]))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:], sys.stdin))
