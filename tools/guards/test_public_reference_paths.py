#!/usr/bin/env python3
"""公开的贡献说明与源码注释不得要求本机的参考克隆（`.reference/` 下的服务端源码等只在维护者机器上）。"""
import pathlib
import re
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]


def scan_paths(root, paths, check_allowed=False):
    pattern = re.compile(r"(?<![A-Za-z0-9_.])\.reference(?=/|`|$)(?:/[^\s`，）\"']*)?")
    allowed = {
        '.gitignore': {'.reference'},
        'core/foundation/src/testFixtures/kotlin/io/github/touhougo/chogumi/core/foundation/testing/RepoFiles.kt': {'.reference'},
        'core/res/src/test/kotlin/io/github/touhougo/chogumi/core/res/StringsLocaleContractTest.kt': {'.reference/'},
        'tools/guards/test_public_reference_paths.py': {'.reference/'},
    }
    violations = []
    used = set()
    for path in filter(None, paths):
        file = root / path
        if not file.is_file():
            continue
        try:
            content = file.read_text(encoding='utf-8')
        except (UnicodeDecodeError, OSError):
            continue
        for number, line in enumerate(content.splitlines(), 1):
            for match in pattern.finditer(line):
                if match.group() in allowed.get(path, set()):
                    used.add((path, match.group()))
                else:
                    violations.append(f'{path}:{number}: {match.group()}')
    if check_allowed:
        violations.extend(
            f'{path}: 放行的 {token} 已匹配不到，删掉这条（死配置）'
            for path, tokens in allowed.items() for token in sorted(tokens) if (path, token) not in used
        )
    return violations


class PublicReferencePathsTest(unittest.TestCase):
    def test_仓库文件不点名本机服务端克隆(self):
        paths = subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT).decode().split('\0')
        self.assertEqual([], scan_paths(ROOT, paths, check_allowed=True))

    def test_扫描区分成员访问与路径(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            (root / 'example.kt').write_text('node.reference\nnode.reference.foo\n')
            self.assertEqual([], scan_paths(root, ['example.kt']))
            (root / 'example.kt').write_text('`.' + 'reference/secret`\n')
            self.assertTrue(scan_paths(root, ['example.kt']))
            (root / '.gitignore').write_text('.' + 'reference\n')
            self.assertEqual([], scan_paths(root, ['.gitignore']))

    def test_匹配不到的放行条目报成死配置(self):
        with tempfile.TemporaryDirectory() as temp:
            found = scan_paths(pathlib.Path(temp), [], check_allowed=True)
        self.assertIn('.gitignore: 放行的 .reference 已匹配不到，删掉这条（死配置）', found)

    def test_开发须知的公开部分不写维护者路径(self):
        guide = (ROOT / 'docs/dev/guide.md').read_text(encoding='utf-8')
        # 只给维护者的说明单独成节，其余各节都是公开的
        public = re.sub(r'(?ms)^### 4\.1\.1 维护者本机资料\n.*?(?=^### |^## |\Z)', '', guide)
        self.assertIsNone(re.search(r'\.(?:reference|devdoc|agent-skills)/', public))


if __name__ == '__main__':
    unittest.main()
