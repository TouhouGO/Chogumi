"""tools 下的 Python 在 3.9 上能跑的自测（tools/ci/check.sh lint 运行）。

Mac 上的 python3 是 3.9：比它新的写法会让门禁或自测在加载时就失败，整个 lint 入口跟着断，而云端的新版 Python 上照样
是绿的。这里查两种最常见的：3.9 不认的语法（`match` 等，按 3.9 语法解析），以及没有 `from __future__ import annotations`
时注解里的 `X | Y`（3.10 起才能在运行时求值，函数定义时就抛 TypeError）。
"""

from __future__ import annotations

import ast
from pathlib import Path
import subprocess
import unittest

REPO = Path(__file__).resolve().parents[2]


def tracked_python(root: Path) -> list[str]:
    out = subprocess.run(["git", "ls-files", "*.py"], cwd=root, capture_output=True, text=True, check=True).stdout
    return out.split()


def has_future_annotations(tree: ast.Module) -> bool:
    return any(
        isinstance(node, ast.ImportFrom) and node.module == "__future__" and any(a.name == "annotations" for a in node.names)
        for node in tree.body
    )


def annotations_of(tree: ast.Module) -> list[ast.expr]:
    found: list[ast.expr] = []
    for node in ast.walk(tree):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            a = node.args
            params = [*a.posonlyargs, *a.args, *a.kwonlyargs, *(p for p in (a.vararg, a.kwarg) if p is not None)]
            found.extend(p.annotation for p in params if p.annotation is not None)
            if node.returns is not None:
                found.append(node.returns)
        elif isinstance(node, ast.AnnAssign):
            found.append(node.annotation)
    return found


def problems_of(rel: str, source: str) -> list[str]:
    try:
        tree = ast.parse(source, filename=rel, feature_version=(3, 9))
    except SyntaxError as e:
        return [f"{rel}:{e.lineno}: Python 3.9 不认的语法（{e.msg}）"]
    if has_future_annotations(tree):
        return []
    return [
        f"{rel}:{node.lineno}: 注解里的 X | Y 在 3.9 上运行时就抛错——文件开头加 from __future__ import annotations"
        for annotation in annotations_of(tree)
        for node in ast.walk(annotation)
        if isinstance(node, ast.BinOp) and isinstance(node.op, ast.BitOr)
    ]


class Python39Test(unittest.TestCase):

    def test_仓库里的Python都能在3_9上加载(self):
        problems = [p for rel in tracked_python(REPO) for p in problems_of(rel, (REPO / rel).read_text(encoding="utf-8"))]
        self.assertEqual([], problems)

    def test_注解里的竖线没有future导入时拦下(self):
        self.assertEqual(1, len(problems_of("a.py", "def f(x: int | None) -> None: ...\n")))
        self.assertEqual([], problems_of("a.py", "from __future__ import annotations\ndef f(x: int | None) -> None: ...\n"))

    def test_比3_9新的语法拦下(self):
        self.assertEqual(1, len(problems_of("a.py", "match x:\n    case 1:\n        pass\n")))

    def test_表达式里的竖线放行(self):
        self.assertEqual([], problems_of("a.py", "flags = 1 | 2\n"))


if __name__ == "__main__":
    unittest.main()
