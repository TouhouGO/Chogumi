"""GitHub Actions 引用门禁（唯一实现处；tools/ci/check.sh lint 运行，单跑：python3 tools/guards/check_action_pins.py）。

外部 Action 换了标签内容就换了，workflow 跑的是别人此刻的代码；checkout 默认把令牌留在工作区，后面的步骤都读得到。

检查项（.github/workflows 下的 workflow，与它们经 `uses: ./…` 实际引用的仓内 action，递归）：
- 外部 `uses` 钉四十位提交 SHA；`docker://` 引用与 docker 型 local action 的 `runs.image` 钉 `@sha256:` digest；
  动态 `uses`、不认识的写法都拒绝。
- `actions/checkout` 在 `with` 里写 `persist-credentials: false`。
- workflow 顶层只有 `permissions: contents: read`；job 只能不写或收紧为只读，只有 release.yml 的 release job 拿 `contents: write`。
- 认不准的 YAML（anchor、alias、tag、重复键、flow 写的 step / job）直接报错，不猜。
"""

from __future__ import annotations

from dataclasses import dataclass, field
import json
from pathlib import Path
import re
import sys

from sources import github_sources, local_action_candidates


FULL_SHA = re.compile(r"[0-9a-fA-F]{40}")
DOCKER_DIGEST = re.compile(r"sha256:[0-9a-f]{64}")
KEY_LINE = re.compile(
    r"^(?P<indent> *)(?P<dash>-(?: +|$))?"
    r"(?P<key>\"(?:\\.|[^\"])*\"|'(?:''|[^'])*'|[A-Za-z0-9_.-]+)[ \t]*:(?P<value>.*)$"
)


@dataclass
class StructureNode:
    line: int
    kind: str
    value: str = ""
    pairs: list[tuple[str, "StructureNode"]] = field(default_factory=list)
    items: list["StructureNode"] = field(default_factory=list)


def strip_yaml_comment(text: str) -> str:
    """去掉引号外的 YAML 注释；井号只有位于行首或空白后才是注释。"""
    quote = ""
    escaped = False
    for index, char in enumerate(text):
        if quote == '"':
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = ""
        elif quote == "'":
            if char == quote:
                if index + 1 < len(text) and text[index + 1] == quote:
                    continue
                quote = ""
        elif char in {'"', "'"}:
            quote = char
        elif char == "#" and (index == 0 or text[index - 1].isspace()):
            return text[:index].rstrip()
    return text.rstrip()


def unquote_scalar(value: str) -> str:
    value = value.strip()
    if len(value) < 2 or value[0] != value[-1] or value[0] not in {'"', "'"}:
        return value
    if value[0] == "'":
        return value[1:-1].replace("''", "'")
    try:
        return json.loads(value)
    except (ValueError, TypeError):
        return value[1:-1]


def quoted_scalar_complete(value: str) -> bool:
    if not value or value[0] not in {'"', "'"}:
        return True
    quote = value[0]
    escaped = False
    index = 1
    while index < len(value):
        char = value[index]
        if quote == '"' and escaped:
            escaped = False
        elif quote == '"' and char == "\\":
            escaped = True
        elif char == quote:
            if quote == "'" and index + 1 < len(value) and value[index + 1] == quote:
                index += 1
            else:
                return not value[index + 1:].strip()
        index += 1
    return False


def indentation(text: str) -> int:
    return len(text) - len(text.lstrip(" "))


def block_scalar(lines: list[str], start: int, parent_indent: int, marker: str) -> tuple[str, int]:
    """读取 |/> 块标量的内容并返回折叠后的值和下一行下标。"""
    end = start
    while end < len(lines):
        line = lines[end]
        if line.strip() and indentation(line) <= parent_indent:
            break
        end += 1
    nonblank = [indentation(line) for line in lines[start:end] if line.strip()]
    content_indent = min(nonblank, default=parent_indent + 1)
    content = [line[content_indent:] if line.strip() else "" for line in lines[start:end]]
    if marker.startswith("|"):
        value = "\n".join(content)
    else:
        paragraphs: list[str] = []
        current: list[str] = []
        for line in content:
            if line:
                current.append(line.strip())
            elif current:
                paragraphs.append(" ".join(current))
                current = []
        if current:
            paragraphs.append(" ".join(current))
        value = "\n".join(paragraphs)
    if "-" not in marker:
        value = value.rstrip("\n") + "\n"
    return value.strip(), end


