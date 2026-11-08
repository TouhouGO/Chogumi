#!/usr/bin/env bash
# 合并前的检查（唯一入口）。远端 CI（.github/workflows/ci.yml）跑的也是它：PR 上 `changed`，合入 main 后整仓；
# 什么时候合、认不认 CI 的结果见 docs/dev/workflow.md「合并前」。
#
#   tools/ci/check.sh                     整仓：门禁 → 合同 → 编译与全部单测
#   tools/ci/check.sh changed origin/main 按改动：门禁 → 合同 → tools/ci/scope.py 按改动的文件选整仓、按模块或到此为止
#   tools/ci/check.sh modules :core:ui …  按模块：门禁 → 合同 → 点名模块、依赖它们的模块与扫整仓的模块的单测与仪器测试编译，加 debug 包
#   tools/ci/check.sh lint                门禁：tools 下各脚本的自测、tools/guards 全部门禁
#   tools/ci/check.sh contracts           合同：OpenAPI 快照一致、DTO 覆盖率、合同测试
#   tools/ci/check.sh build               编译 debug 包与全部测试包，跑全部单测并按 JUnit XML 计数
#   tools/ci/check.sh warm                预热 Gradle 构建缓存：编译全部模块（含单测与仪器测试源码）、打 debug 包，单测只演不跑
#
# 整仓、按改动、按模块前面可加 `--part tests` 或 `--part builds`：只跑 Gradle 检查的那一份，不跑门禁与合同。
# 单测份是名为 test 的任务（跑完计数），构建份是其余（仪器测试编译、打包、lint、R8）。远端 CI 把门禁与合同、两份 Gradle
# 分成三个并行的任务；本地不加 `--part`，一次 Gradle 跑完两份，结果与 CI 相同。
#
# 按模块时，哪些模块要跑由 tools/ci/affected.py 决定（依赖图来自 Gradle，另加单测扫整仓源码的模块），开头打出模块清单；
# 这些模块的单测与仪器测试编译跑完后还编译 debug 包。
#
# 仪器测试（src/androidTest）不在这里：改到它们覆盖的界面时，用 tools/device/device.sh claim 拿一台模拟器跑
# `ANDROID_SERIAL=<那台> ./gradlew :<module>:connectedDebugAndroidTest`（见 docs/dev/workflow.md）。
#
# 各段依次调用、不用 && 连：&& 左边的函数里 set -e 不生效，中间一条门禁失败会被吞掉。
set -euo pipefail
cd "$(dirname "$0")/../.."

lint() {
    python3 -m unittest discover -s tools/device -p 'test_*.py'
    python3 -m unittest discover -s tools/ci -p 'test_*.py'
    python3 -m unittest discover -s tools/guards -p 'test_*.py'
    # tools/guards 下每个 check_*.py 都是一道门禁：放进去就跑，不另外登记
    for guard in tools/guards/check_*.py; do
        python3 "$guard"
    done
}

contracts() {
    (
        cd tools/openapi
        npm ci --silent
        node refresh.mjs --check
        node check-coverage.mjs
        node --test contract.test.mjs
    )
}

# Gradle 的模块、依赖与测试结果目录（唯一取法），交给 affected.py 与 count_tests.py
project_graph() {
    ./gradlew -q --no-configuration-cache -I tools/ci/project-graph.init.gradle.kts help
}

# 整仓的 Gradle 检查：全部单测，debug 包与全部测试包，benchmark 包（R8）与 lint
FULL_TASKS=(test :app:assembleDebug assembleDebugAndroidTest :app:assembleBenchmarkRelease :app:lintDebug
    :baselineprofile:compileBenchmarkReleaseKotlin)

# 只跑哪一份 Gradle 检查（--part）：all 两份都跑，tests 只跑名为 test 的任务，builds 只跑其余
part=all

# 本份的任务：从给出的任务里按 part 留下
part_tasks() {
    local task
    for task in "$@"; do
        case "$part:${task##*:}" in
            all:*|tests:test) echo "$task" ;;
            builds:test|tests:*) ;;
            builds:*) echo "$task" ;;
        esac
    done
}

