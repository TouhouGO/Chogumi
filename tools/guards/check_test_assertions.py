"""测试断言门禁（唯一实现处；tools/ci/check.sh lint 运行，单跑：python3 tools/guards/check_test_assertions.py）。

一个 @Test 函数里没有断言，它就只是「跑一遍不崩」：截图、录屏、把清单打印出来给人看。这类函数报成测试，测试数虚高，
而它们什么都没检查——界面改坏了照样绿。截图与录屏是给人看的产物，不是测试：标 `@VisualCapture`（core:ui
testFixtures），默认的 connectedDebugAndroidTest 不跑、不计数，要出图时带 `-Pchogumi.captures` 单独跑
（docs/dev/guide.md §4.2）。

检查项：
1. 单元测试、仪器测试与 baseline profile 的每个 @Test 函数都要有断言：直接调用 assert* / fail / 标准 Kotlin
   check / checkNotNull / require / requireNotNull，或 @Test(expected = …)，或调用了会断言的辅助函数——函数体里
   有断言（可以隔几层）。辅助函数按名字找：先找同一个文件里声明的，没有再找同一个源码集其他文件与各模块
   testFixtures 里非 private 的。
2. 标了 @VisualCapture 的函数（或所在的类）不许有断言：有断言就是测试，标了它默认运行会跳过。
3. 截图落盘只经 core:ui testFixtures 的 `saveScreenshot`：测试源码里自己把位图压成文件（`CompressFormat`）的拦下——
   各写一份会各落一处，写进测试包自己的目录还会随卸载一起删掉。
"""

from __future__ import annotations

import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

from sources import kotlin_typealiases, normalize_code, test_source_scope, test_sources

ASSERTION = re.compile(r"\b(?:assert\w*|fail)\s*[(<{]")
KOTLIN_ASSERTION = re.compile(r"(?<![\w.])(check|checkNotNull|require|requireNotNull)\s*\(")
QUALIFIED_KOTLIN_ASSERTION = re.compile(r"\bkotlin\s*\.\s*(?:check|checkNotNull|require|requireNotNull)\s*\(")
TEST_ANNOTATION = re.compile(r"@(?:\w+\s*\.\s*)*Test\b(\s*\([^)]*\))?")
CAPTURE_ANNOTATION = re.compile(r"@VisualCapture\b")
PRIVATE = re.compile(r"\bprivate\b")
FUN_DECL = re.compile(r"\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(`[^`]+`|\w+)\s*\(")
CLASS_DECL = re.compile(r"\b(?:class|object)\s+\w+")
VALUE_DECL = re.compile(r"\b(?:val|var)\s+(\w+)\b")
PARAMETER_NAME = re.compile(
    r"(?:^|,)\s*(?:@[\w.]+(?:\([^)]*\))?\s+)*(?:(?:vararg|noinline|crossinline)\s+)*"
    r"(?:(?:val|var)\s+)?(\w+)\s*:",
    re.MULTILINE,
)
BITMAP_WRITE = re.compile(r"\bCompressFormat\b")
SCREENSHOT_CALL = re.compile(r"\bsaveScreenshot\s*\(")
SCREENSHOT_REFERENCE_CALL = re.compile(
    r"(?:\(\s*::\s*saveScreenshot\s*\)|::\s*saveScreenshot)\s*(?:\.\s*invoke\s*)?\("
)
ASSIGNMENT_LINE = re.compile(r"(?m)(?:^|;)\s*(?:(?:val|var)\s+)?(\w+)(?:\s*:[^;\n=]+)?\s*=\s*([^;\n]+)")
SCREENSHOT_REFERENCE = re.compile(r"^\s*::\s*saveScreenshot\b")
# 这个测试实际调用 saveScreenshot 来证明 PNG 编码失败会先失败，不访问 UiAutomation。
SCREENSHOT_FAILURE_SELF_TEST = (
    "core/ui/src/test/kotlin/io/github/touhougo/chogumi/core/ui/testing/ScreenshotFilesTest.kt"
)
CALL = re.compile(r"(?<![\w`])(\w+)\s*[(<{]|`([^`]+)`\s*\(")
# 跨行注解最多往上找几行
MAX_ANNOTATION_LINES = 12
# 声明前面那几行里能出现的：注解（参数可跨行）、修饰符、空白
PREFIX_LINE = re.compile(r"(?:@[\w.]+(?:\([^)]*\))?|private|internal|public|protected|open|abstract|override|suspend|data|inline|\s)*")


