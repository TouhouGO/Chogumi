"""按当前 Gradle 登记的 JUnit XML 目录数单元测试（报数的唯一口径；tools/ci/check.sh 运行），失败或错误时以非零退出。

    python3 tools/ci/count_tests.py < 依赖图              数全部模块（整仓 build 之后）
    python3 tools/ci/count_tests.py :core:ui … < 依赖图   只数这几个模块（按模块检查之后：别的模块磁盘上是上一轮的旧结果）

依赖图是 tools/ci/project-graph.init.gradle.kts 打出的行（check.sh 的 project_graph），每个登记的 Test 任务一行
`chogumi-test-xml`。只数这些目录：改名或删掉的任务、模块，旧结果还留在磁盘上时不算进来。
图里一行 `chogumi-test-xml` 都没有，或入选结果合计没有任何测试时，报错退出而不报成功。
没有源码的任务可以不生成报告；但入选模块的 `src/test` 里有源码、登记的目录里却一份结果都没有（测试任务被跳过、禁用或没跑），
也报错：别的模块的结果不能替它报绿。
"""
import re
import sys
import xml.etree.ElementTree as ET
from collections import OrderedDict
from pathlib import Path

from affected import normalize

COUNT_ATTRIBUTES = ("tests", "skipped", "failures", "errors")


def result_counts(path: Path):
    """一个 JUnit XML 里的总数、跳过、失败与错误；格式不对时由调用方拒绝整次计数。"""
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError) as error:
        raise ValueError(f"{path}: 无法解析 JUnit XML（{error}）") from None
    if root.tag == "testsuite":
        if root.find("testsuite") is not None:
            raise ValueError(f"{path}: testsuite 根中不能嵌套 testsuite")
        suites = [root]
    elif root.tag == "testsuites":
        suites = list(root)
        if any(suite.tag != "testsuite" for suite in suites):
            raise ValueError(f"{path}: testsuites 只能直接包含 testsuite")
        if any(suite.find("testsuite") is not None for suite in suites):
            raise ValueError(f"{path}: testsuites 中不能嵌套 testsuite")
    else:
        raise ValueError(f"{path}: 根元素不是 testsuite 或 testsuites")
    if not suites:
        raise ValueError(f"{path}: 没有 testsuite")

    counts = [0, 0, 0, 0]
    for suite in suites:
        values = []
        for attribute in COUNT_ATTRIBUTES:
            value = suite.get(attribute)
            if value is None or re.fullmatch(r"\d+", value) is None:
                raise ValueError(f"{path}: testsuite 缺少有效的 {attribute} 计数")
            values.append(int(value))
        if values[1] + values[2] + values[3] > values[0]:
            raise ValueError(f"{path}: testsuite 的跳过、失败与错误之和超过 tests")
        cases = suite.findall("testcase")
        actual = [len(cases)] + [sum(case.find(status) is not None for case in cases) for status in ("skipped", "failure", "error")]
        if values != actual:
            raise ValueError(f"{path}: testsuite 汇总与用例不一致：汇总 {values}，用例 {actual}")
        for index, value in enumerate(values):
            counts[index] += value
    return counts


def has_test_sources(directory):
    """模块目录下 src/test 里有没有 Kotlin / Java 源码；图里没有模块目录时不判。"""
    return directory is not None and any(
        path.suffix in (".kt", ".java") for path in (directory / "src" / "test").rglob("*") if path.is_file())


def main():
    selected = {normalize(name) for name in sys.argv[1:]}
    registered = False
    directories = set()
    module_dirs = {}
    modules = OrderedDict()
    for line in sys.stdin:
        fields = line.rstrip("\n").split("\t")
        if len(fields) >= 3 and fields[0] == "chogumi-module":
            module_dirs[fields[1]] = Path(fields[2])
        if len(fields) != 3 or fields[0] != "chogumi-test-xml":
            continue
        registered = True
        _, name, directory = fields
        if selected and name not in selected:
            continue
        directories.add((name, directory))
    if not registered:
        print("依赖图里没有一行 chogumi-test-xml，不知道该数哪些测试结果：查 tools/ci/project-graph.init.gradle.kts 与 Gradle 的输出",
              file=sys.stderr)
        return 2

    for name, directory in sorted(directories):
        module = name.lstrip(":").replace(":", "/")
        for path in sorted(Path(directory).glob("TEST-*.xml")):
            try:
                counts = result_counts(path)
            except ValueError as error:
                print(error, file=sys.stderr)
                return 2
            if counts[0] == 0:
                print(f"{path}: testsuite 记录 0 条测试；没有源码的任务不应生成结果文件", file=sys.stderr)
                return 2
            module_counts = modules.setdefault(module, [0, 0, 0, 0])
            for index, value in enumerate(counts):
                module_counts[index] += value

    silent = sorted(name for name in {name for name, _ in directories}
                    if name.lstrip(":").replace(":", "/") not in modules and has_test_sources(module_dirs.get(name)))
    if silent:
        print(f"{'、'.join(silent)} 有单元测试源码，登记的结果目录里却没有任何结果：测试任务被跳过、禁用或没有执行",
              file=sys.stderr)
        return 2

    total = sum(c[0] for c in modules.values())
    if total == 0:
        scope = "、".join(sorted(selected)) if selected else "全部登记模块"
        print(f"{scope}没有读到任何测试：检查 Gradle 的执行结果与登记的 JUnit XML 目录", file=sys.stderr)
        return 2
    skipped = sum(c[1] for c in modules.values())
    if total == skipped:
        scope = "、".join(sorted(selected)) if selected else "全部登记模块"
        print(f"{scope}的 {total} 条测试全部跳过，没有任何实际执行的测试", file=sys.stderr)
        return 2

    for module, (tests, module_skipped, failures, errors) in modules.items():
        print(f'{module}\t{tests}' + (f'\t跳过 {module_skipped}' if module_skipped else '') + (f'\t失败 {failures + errors}' if failures + errors else ''))
    broken = sum(c[2] + c[3] for c in modules.values())
    print(f'单元测试共 {total} 条，失败 {broken} 条')
    return 1 if broken else 0


if __name__ == "__main__":
    sys.exit(main())
