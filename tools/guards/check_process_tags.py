"""过程标签门禁（唯一实现处；tools/ci/check.sh lint 运行，单跑：python3 tools/guards/check_process_tags.py）。

注释、测试名、文档只写「现在是什么、为什么」。开发过程里的编号与说法（第几单、第几期、这个 PR、交接文档、
改版前……）离开当时的上下文就没人看得懂，还会把人引去找不存在的东西；讲「以前怎样、后来改成怎样」的改动史同理，
那是提交记录的事。

检查项（仓库里被跟踪的源码、构建脚本、工作流、工具与说明文档，后缀见 SUFFIXES）：
1. 单号：独立的 `Q` + 数字（可带一个小写字母，如 Q19c）。
2. 过程说法：本期、第 N 期、批次 X、交接文档、本 PR、本单、派单、指挥官、执行者、返工、K 报告、改前 / 改版前、
   vN 定稿、审阅修复 / 审阅加编号 / 审阅报告、清单、结论、发现、注释里（`//`、`/*`、`#`、`<!--`）的 P + 数字（审阅清单的条目号）、Decision X（某份决策清单里的条目号）。代码与构建脚本（.kt / .kts / .pro / .xml）里独立的「PR」也算：
   注释指向某个 PR 的正文，读代码的人看不到；说明文档讲合并流程时会正常提到 PR，不按这条查。
3. 改动史的固定说法：原实现、旧实现、旧写法、旧行为、已改走、不再保留、欠账、曾把、曾经、原来是、修复前 / 修复后、
   彻底剔除、原来这条、改走……后 / 时、已随……删除，以及注释开头的库版本号（`/** v15：…`、`# v15：…`）。只拦这几个不会有别的意思的词；「不再请求」这类描述行为的
   「不再」、「系统原有的处理」这类不讲来历的「原有」不拦，改动史的其它写法靠审阅。
4. 某一台机器、某一次会话才有的路径：`/tmp/…`、`/Users/<名字>/…`、`/home/user/…`、`/root/…`、`/mnt/project-files`、
   `/mnt/user-data`。临时脚本和日志随会话消失，别人的机器上没有这个目录，写进文档、注释或脚本只会把人引去找不存在的文件。
   模拟器里的 `/data/local/tmp` 不算。门禁自测里故意写的违规样例（tools/guards/test_*.py）不查这一项。
5. 代码与构建脚本（.kt / .kts / .pro / .xml）里指向 `.devdoc/` 的路径：那是维护者本机、不入库的目录，读代码的人打不开；
   实测写日期与结论（「2026-09-29 实测」），服务端事实写进 docs/dev/guide.md §1。说明文档讲这个目录本身时不按这条查。

调查报告、历史原型这类过程记录不入库（放在 .gitignore 的 .devdoc/）。LESSONS.md 按规范记录错误来历，
允许过程说法与改动史，但仍不允许本机路径与无上下文单号；其余文件没有此例外。
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

SUFFIXES = {".kt", ".kts", ".py", ".mjs", ".js", ".md", ".xml", ".toml", ".properties", ".sh", ".pro", ".yml", ".yaml"}
CODE_SUFFIXES = {".kt", ".kts", ".pro", ".xml"}

# 一行里注释开始的位置：Kotlin 的 // 与 /* 、KDoc 续行的 *、脚本与 YAML 的 #（行首或空白后）、HTML 注释
# Markdown 行首的 # 是标题不是注释，查之前先遮掉（MD_HEADING）。
COMMENT_START = r"(?:/\*\*?|//|^\s*\*|(?:^|(?<=\s))#|<!--)"
MD_HEADING = re.compile(r"^#{1,6}(?=\s)")
Q_LABEL = re.compile(r"(?<![A-Za-z0-9_])Q[0-9]+[a-z]?(?![A-Za-z0-9_])")
PROCESS_WORDS = re.compile(
    r"本期|第 ?[0-9一二三四五六七八九十]+ ?期|批次 ?[A-Z]|交接文档|本 ?PR|本单|派单|指挥官|执行者|返工|K ?报告|改版前|改前"
    r"|v[0-9] ?定稿|Decision [A-Z](?![A-Za-z])|审阅修复|审阅 ?[A-Z]?[0-9]+|审阅(?:报告|清单|结论|发现)"
    r"|" + COMMENT_START + r"[^\n]*(?<![A-Za-z0-9_\"#])P[0-9]{1,2}(?![A-Za-z0-9_?])"
)
CODE_PR = re.compile(r"(?<![A-Za-z0-9_])PR(?![A-Za-z0-9_])")
HISTORY_WORDS = re.compile(
    r"原实现|旧实现|旧写法|旧行为|已改走|不再保留|欠账|曾把|曾经|原来是|修复前|修复后|彻底剔除|原来这条|改走.{0,12}[后时]|已随.{0,20}删除"
    r"|" + COMMENT_START + r"\s*v[0-9]+[：:]"
)
DEVDOC_PATH = re.compile(r"(?<![\w-])\.devdoc/")
MACHINE_PATH = re.compile(r"(?<![\w./~-])(?:/tmp/|/Users/\w|/home/user/|/root/|/mnt/project-files|/mnt/user-data)")
GUARD_SELF_TESTS = "tools/guards/test_"
SELF = "tools/guards/check_process_tags.py"
SELF_TEST = "tools/guards/test_check_process_tags.py"
LESSONS = "LESSONS.md"


def tracked_files(root: Path) -> list[str]:
    return subprocess.run(["git", "ls-files"], cwd=root, capture_output=True, text=True, check=True).stdout.splitlines()


def find_violations(root: Path, files: list[str]) -> list[str]:
    violations: list[str] = []
    for rel in files:
        if rel in (SELF, SELF_TEST) or Path(rel).suffix not in SUFFIXES:
            continue
        path = root / rel
        if not path.is_file():
            continue
        for i, line in enumerate(path.read_text(encoding="utf-8").splitlines()):
            if rel.endswith(".md"):
                line = MD_HEADING.sub(lambda heading: " " * len(heading.group()), line)
            words = None if rel == LESSONS else PROCESS_WORDS.search(line) or (Path(rel).suffix in CODE_SUFFIXES and CODE_PR.search(line))
            if words:
                violations.append(f"{rel}:{i + 1}: 过程说法「{words.group(0)}」——只写现在是什么、为什么")
            history = None if rel == LESSONS else HISTORY_WORDS.search(line)
            if history:
                violations.append(f"{rel}:{i + 1}: 改动史「{history.group(0)}」——只写现在是什么、为什么")
            machine = None if rel.startswith(GUARD_SELF_TESTS) else MACHINE_PATH.search(line)
            if machine:
                violations.append(f"{rel}:{i + 1}: 本机或本次会话的路径「{machine.group(0)}」——别人那里没有，写仓库里的路径或命令")
            devdoc = DEVDOC_PATH.search(line) if Path(rel).suffix in CODE_SUFFIXES else None
            if devdoc:
                violations.append(f"{rel}:{i + 1}: 指向仓库外的 .devdoc/——写实测日期与结论，服务端事实写进 docs/dev/guide.md §1")
            label = Q_LABEL.search(line)
            if label:
                violations.append(f"{rel}:{i + 1}: 单号「{label.group(0)}」——去掉编号，只留原因")
    return violations


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    violations = find_violations(root, tracked_files(root))
    for v in violations:
        print(v, file=sys.stderr)
    if violations:
        print(f"\n过程标签门禁未通过：{len(violations)} 处", file=sys.stderr)
        return 1
    print("过程标签门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