def matching_brace(code: str, open_at: int) -> int:
    depth = 0
    for i in range(open_at, len(code)):
        if code[i] == "{":
            depth += 1
        elif code[i] == "}":
            depth -= 1
            if depth == 0:
                return i
    return len(code) - 1


def prefix_of(code: str, at: int) -> str:
    """声明前面紧挨着的注解与修饰符：同一行里 fun / class 之前的部分，加上往上连续的注解行。
    空行（代码视图里注释也成了空白）跳过；参数跨几行的注解（`@Suppress(` 到 `)`）整段算一行。"""
    line_start = code.rfind("\n", 0, at) + 1
    lines = [code[line_start:at]]
    while line_start > 0:
        block_start = previous_prefix_block(code, line_start)
        if block_start is None:
            break
        lines.insert(0, code[block_start:line_start - 1])
        line_start = block_start
    return "\n".join(lines)


def previous_prefix_block(code: str, line_start: int) -> int | None:
    """[line_start] 那一行上面紧挨着的一段的起点：空行、一行注解与修饰符，或从 `@` 开头跨几行的一个注解；都不是就是 None。"""
    start = code.rfind("\n", 0, line_start - 1) + 1
    if PREFIX_LINE.fullmatch(code[start:line_start - 1]):
        return start
    for _ in range(MAX_ANNOTATION_LINES):
        if start == 0:
            return None
        start = code.rfind("\n", 0, start - 1) + 1
        block = code[start:line_start - 1]
        if block.lstrip().startswith("@"):
            return start if PREFIX_LINE.fullmatch(block) else None
    return None


@dataclass
class Function:
    name: str
    line: int
    prefix: str
    body: str
    start: int
    body_start: int
    body_end: int
    parameters: set[str]
    called: set[str] = field(default_factory=set)
    nested: bool = False
    scoped_values: set[str] = field(default_factory=set)

    @property
    def private(self) -> bool:
        return bool(PRIVATE.search(self.prefix))


def matching_paren(code: str, open_at: int) -> int:
    depth = 0
    for i in range(open_at, len(code)):
        if code[i] == "(":
            depth += 1
        elif code[i] == ")":
            depth -= 1
            if depth == 0:
                return i
    return len(code) - 1


def body_of(code: str, params_end: int) -> tuple[str, int, int] | None:
    """参数表之后的函数体：花括号体，或 `=` 后的表达式（从 `=` 算起；同一行有花括号就取到配对处，否则取到下一个空行）。
    参数表后先遇到空行或下一个声明，就是没有函数体（抽象声明、接口方法）。"""
    head = re.compile(r"[^{=]*?(?=[{=])", re.S).match(code, params_end + 1)
    if not head or re.search(r"\n\s*\n|\b(?:fun|val|var|class)\b|@", head.group()):
        return None
    at = head.end()
    if code[at] == "=":
        line_end = code.find("\n", at)
        line_end = len(code) if line_end < 0 else line_end
        brace = code.find("{", at, line_end)
        if brace < 0:
            gap = re.compile(r"\n\s*\n").search(code, at)
            end = gap.start() if gap else len(code)
            return code[at:end], at, end
        # `= helper { … }`：表达式从 `=` 算起，取到这对花括号的配对处，花括号前的调用也是函数体
        end = matching_brace(code, brace) + 1
        return code[at:end], at, end
    end = matching_brace(code, at) + 1
    return code[at:end], at, end


