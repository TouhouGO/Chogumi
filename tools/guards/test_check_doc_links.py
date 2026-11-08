"""文档链接门禁的自测（tools/ci/check.sh lint 运行）。"""

from pathlib import Path
import tempfile
import unittest

from check_doc_links import anchor_of, find_violations

ROUTER = "# 路由\n\n| 事 | 读 |\n|---|---|\n| 规则 | [规范](docs/code-standards.md) |\n| 教训 | [LESSONS.md](LESSONS.md) |\n"
STANDARDS = "# 代码规范\n\n## 测试\n\n- **一次性反馈属于事件，不属于状态。** 说明。\n\n| 规则 | 看什么 |\n|---|---|\n| 修在兄弟路径都经过的函数里。 | 列出兄弟 |\n\n## 合并前：`tools/ci/check.sh`，按改动选范围\n"


class CheckDocLinksTest(unittest.TestCase):

    def violations(self, files: dict[str, str]) -> list[str]:
        root = Path(tempfile.mkdtemp())
        for rel, text in files.items():
            path = root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        return find_violations(root, sorted(files))

    def base(self, **extra: str) -> dict[str, str]:
        return {"AGENTS.md": ROUTER, "docs/code-standards.md": STANDARDS, "LESSONS.md": "# 教训\n", **extra}

    def test_齐全的文档放行(self):
        self.assertEqual([], self.violations(self.base()))

    def test_指向不存在文件的链接拦下(self):
        found = self.violations(self.base(**{"LESSONS.md": "见 [流程](docs/dev/workflow.md)。\n"}))
        self.assertEqual(1, len(found))
        self.assertIn("LESSONS.md:1", found[0])
        self.assertIn("docs/dev/workflow.md", found[0])

    def test_相对链接按引用文件所在目录解析(self):
        files = self.base(**{"docs/code-standards.md": STANDARDS + "\n见 [教训](../LESSONS.md) 与 [本文测试](#测试)。\n"})
        self.assertEqual([], self.violations(files))

    def test_锚点要对应标题_含代码与标点的标题按_GitHub_的生成法(self):
        self.assertEqual("合并前toolscichecksh按改动选范围", anchor_of("合并前：`tools/ci/check.sh`，按改动选范围"))
        ok = self.base(**{"LESSONS.md": "见 [合并](docs/code-standards.md#合并前toolscichecksh按改动选范围)。\n"})
        self.assertEqual([], self.violations(ok))
        bad = self.base(**{"LESSONS.md": "见 [合并](docs/code-standards.md#before-merging)。\n"})
        self.assertEqual(1, len(self.violations(bad)))
        self.assertIn("锚点", self.violations(bad)[0])

    def test_网址与代码里的链接不查(self):
        text = "见 [M3](https://m3.material.io/) 与 [信](mailto:a@b.c)。\n\n```\n[坏](docs/no.md)\n```\n\n行内 `[坏](docs/no2.md)` 也不算。\n"
        self.assertEqual([], self.violations(self.base(**{"LESSONS.md": text})))

    def test_docs_下没被路由表链接到的文档拦下(self):
        found = self.violations(self.base(**{"docs/dev/guide.md": "# 须知\n"}))
        self.assertEqual(1, len(found))
        self.assertIn("docs/dev/guide.md", found[0])
        self.assertIn("AGENTS.md", found[0])

    def test_路由表链接带锚点也算路由到(self):
        router = ROUTER + "| 坑 | [须知](docs/dev/guide.md#2-坑) |\n"
        files = self.base(**{"AGENTS.md": router, "docs/dev/guide.md": "# 须知\n\n## 2. 坑\n"})
        self.assertEqual([], self.violations(files))

    def test_章节名引用要能在文档里找到标题或加粗条目或表格首列(self):
        ok = {
            "tools/guards/check_x.py": '"""规矩见 docs/code-standards.md「测试」与 docs/code-standards.md「一次性反馈属于事件」。"""\n',
            "core/a/src/main/A.kt": "// 见 docs/code-standards.md「修在兄弟路径都经过的函数里」\n",
            "tools/ci/check.sh": "# 见 docs/code-standards.md「合并前」\n",
        }
        self.assertEqual([], self.violations(self.base(**ok)))
        bad = {"core/a/src/main/A.kt": "// 见 docs/code-standards.md「Tests must not poll」\n"}
        found = self.violations(self.base(**bad))
        self.assertEqual(1, len(found))
        self.assertIn("A.kt:1", found[0])
        self.assertIn("Tests must not poll", found[0])

    def test_链接后面紧跟的章节名与简称同样检查(self):
        ok = self.base(**{"LESSONS.md": "见 [规范](docs/code-standards.md)「测试」，也就是规范「测试」。\n"})
        self.assertEqual([], self.violations(ok))
        bad = self.base(**{"LESSONS.md": "见 [规范](docs/code-standards.md)「验收」，流程「合并前」。\n"})
        found = self.violations(bad)
        self.assertEqual(2, len(found), found)
        self.assertIn("「验收」", found[0])
        self.assertIn("docs/dev/workflow.md", found[1])

    def test_引用的文档不存在拦下(self):
        found = self.violations(self.base(**{"core/a/src/main/A.kt": "// 见 docs/old.md「测试」\n"}))
        self.assertEqual(1, len(found))
        self.assertIn("docs/old.md", found[0])

    def test_仓库外的文档名不查章节名(self):
        files = self.base(**{
            "core/a/src/main/A.kt": "// 见 tokens.md「Motion springs」与 references/components/input.md「Text fields」\n",
            "core/b/src/main/B.kt": "// 量过：.devdoc/audits/probe.md「结论」\n",
            "docs/design/typography.md": "M3 typography.md「Top app bar title → title」\n",
        })
        files["AGENTS.md"] = ROUTER + "| 字 | [字阶](docs/design/typography.md) |\n"
        self.assertEqual([], self.violations(files))

    def test_引用式链接的定义同样检查(self):
        ok = self.base(**{"LESSONS.md": "见 [规范][std]。\n\n[std]: docs/code-standards.md#测试\n"})
        self.assertEqual([], self.violations(ok))
        bad = self.base(**{"LESSONS.md": "见 [流程][wf]。\n\n[wf]: docs/dev/workflow.md\n"})
        found = self.violations(bad)
        self.assertEqual(1, len(found), found)
        self.assertIn("LESSONS.md:3", found[0])

    def test_行内代码里的章节名引用与构建配置里的引用同样检查(self):
        files = self.base(**{
            "LESSONS.md": "见 `docs/code-standards.md`「不存在的节」。\n",
            "app/proguard-rules.pro": "# 见 docs/code-standards.md「也不存在」\n",
            "app/src/main/res/values/strings.xml": "<!-- 见 docs/code-standards.md「测试」 -->\n",
        })
        found = self.violations(files)
        self.assertEqual(2, len(found), found)
        self.assertTrue(any("LESSONS.md:1" in item for item in found), found)
        self.assertTrue(any("app/proguard-rules.pro:1" in item for item in found), found)

    def test_门禁自测里的样例不查章节名(self):
        files = self.base(**{"tools/guards/test_check_x.py": '"AGENTS.md": "docs/code-standards.md「Time」"\n'})
        self.assertEqual([], self.violations(files))


if __name__ == "__main__":
    unittest.main()
