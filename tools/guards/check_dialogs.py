"""对话框门禁（唯一实现处；tools/ci/check.sh lint 运行，单跑：python3 tools/guards/check_dialogs.py）。

问一句「要不要做」的确认框只有一个：core:ui 的 ConfirmDialog（右边确认、左边「取消」，收不回来的操作确认按钮用 error 色）。
各页自己拼 AlertDialog，确认色、「取消」的字、先收起还是先执行就各写各的。
带输入框或其他内容的对话框不是确认框，列在 ALLOWED 里，每条写明理由。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

from sources import enclosing_function, kotlin_typealiases, main_sources, normalize_code

HOMES = {"ConfirmDialog.kt"}

ALLOWED: dict[tuple[str, str], str] = {
    ("ThemeColorSettings.kt", "SeedGrid"): "自定义主题色：对话框里是十六进制色值的输入框",
    ("LoginScreen.kt", "ManualTokenDialog"): "手动填令牌：对话框里是令牌输入框",
    ("SearchContent.kt", "PresetRenameDialog"): "给搜索组合改名：对话框里是名称输入框",
    ("CollectionProgressSection.kt", "ProgressInputDialog"): "直接输入进度：对话框里是数字输入框",
    ("ComposerSheet.kt", "InsertDialog"): "编辑器插入链接 / 图片：对话框里是地址与文字输入框",
    ("TurnstileDialog.kt", "TurnstileDialog"): "发帖人机验证：对话框里是验证网页",
    ("AppUpdateDialog.kt", "AppUpdateDialog"): "更新提示：对话框里是更新内容、下载进度，三个选择",
}

CALL = re.compile(r"(?<![\w.])(?:[a-z]\w*\.)*(AlertDialog|BasicAlertDialog)\s*\(")


def find_violations(root: Path, allowed: dict[tuple[str, str], str] = ALLOWED) -> list[str]:
    violations: list[str] = []
    used: set[tuple[str, str]] = set()
    paths = main_sources(root)
    typealiases = kotlin_typealiases(paths)
    for path in paths:
        if path.name in HOMES:
            continue
        text = normalize_code(path.read_text(encoding="utf-8"), typealiases)
        for m in CALL.finditer(text):
            key = (path.name, enclosing_function(text, m.start()))
            if key in allowed:
                used.add(key)
                continue
            line = text[: m.start()].count("\n") + 1
            violations.append(
                f"{path.relative_to(root).as_posix()}:{line}: {key[1]} 自己拼了 {m.group(1)}——"
                "确认框改用 core:ui 的 ConfirmDialog；带输入框的对话框写进 check_dialogs.py 的 ALLOWED 并写明理由"
            )
    for key in sorted(set(allowed) - used):
        violations.append(f"白名单条目 {key[0]}::{key[1]} 已匹配不到，删掉这条（死配置）")
    return violations


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    violations = find_violations(root)
    for v in violations:
        print(v, file=sys.stderr)
    if violations:
        print(f"\n对话框门禁未通过：{len(violations)} 处（见 core:ui ConfirmDialog）", file=sys.stderr)
        return 1
    print("对话框门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