def parent_braces(code: str, starts: list[int]) -> dict[int, int | None]:
    """各函数声明直接所在的未配对花括号；没有则是文件顶层。"""
    wanted = set(starts)
    parents: dict[int, int | None] = {}
    stack: list[int] = []
    for index, char in enumerate(code):
        if index in wanted:
            parents[index] = stack[-1] if stack else None
        if char == "{":
            stack.append(index)
        elif char == "}" and stack:
            stack.pop()
    return parents


def container_spans(code: str) -> list[tuple[int, int]]:
    """class / object 的正文范围。"""
    spans: list[tuple[int, int]] = []
    for match in CLASS_DECL.finditer(code):
        brace = code.find("{", match.end())
        if brace >= 0:
            spans.append((brace, matching_brace(code, brace)))
    return spans


def value_declarations(code: str, start: int, end: int, excluded: list[tuple[int, int]]) -> set[str]:
    return {
        match.group(1)
        for match in VALUE_DECL.finditer(code, start, end)
        if not any(body_start <= match.start() < body_end for body_start, body_end in excluded)
    }


def functions(code: str) -> list[Function]:
    """每个有函数体的 fun，并记录函数体范围、参数与可直接遮蔽标准检查的本地值名。"""
    found: list[Function] = []
    for match in FUN_DECL.finditer(code):
        params_start = match.end() - 1
        params_end = matching_paren(code, params_start)
        body_span = body_of(code, params_end)
        if body_span is None:
            continue
        body, body_start, body_end = body_span
        called = {a or b for a, b in CALL.findall(body)}
        found.append(Function(
            name=match.group(1).strip("`"),
            line=code.count("\n", 0, match.start()) + 1,
            prefix=prefix_of(code, match.start()),
            body=body,
            start=match.start(),
            body_start=body_start,
            body_end=body_end,
            parameters=set(PARAMETER_NAME.findall(code[params_start + 1:params_end])),
            called=called,
        ))
    parents = parent_braces(code, [f.start for f in found])
    containers = container_spans(code)
    class_braces = {start for start, _ in containers}
    function_bodies = [(f.body_start, f.body_end) for f in found]
    for f in found:
        parent = parents.get(f.start)
        f.nested = parent is not None and parent not in class_braces
        nested_bodies = [
            (g.body_start, g.body_end)
            for g in found
            if g is not f and f.body_start <= g.body_start < f.body_end
        ]
        scoped = f.parameters | value_declarations(code, f.body_start, f.body_end, nested_bodies)
        containing = [(start, end) for start, end in containers if start <= f.start <= end]
        if containing:
            class_start, class_end = max(containing, key=lambda span: span[0])
            scoped |= value_declarations(code, class_start, class_end + 1, function_bodies)
        f.scoped_values = scoped
    return found


def capture_spans(code: str) -> list[tuple[int, int]]:
    """标了 @VisualCapture 的类的范围。"""
    spans: list[tuple[int, int]] = []
    for match in CLASS_DECL.finditer(code):
        if not CAPTURE_ANNOTATION.search(prefix_of(code, match.start())):
            continue
        brace = code.find("{", match.end())
        if brace >= 0:
            spans.append((brace, matching_brace(code, brace)))
    return spans


def has_qualified_kotlin_assertion(code: str) -> bool:
    """显式 kotlin.* 标准检查；允许点和空白跨行，但不接受接在成员访问后的 kotlin。"""
    for match in QUALIFIED_KOTLIN_ASSERTION.finditer(code):
        prefix = code[:match.start()].rstrip()
        if not prefix or prefix[-1] != ".":
            return True
    return False


def standalone_kotlin_assertion_names(code: str) -> list[str]:
    """未限定的标准检查调用；带空白的成员访问 . check(...) 仍按成员处理。"""
    names: list[str] = []
    for match in KOTLIN_ASSERTION.finditer(code):
        prefix = code[:match.start()].rstrip()
        if not prefix or prefix[-1] != ".":
            names.append(match.group(1))
    return names