# 跑本份的任务，本份含单测时按 JUnit XML 计数。参数：依赖图、要数的模块（空格分开，空 = 全部模块）、任务…
run_part() {
    local graph=$1 counted=$2 tasks
    shift 2
    tasks=$(part_tasks "$@")
    if [ -z "$tasks" ]; then
        echo "这一份（${part}）没有要跑的 Gradle 任务"
        return
    fi
    # shellcheck disable=SC2086 # 模块与任务名不含空白，按空白拆成参数
    ./gradlew --max-workers=4 $tasks
    if [ "$part" != builds ]; then
        # shellcheck disable=SC2086
        python3 tools/ci/count_tests.py $counted <<<"$graph"
    fi
}

# 依赖图（第一个参数）上的整仓检查
build_in() {
    run_part "$1" "" "${FULL_TASKS[@]}"
}

build() {
    local graph
    graph=$(project_graph)
    build_in "$graph"
}

# 依赖图（第一个参数）+ 点名模块
modules_in() {
    local graph=$1 plan
    shift
    plan=$(python3 tools/ci/affected.py "$@" <<<"$graph")
    local selected=${plan%%$'\n'*} tasks=${plan#*$'\n'}
    echo "按模块检查：$selected"
    # shellcheck disable=SC2086 # 任务名不含空白
    run_part "$graph" "$selected" $tasks
}

# 全部有单元测试的模块按模块检查的任务，单测只演不跑（Test 的 dryRun，build-logic 读 chogumi.testDryRun）：
# 编译与资源处理的产出进构建缓存，PR 上的检查从缓存取，不重编没改的模块
warm() {
    local graph plan
    graph=$(project_graph)
    # shellcheck disable=SC2046 # 模块路径不含空白
    plan=$(python3 tools/ci/affected.py $(awk -F'\t' '$1 == "chogumi-module" { print $2 }' <<<"$graph") <<<"$graph")
    # shellcheck disable=SC2086
    ./gradlew --max-workers=4 -Pchogumi.testDryRun=true ${plan#*$'\n'}
}

modules() {
    local graph
    graph=$(project_graph)
    modules_in "$graph" "$@"
}

changed() {
    local graph level
    graph=$(project_graph)
    level=$(python3 tools/ci/scope.py "$1" <<<"$graph")
    echo "按改动选范围：$level"
    case "$level" in
        full) build_in "$graph" ;;
        lint) ;;
        # shellcheck disable=SC2086 # 模块名不含空白
        modules\ *) modules_in "$graph" ${level#modules } ;;
        *) echo "scope.py 给出了不认识的范围：$level" >&2; exit 2 ;;
    esac
}

# 不加 --part 时先跑门禁与合同；加了 --part 只跑 Gradle 的那一份（门禁与合同由远端 CI 另一个任务跑）
guards() {
    if [ "$part" = all ]; then
        lint
        contracts
    fi
}

usage() {
    echo "用法：$0 [--part tests|builds] [changed <基准提交>|modules <模块>…]，或 $0 lint|contracts|build|warm" >&2
    exit 2
}

if [ "${1:-}" = --part ]; then
    case "${2:-}" in
        tests|builds) part=$2 ;;
        *) usage ;;
    esac
    shift 2
fi

case "${1:-all}" in
    lint|contracts|build|warm) [ "$part" = all ] || usage ;;
esac

case "${1:-all}" in
    lint) lint ;;
    contracts) contracts ;;
    build) build ;;
    warm) warm ;;
    modules)
        shift
        [ $# -gt 0 ] || { echo "用法：$0 modules <模块>…（如 :feature:home core/ui）" >&2; exit 2; }
        guards; modules "$@" ;;
    changed)
        [ $# -eq 2 ] || { echo "用法：$0 changed <基准提交>（如 origin/main）" >&2; exit 2; }
        guards; changed "$2" ;;
    all) guards; build ;;
    *) usage ;;
esac