class StructureParser:
    """读取 GitHub workflow/action 所需的块映射、序列与保守 flow 节点。"""

    def __init__(self, text: str):
        self.lines = text.splitlines()
        self.index = 0
        self.errors: list[tuple[int, str]] = []

    def parse(self) -> StructureNode:
        self._next_significant()
        if self.index >= len(self.lines):
            return StructureNode(1, "scalar", "")
        line = strip_yaml_comment(self.lines[self.index])
        return self._parse_block(indentation(line))

    def _next_significant(self) -> None:
        while self.index < len(self.lines):
            line = strip_yaml_comment(self.lines[self.index])
            if line.strip() and line.strip() not in {"---", "..."}:
                return
            self.index += 1

    @staticmethod
    def _sequence_rest(line: str, indent: int) -> tuple[str, int] | None:
        tail = line[indent:]
        if tail == "-":
            return "", indent + 2
        if tail.startswith("- "):
            after_dash = tail[1:]
            spaces = len(after_dash) - len(after_dash.lstrip())
            return after_dash.lstrip(), indent + 1 + spaces
        return None

    def _parse_block(self, indent: int) -> StructureNode:
        self._next_significant()
        if self.index >= len(self.lines):
            return StructureNode(max(1, len(self.lines)), "scalar", "")
        line = strip_yaml_comment(self.lines[self.index])
        actual_indent = indentation(line)
        if actual_indent < indent:
            return StructureNode(self.index + 1, "scalar", "")
        if self._sequence_rest(line, actual_indent) is not None:
            return self._parse_sequence(actual_indent)
        return self._parse_mapping(actual_indent)

    def _parse_mapping(self, indent: int) -> StructureNode:
        node = StructureNode(self.index + 1, "map")
        self._parse_mapping_tail(node, indent)
        return node

    def _append_pair(self, node: StructureNode, key: str, value: StructureNode, line: int) -> None:
        if any(existing == key for existing, _ in node.pairs):
            self.errors.append((line, f"重复的映射键 {key} 无法可靠复核"))
        node.pairs.append((key, value))

    def _parse_mapping_tail(self, node: StructureNode, indent: int) -> None:
        while True:
            self._next_significant()
            if self.index >= len(self.lines):
                return
            line = strip_yaml_comment(self.lines[self.index])
            current_indent = indentation(line)
            if current_indent < indent or (current_indent == indent and self._sequence_rest(line, current_indent) is not None):
                return
            if current_indent != indent:
                self.errors.append((self.index + 1, "不支持的 YAML 缩进结构"))
                self.index += 1
                continue
            match = KEY_LINE.match(line)
            if not match:
                self.errors.append((self.index + 1, "无法识别的映射键"))
                self.index += 1
                continue
            key = unquote_scalar(match.group("key"))
            expr = match.group("value").strip()
            line_number = self.index + 1
            self.index += 1
            self._append_pair(node, key, self._parse_value(expr, line_number, indent), line_number)

    def _parse_sequence(self, indent: int) -> StructureNode:
        node = StructureNode(self.index + 1, "seq")
        while True:
            self._next_significant()
            if self.index >= len(self.lines):
                return node
            line = strip_yaml_comment(self.lines[self.index])
            if indentation(line) != indent:
                return node
            sequence_parts = self._sequence_rest(line, indent)
            if sequence_parts is None:
                return node
            rest, content_indent = sequence_parts
            line_number = self.index + 1
            self.index += 1
            if not rest:
                self._next_significant()
                if self.index < len(self.lines):
                    child_line = strip_yaml_comment(self.lines[self.index])
                    child_indent = indentation(child_line)
                else:
                    child_indent = indent
                item = self._parse_block(child_indent) if child_indent > indent else StructureNode(line_number, "scalar", "")
            else:
                match = KEY_LINE.match(rest)
                if match is None:
                    item = self._scalar_node(rest, line_number)
                else:
                    item = StructureNode(line_number, "map")
                    key = unquote_scalar(match.group("key"))
                    expr = match.group("value").strip()
                    self._append_pair(item, key, self._parse_value(expr, line_number, content_indent), line_number)
                    self._parse_mapping_tail(item, content_indent)
            node.items.append(item)

    def _parse_value(self, expr: str, line_number: int, parent_indent: int) -> StructureNode:
        if expr.startswith(("&", "*", "!")):
            self.errors.append((line_number, "不支持 YAML alias、anchor 或 tag"))
        if not quoted_scalar_complete(expr):
            parts = [expr]
            while self.index < len(self.lines):
                parts.append(strip_yaml_comment(self.lines[self.index]))
                if quoted_scalar_complete("\n".join(parts)):
                    self.index += 1
                    break
                self.index += 1
            expr = " ".join(part.strip() for part in parts)
        if expr.startswith(("|", ">")):
            value, self.index = block_scalar(self.lines, self.index, parent_indent, expr)
            return StructureNode(line_number, "scalar", value)
        if expr:
            return self._scalar_node(expr, line_number)
        self._next_significant()
        if self.index < len(self.lines):
            line = strip_yaml_comment(self.lines[self.index])
            child_indent = indentation(line)
            if child_indent > parent_indent:
                return self._parse_block(child_indent)
        return StructureNode(line_number, "scalar", "")

    @staticmethod
    def _scalar_node(expr: str, line_number: int) -> StructureNode:
        if expr == "[]":
            return StructureNode(line_number, "seq")
        if expr == "{}":
            return StructureNode(line_number, "map")
        if expr.startswith(("{", "[")):
            return StructureNode(line_number, "flow", expr)
        return StructureNode(line_number, "scalar", unquote_scalar(expr))