def visibly_local_names(f: Function, funcs: list[Function]) -> set[str]:
    """当前函数可见的本地 helper 名字：同 scope、祖先、自身及自身的 nested 函数。"""
    return {
        g.name
        for g in funcs
        if not g.nested
        or g is f
        or f.body_start <= g.start < f.body_end
        or g.body_start <= f.start < g.body_end
    }


def asserting(files: list[list[Function]], shared: list[Function], kotlin_assertion_functions: set[int]) -> set[int]:
    """会断言的函数（按 id）：函数体里直接断言，或调用的名字解析到一个会断言的函数。

    名字解析：同一个文件里有这个名字的声明就只认它们，否则认 [shared]（同一个源码集与 testFixtures 里非 private 的）。
    testFixtures 里的标准 Kotlin check / require 是准备输入的前置条件；截图夹具也常见，因此只有实际测试源码里的
    这些调用直接算断言。夹具里显式的 assert / fail 仍可作为辅助函数断言链。
    """
    shared_by_name: dict[str, list[Function]] = {}
    for f in shared:
        shared_by_name.setdefault(f.name, []).append(f)
    resolved: list[tuple[Function, list[Function]]] = []
    result: set[int] = set()
    for funcs in files:
        local: dict[str, list[Function]] = {}
        for f in funcs:
            local.setdefault(f.name, []).append(f)
        for f in funcs:
            targets = [t for name in f.called for t in local.get(name, shared_by_name.get(name, [])) if t is not f]
            resolved.append((f, targets))
            kotlin_checks = standalone_kotlin_assertion_names(f.body) if id(f) in kotlin_assertion_functions else []
            qualified_kotlin_check = has_qualified_kotlin_assertion(f.body) if id(f) in kotlin_assertion_functions else False
            visible_local = visibly_local_names(f, funcs)
            bare_kotlin_check = any(
                name not in f.scoped_values and name not in visible_local and name not in shared_by_name
                for name in kotlin_checks
            )
            if ASSERTION.search(f.body) or qualified_kotlin_check or bare_kotlin_check:
                result.add(id(f))
    while True:
        more = {id(f) for f, targets in resolved if id(f) not in result and any(id(t) in result for t in targets)}
        if not more:
            return result
        result |= more


def calls_screenshot_reference(body: str) -> bool:
    """按同一函数内的赋值顺序解析 ::saveScreenshot → 局部别名 → 实际调用的单层可调用链。"""
    assignments = list(ASSIGNMENT_LINE.finditer(body))

    def refers_to_screenshot(name: str, before: int) -> bool:
        latest = next((match for match in reversed(assignments) if match.group(1) == name and match.start() < before), None)
        if latest is None:
            return False
        initializer = latest.group(2).strip()
        if SCREENSHOT_REFERENCE.match(initializer):
            return True
        source = re.fullmatch(r"([A-Za-z_]\w*)", initializer)
        return source is not None and refers_to_screenshot(source.group(1), latest.start())

    names = {match.group(1) for match in assignments}
    for name in names:
        for call in re.finditer(r"(?<![\w`])" + re.escape(name) + r"\s*(?:\.\s*invoke\s*)?\(", body):
            if re.search(r"\bfun\s+$", body[max(0, call.start() - 16):call.start()]):
                continue
            if refers_to_screenshot(name, call.start()):
                return True
    return False


def screenshotting(files: list[list[Function]], shared: list[Function]) -> set[int]:
    """直接或经同源测试 helper 间接调用标准截图落盘函数的函数。"""
    shared_by_name: dict[str, list[Function]] = {}
    for function in shared:
        shared_by_name.setdefault(function.name, []).append(function)
    resolved: list[tuple[Function, list[Function]]] = []
    result: set[int] = set()
    for funcs in files:
        local: dict[str, list[Function]] = {}
        for function in funcs:
            local.setdefault(function.name, []).append(function)
        for function in funcs:
            if SCREENSHOT_CALL.search(function.body) or SCREENSHOT_REFERENCE_CALL.search(function.body) or calls_screenshot_reference(function.body):
                result.add(id(function))
            targets = [target for name in function.called for target in local.get(name, shared_by_name.get(name, [])) if target is not function]
            resolved.append((function, targets))
    while True:
        more = {id(function) for function, targets in resolved if id(function) not in result and any(id(target) in result for target in targets)}
        if not more:
            return result
        result |= more


