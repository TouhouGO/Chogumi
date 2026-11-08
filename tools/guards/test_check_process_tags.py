"""过程标签门禁的自测：每条规矩各给一个违规样例，确认门禁真的拦得住（tools/ci/check.sh lint 运行）。"""

from pathlib import Path
import tempfile
import unittest

from check_process_tags import find_violations


class CheckProcessTagsTest(unittest.TestCase):

    def violations(self, files: dict[str, str]) -> list[str]:
        root = Path(tempfile.mkdtemp())
        for rel, text in files.items():
            path = root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        return find_violations(root, list(files))

    def test_只写现状与原因的注释放行(self):
        self.assertEqual([], self.violations({"core/a/src/main/A.kt": "// 读对齐线会在快滑时闪退，所以走主内容槽位\n"}))

    def test_教训允许记录改动史而规范正文仍拦下(self):
        history = "旧行为在修复前漏掉回归，修复后补齐；本 PR 记录证据。\n"
        self.assertEqual([], self.violations({"LESSONS.md": history}))
        self.assertTrue(self.violations({"docs/dev/guide.md": history}))

    def test_教训仍然不允许本机临时路径(self):
        found = self.violations({"LESSONS.md": "证据在 /tmp/only-here.log\n"})
        self.assertEqual(1, len(found))
        self.assertIn("本机或本次会话的路径", found[0])

    def test_本机与会话路径拦下(self):
        found = self.violations({
            "docs/dev/guide.md": "本轮通过 python3 -I /tmp/chogumi-fullfix-run.py 执行\n",
            "tools/x/run.sh": "cd /Users/na/Projects/Chogumi\n",
            "core/a/src/main/A.kt": "// 截图在 /mnt/project-files/a.png\n",
        })
        self.assertEqual(3, len(found))

    def test_模拟器里的tmp与门禁自测样例放行(self):
        self.assertEqual([], self.violations({
            "docs/dev/guide.md": "截图存在模拟器的 /data/local/tmp/chogumi-captures\n",
            "tools/guards/test_check_x.py": 'doc = "临时目录 /tmp/x.lock"\n',
        }))

    def test_代码里指向devdoc的路径拦下_说明文档讲这个目录放行(self):
        found = self.violations({
            "core/a/src/main/A.kt": " * 实测见 `.devdoc/audits/2026-09-29-p1-home-probe.md` §7\n",
            "app/proguard-rules.pro": "# 依据 .devdoc/audits/r8.md\n",
        })
        self.assertEqual(2, len(found))
        self.assertTrue(all(".devdoc/" in f for f in found), found)
        self.assertEqual([], self.violations({
            "core/a/src/main/B.kt": " * 2026-09-29 实测：响应 663 KB\n",
            "AGENTS.md": "调查报告不进仓库：`.devdoc/`（gitignore，维护者本机）\n",
            "tools/x/notes.py": "# 报告放在 .devdoc/ 下\n",
        }))

    def test_单号拦下(self):
        found = self.violations({"core/a/src/main/A.kt": "// 不用自定义对齐线（Q19c）\n"})
        self.assertEqual(1, len(found))
        self.assertIn("Q19c", found[0])

    def test_四位数单号同样拦下(self):
        found = self.violations({"core/a/src/main/A.kt": "// 历史编号 Q1000\n"})
        self.assertEqual(1, len(found))
        self.assertIn("Q1000", found[0])

    def test_过程说法拦下(self):
        found = self.violations({
            "core/a/src/main/A.kt": "/** 讨论入口本期不做 */\n",
            "core/a/src/test/B.kt": "fun `权重组照交接文档的四组`() {}\n",
            "docs/dev/guide.md": "第 2 期的词表\n",
        })
        self.assertEqual(3, len(found))

    def test_批次与返工拦下(self):
        found = self.violations({"core/a/src/test/A.kt": "/** 合并展示测试（批次 D2 返工） */\n"})
        self.assertEqual(1, len(found))
        self.assertIn("批次 D", found[0])

    def test_改动史说法拦下_描述行为的不再放行(self):
        found = self.violations({
            "core/a/src/main/A.kt": "// 原实现在这里差了一天\n",
            "core/a/src/test/B.kt": "// 默认排序已改走 v0\n",
            "core/a/src/main/C.kt": "// 到末页后不再请求；分批次写入\n",
        })
        self.assertEqual(2, len(found))
        self.assertTrue(all("改动史" in v for v in found))

    def test_单号式的决策条目与本单拦下(self):
        found = self.violations({
            "core/a/src/main/A.kt": "/** 按条目互斥加锁（Decision F） */\n",
            "core/a/src/main/B.kt": "// 两种口径不一致正是本单要消灭的\n",
        })
        self.assertEqual(2, len(found))

    def test_代码里指向PR的注释拦下_文档讲流程放行_PRNG放行(self):
        found = self.violations({
            "core/a/src/main/A.kt": "// hex 色值保持原样（见 PR 待确认项）\n",
            "app/proguard-rules.pro": "# 规则见 PR\n",
            "AGENTS.md": "say in the PR body which scope ran\n",
            "core/a/src/main/B.kt": "val rng = PRNG(seed)\n",
        })
        self.assertEqual(2, len(found))
        self.assertTrue(all("PR" in v for v in found))

    def test_改动史的新说法拦下(self):
        found = self.violations({
            "core/a/src/test/A.kt": "// 旧行为下永远等不到\n// 曾把 loadEpisodes 写成 filter\n// 修复前必红\n",
            "core/a/src/main/B.kt": "/** v15：评分各档人数 */\n    /**\n     * v14：最近写入时刻\n     */\n",
            "core/a/src/main/C.kt": "// 彻底剔除 floorNumber 字段\n",
        })
        self.assertEqual(6, len(found))

    def test_审阅编号与改走说法拦下_引号里的P1与审阅动效放行(self):
        for line in ("// 审阅 P12 指出的问题\n", "// 审阅修复补的\n", "// P12 里补的\n",
                     "// 原来这条断言是错的\n", "// 改走 v0 后不再需要\n", "// 已随旧入口删除\n"):
            self.assertEqual(1, len(self.violations({"core/a/src/main/A.kt": line})), line)
        self.assertEqual([], self.violations({"core/a/src/main/A.kt": '// 请求体记为 "P1?" + 查询串；拿去审阅动效\n'}))

    def test_井号与HTML注释里的条目号和版本号同样拦下(self):
        for rel, line in (
            ("tools/x/run.py", "# P12 里补的\n"),
            ("tools/x/run.sh", "    # v15：评分各档人数\n"),
            ("docs/dev/guide.md", "<!-- P3 待定 -->\n"),
        ):
            with self.subTest(rel=rel):
                self.assertEqual(1, len(self.violations({rel: line})))
        self.assertEqual([], self.violations({"tools/x/run.py": 'COLOR = "#P1A2B3"  # 色值\n'}))
        self.assertEqual([], self.violations({"docs/dev/guide.md": "## P1 接口的分页\n### v0：公开接口\n"}))

    def test_工作流与_YAML_同样检查_其余后缀不查(self):
        found = self.violations({
            ".github/workflows/ci.yml": "# 本期不跑设备\n",
            ".github/actions/setup/action.yaml": "# 见 /tmp/run.log\n",
            "app/src/main/res/raw/notes.txt": "本期不做\n",
        })
        self.assertEqual(2, len(found), found)

    def test_不讲来历的原有与以前放行(self):
        page = "// 记录失败也不能挡住系统原有的崩溃处理\n// 2015 年及以前\nval v15 = 15\n"
        self.assertEqual([], self.violations({"core/a/src/main/A.kt": page}))

if __name__ == "__main__":
    unittest.main()