def map_values(node: StructureNode | None, key: str) -> list[StructureNode]:
    if node is None or node.kind != "map":
        return []
    return [value for name, value in node.pairs if name == key]


def map_value(node: StructureNode | None, key: str) -> StructureNode | None:
    values = map_values(node, key)
    return values[0] if len(values) == 1 else None


def pin_problem(value: str) -> str:
    if value.startswith(("./", "$/")):
        return ""
    if value.startswith("$"):
        return "动态 uses 无法在静态 workflow 中复核"
    if value.startswith("docker://"):
        digest = value.rsplit("@", 1)[-1] if "@" in value else ""
        if not DOCKER_DIGEST.fullmatch(digest):
            return "Docker Action 必须使用 @sha256:<64 位小写十六进制> digest"
        return ""
    if "://" in value:
        return "uses 使用了不受支持的协议或写法"
    if "@" not in value:
        return "外部 uses 缺少 @<四十位提交 SHA>"
    target, ref = value.rsplit("@", 1)
    if "/" not in target or target.startswith("/"):
        return "外部 uses 的目标不是 owner/repository 路径"
    if not FULL_SHA.fullmatch(ref):
        if "/.github/workflows/" in target:
            return "外部 reusable workflow 必须钉四十位提交 SHA：" + value
        return "外部 uses 必须钉四十位提交 SHA：" + value
    return ""


def checkout_without_false(step: StructureNode) -> bool:
    uses = map_value(step, "uses")
    if uses is None or uses.kind != "scalar":
        return False
    target = uses.value.rsplit("@", 1)[0].split("/")
    if len(target) < 2 or "/".join(target[:2]).lower() != "actions/checkout":
        return False
    with_nodes = map_values(step, "with")
    if len(with_nodes) != 1 or with_nodes[0].kind != "map":
        return True
    credentials = map_values(with_nodes[0], "persist-credentials")
    return len(credentials) != 1 or credentials[0].kind != "scalar" or credentials[0].value.strip().lower() != "false"