def find_violations(root: Path) -> list[str]:
    violations: list[str] = []
    paths_by_scope: dict[tuple[str, str], list[Path]] = {}
    sources = test_sources(root)
    typealiases = kotlin_typealiases(sources)
    for path in sources:
        scope = test_source_scope(root, path)
        if scope is not None:
            paths_by_scope.setdefault(scope, []).append(path)
    fixtures = [functions(normalize_code(p.read_text(encoding="utf-8"), typealiases))
                for scope, paths in paths_by_scope.items() if scope[1] == "testFixtures" for p in paths]
    fixture_shared = [f for funcs in fixtures for f in funcs if not f.private and not f.nested]
    for (_, source_set), paths in sorted(paths_by_scope.items()):
        if source_set == "testFixtures":
            continue
        codes = {path: normalize_code(path.read_text(encoding="utf-8"), typealiases) for path in paths}
        parsed = {path: functions(code) for path, code in codes.items()}
        shared = [f for funcs in parsed.values() for f in funcs if not f.private and not f.nested] + fixture_shared
        # testFixtures 自己的辅助函数也要能解析出「会断言」，跟本源码集的文件一起算
        kotlin_assertion_functions = {id(f) for funcs in parsed.values() for f in funcs}
        all_functions = list(parsed.values()) + fixtures
        asserts = asserting(all_functions, shared, kotlin_assertion_functions)
        screenshots = screenshotting(all_functions, shared)
        for path, funcs in parsed.items():
            rel = path.relative_to(root).as_posix()
            screenshot_failure_self_test = rel == SCREENSHOT_FAILURE_SELF_TEST
            if not screenshot_failure_self_test:
                for write in BITMAP_WRITE.finditer(codes[path]):
                    violations.append(
                        f"{rel}:{codes[path].count(chr(10), 0, write.start()) + 1}: 自己把截图压成文件——"
                        "落盘只经 core:ui testFixtures 的 saveScreenshot"
                    )
            spans = capture_spans(codes[path])
            for f in funcs:
                test = TEST_ANNOTATION.search(f.prefix)
                if not test:
                    continue
                capture = bool(CAPTURE_ANNOTATION.search(f.prefix)) or any(a <= f.start <= b for a, b in spans)
                checks = id(f) in asserts or "expected" in (test.group(1) or "")
                if capture and checks:
                    violations.append(
                        f"{rel}:{f.line}: {f.name} 标了 @VisualCapture 却有断言——有断言就是测试，标了它默认运行会跳过；"
                        "把断言挪进真正的测试，或去掉标记"
                    )
                elif not capture and id(f) in screenshots and not screenshot_failure_self_test:
                    violations.append(
                        f"{rel}:{f.line}: {f.name} 普通 @Test 调用了 saveScreenshot——截图是给人看的产物，"
                        "把落盘移到 @VisualCapture，行为断言留在普通测试"
                    )
                elif not capture and not checks:
                    violations.append(
                        f"{rel}:{f.line}: {f.name} 没有断言——跑一遍不崩不是测试；补上断言，"
                        "截图 / 录屏这类给人看的产物标 @VisualCapture"
                    )
    return violations


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    violations = find_violations(root)
    for v in violations:
        print(v, file=sys.stderr)
    if violations:
        print(f"\n测试断言门禁未通过：{len(violations)} 处（截图与录屏标 @VisualCapture，见 docs/dev/guide.md §4.2）",
              file=sys.stderr)
        return 1
    print("测试断言门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
