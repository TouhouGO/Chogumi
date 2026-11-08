"""文字输入框门禁（唯一实现处；tools/ci/check.sh lint 运行，单跑：python3 tools/guards/check_text_fields.py）。

全 App 的文字输入只有两种：core:ui 的 FormTextField（表单里的输入框）和 ContainedSearchField（搜索框）。
页面自己拿官方 OutlinedTextField / TextField / BasicTextField（含 Secure 版与搜索栏的 InputField）拼，就会一页一个外观（方角描边、没有焦点环、行高各异）。
白名单只列「嵌在别的组件里、没有自己容器」的输入（按「文件::函数」列）。
放在 AlertDialog / BasicAlertDialog 里的 FormTextField（及建在它上面的 SecretTextField）必须传 `inDialog = true`：对话框的底色与默认的框底色同一档，不传框就隐形。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

from sources import enclosing_function, kotlin_typealiases, main_sources, normalize_code

HOMES = {"FormTextField.kt", "ContainedSearchField.kt"}

# 嵌在 chip 里、没有自己容器的输入
ALLOWED = {
    ("CollectionReviewSections.kt", "AddTagField"): "标签输入嵌在 chip 里，容器是 chip 本身",
}

# 官方输入框，含搜索栏的输入框（SearchBarDefaults.InputField）
FIELD_CALL = re.compile(
    r"\b(OutlinedTextField|TextField|SecureTextField|BasicTextField|BasicSecureTextField|OutlinedSecureTextField"
    r"|SearchBarDefaults\s*\.\s*InputField)\s*\("
)
DIALOG_CALL = re.compile(r"\b(?:AlertDialog|BasicAlertDialog)\s*\(")
# 带 inDialog 参数的输入框：FormTextField 与建在它上面的 SecretTextField
DIALOG_FIELD_CALL = re.compile(r"\b(?:FormTextField|SecretTextField)\s*\(")


def matching(text: str, start: int, open_ch: str, close_ch: str) -> int:
    """[start] 处的括号配对的位置；文本是 normalize_code 视图，字面量与注释已抹掉。"""
    depth = 0
    for i in range(start, len(text)):
        if text[i] == open_ch:
            depth += 1
        elif text[i] == close_ch:
            depth -= 1
            if depth == 0:
                return i
    return len(text)


def call_extent(text: str, open_paren: int) -> int:
    """调用的参数括号加上紧跟的尾随 lambda（BasicAlertDialog 的内容在这里）的结束位置。"""
    end = matching(text, open_paren, "(", ")")
    rest = text[end + 1:]
    stripped = rest.lstrip()
    if stripped.startswith("{"):
        end = matching(text, end + 1 + len(rest) - len(stripped), "{", "}")
    return end


def dialog_field_violations(root: Path) -> list[str]:
    found: list[str] = []
    paths = main_sources(root)
    typealiases = kotlin_typealiases(paths)
    for path in paths:
        if path.name in HOMES:
            continue
        text = normalize_code(path.read_text(encoding="utf-8"), typealiases)
        for dialog in DIALOG_CALL.finditer(text):
            body = text[dialog.end() - 1 : call_extent(text, dialog.end() - 1) + 1]
            for field in DIALOG_FIELD_CALL.finditer(body):
                args = body[field.end() - 1 : matching(body, field.end() - 1, "(", ")")]
                if not re.search(r"\binDialog\s*=\s*true\b", args):
                    line = text[: dialog.end() - 1 + field.start()].count("\n") + 1
                    found.append(
                        f"{path.relative_to(root).as_posix()}:{line}: 对话框里的输入框没传 inDialog = true——"
                        "对话框底色与框底色同一档，框会隐形（core:ui FormTextField）"
                    )
    return found


def find_violations(root: Path, allowed: dict[tuple[str, str], str] = ALLOWED) -> list[str]:
    violations: list[str] = []
    used: set[tuple[str, str]] = set()
    paths = main_sources(root)
    typealiases = kotlin_typealiases(paths)
    for path in paths:
        if path.name in HOMES:
            continue
        text = normalize_code(path.read_text(encoding="utf-8"), typealiases)
        for m in FIELD_CALL.finditer(text):
            key = (path.name, enclosing_function(text, m.start()))
            if key in allowed:
                used.add(key)
                continue
            line = text[: m.start()].count("\n") + 1
            violations.append(
                f"{path.relative_to(root).as_posix()}:{line}: {key[1]} 直接用 {m.group(1)}——"
                "表单输入改用 FormTextField，搜索框改用 ContainedSearchField（core:ui）"
            )
    violations.extend(dialog_field_violations(root))
    for key in sorted(set(allowed) - used):
        violations.append(f"白名单条目 {key[0]}::{key[1]} 已匹配不到，删掉这条（死配置）")
    return violations


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    violations = find_violations(root)
    for v in violations:
        print(v, file=sys.stderr)
    if violations:
        print(f"\n文字输入框门禁未通过：{len(violations)} 处（见 core:ui FormTextField）", file=sys.stderr)
        return 1
    print("文字输入框门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