def permission_violations(relative: str, workflow: StructureNode) -> list[str]:
    violations: list[str] = []
    top_nodes = map_values(workflow, "permissions")
    if len(top_nodes) != 1:
        violations.append(f"{relative}:1: workflow 顶层必须声明一次 permissions: contents: read")
    elif top_nodes[0].kind != "map" or [(key, value.kind, value.value) for key, value in top_nodes[0].pairs] != [
        ("contents", "scalar", "read")
    ]:
        violations.append(f"{relative}:{top_nodes[0].line}: workflow 顶层权限只能是 contents: read")

    jobs_nodes = map_values(workflow, "jobs")
    if len(jobs_nodes) != 1:
        return violations + [f"{relative}:1: workflow 必须声明一次 jobs 映射"]
    jobs = jobs_nodes[0]
    if jobs.kind != "map":
        return violations + [f"{relative}:{jobs.line}: jobs 必须使用块映射"]

    release_write = False
    release_seen = False
    for job_name, job in jobs.pairs:
        if job.kind != "map":
            violations.append(f"{relative}:{job.line}: job {job_name} 必须使用块结构")
            continue
        allowed_release = relative == ".github/workflows/release.yml" and job_name == "release"
        release_seen = release_seen or allowed_release
        permissions = map_values(job, "permissions")
        if not permissions:
            continue
        for permission in permissions:
            if permission.kind == "flow":
                violations.append(f"{relative}:{permission.line}: job {job_name} 的 permissions 必须使用块结构")
                continue
            if permission.kind == "map":
                declared = [(key, value.kind, value.value) for key, value in permission.pairs]
            elif permission.kind == "scalar" and not permission.value:
                declared = []
            else:
                violations.append(f"{relative}:{permission.line}: job {job_name} 的 permissions 只能是 contents: read|write")
                continue
            if allowed_release and declared == [("contents", "scalar", "write")]:
                release_write = True
            elif allowed_release:
                violations.append(f"{relative}:{permission.line}: release job 只能获得 contents: write")
            elif not declared or declared == [("contents", "scalar", "read")]:
                continue
            elif declared == [("contents", "scalar", "write")]:
                violations.append(f"{relative}:{permission.line}: 只有 release job 可以获得 contents: write")
            else:
                violations.append(f"{relative}:{permission.line}: job {job_name} 的 permissions 只能收紧为只读")
    if relative == ".github/workflows/release.yml" and not release_seen:
        violations.append(f"{relative}:1: 缺少 release job")
    elif relative == ".github/workflows/release.yml" and not release_write:
        violations.append(f"{relative}:1: release job 必须获得 contents: write 并保留其他权限为空")
    return violations


def check_reference(relative: str, node: StructureNode, root: Path, seen_actions: set[Path], stack: set[Path]) -> list[str]:
    if node.kind != "scalar":
        return [f"{relative}:{node.line}: uses 必须使用标量引用"]
    value = node.value.strip()
    violations = []
    problem = pin_problem(value)
    if problem:
        violations.append(f"{relative}:{node.line}: {problem}")
    if value.startswith(("./", "$/")):
        violations.extend(check_local_action(relative, node.line, value, root, seen_actions, stack))
    return violations


def check_local_action(
    relative: str,
    line: int,
    reference: str,
    root: Path,
    seen_actions: set[Path],
    stack: set[Path],
) -> list[str]:
    try:
        candidates = local_action_candidates(root, reference)
    except ValueError as error:
        return [f"{relative}:{line}: {error}"]
    existing = [path.resolve() for path in candidates if path.is_file()]
    if len(existing) != 1:
        detail = "缺少 action.yml|action.yaml" if not existing else "同时存在 action.yml 与 action.yaml"
        return [f"{relative}:{line}: local action {reference} {detail}"]
    action_path = existing[0]
    if action_path in stack:
        return [f"{relative}:{line}: local action 递归引用自身：{reference}"]
    if action_path in seen_actions:
        return []
    seen_actions.add(action_path)
    action_relative = action_path.relative_to(root.resolve()).as_posix()
    parser = StructureParser(action_path.read_text(encoding="utf-8"))
    action = parser.parse()
    violations = [f"{action_relative}:{error_line}: {message}" for error_line, message in parser.errors]
    if action.kind != "map":
        return violations + [f"{action_relative}:{action.line}: action metadata 必须使用块映射"]
    runs = map_value(action, "runs")
    if runs is None or runs.kind != "map":
        return violations + [f"{action_relative}:{action.line}: local action 缺少 runs 块映射"]
    image = map_value(runs, "image")
    if image is not None and image.kind == "scalar" and image.value.startswith("docker://"):
        problem = pin_problem(image.value.strip())
        if problem:
            violations.append(f"{action_relative}:{image.line}: {problem}")
    steps = map_value(runs, "steps")
    if steps is not None:
        violations.extend(check_steps(action_relative, steps, root, seen_actions, stack | {action_path}))
    return violations


def check_step(
    relative: str,
    step: StructureNode,
    root: Path,
    seen_actions: set[Path],
    stack: set[Path],
) -> list[str]:
    violations: list[str] = []
    for uses in map_values(step, "uses"):
        violations.extend(check_reference(relative, uses, root, seen_actions, stack))
    if checkout_without_false(step):
        violations.append(f"{relative}:{step.line}: actions/checkout 必须在 with 中设置 persist-credentials: false")
    return violations


def check_steps(
    relative: str,
    steps: StructureNode,
    root: Path,
    seen_actions: set[Path],
    stack: set[Path],
) -> list[str]:
    if steps.kind != "seq":
        return [f"{relative}:{steps.line}: steps 必须使用块序列"]
    violations: list[str] = []
    for step in steps.items:
        if step.kind != "map":
            violations.append(f"{relative}:{step.line}: step 必须使用块结构")
            continue
        violations.extend(check_step(relative, step, root, seen_actions, stack))
    return violations


def check_workflow(relative: str, workflow: StructureNode, root: Path, seen_actions: set[Path]) -> list[str]:
    violations = permission_violations(relative, workflow)
    jobs = map_value(workflow, "jobs")
    if jobs is None or jobs.kind != "map":
        return violations
    for _, job in jobs.pairs:
        if job.kind != "map":
            continue
        for uses in map_values(job, "uses"):
            # reusable workflow 的 local 路径指向 workflow，不按 action metadata 解析。
            if uses.kind == "scalar" and uses.value.startswith(("./", "$/")):
                continue
            violations.extend(check_reference(relative, uses, root, seen_actions, set()))
        steps = map_value(job, "steps")
        if steps is not None:
            violations.extend(check_steps(relative, steps, root, seen_actions, set()))
    return violations


def find_violations(root: Path) -> list[str]:
    violations: list[str] = []
    seen_actions: set[Path] = set()
    for path in github_sources(root):
        relative = path.relative_to(root).as_posix()
        parser = StructureParser(path.read_text(encoding="utf-8"))
        workflow = parser.parse()
        violations.extend(f"{relative}:{line}: {message}" for line, message in parser.errors)
        if workflow.kind != "map":
            violations.append(f"{relative}:{workflow.line}: workflow 必须使用块映射")
            continue
        violations.extend(check_workflow(relative, workflow, root, seen_actions))
    return sorted(set(violations))


def main(root: Path) -> int:
    violations = find_violations(root)
    for violation in violations:
        print(violation, file=sys.stderr)
    return 1 if violations else 0


if __name__ == "__main__":
    raise SystemExit(main(Path(__file__).resolve().parents[2]))
