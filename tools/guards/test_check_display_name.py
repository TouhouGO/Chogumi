"""显示名门禁的自测（tools/ci/check.sh lint 运行）。"""

from pathlib import Path
import tempfile
import unittest

from check_display_name import find_violations, unused_allowances


class CheckDisplayNameTest(unittest.TestCase):

    def violations(self, files: dict[str, str], allowed: set[str] = frozenset()) -> list[str]:
        root = Path(tempfile.mkdtemp())
        for rel, text in files.items():
            path = root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        return find_violations(root, set(allowed))

    def test_手写中文名优先拦下(self):
        found = self.violations({"feature/a/src/main/Page.kt": "val t = subject.nameCn.ifBlank { subject.name }\n"})
        self.assertEqual(1, len(found))
        self.assertIn("feature/a/src/main/Page.kt:1", found[0])
        self.assertIn("displayNameOf", found[0])

    def test_判空串可空与换行的写法同样拦下(self):
        page = (
            "val a = subject.nameCn.ifEmpty { subject.name }\n"
            "val b = subject?.nameCn?.ifBlank { subject.name }\n"
            "val c = nameCN.takeIf { it.isNotBlank() } ?: name\n"
            "val d = episode.nameCN\n    .ifBlank { episode.name }\n"
        )
        found = self.violations({"core/x/src/main/A.kt": page})
        self.assertEqual(["core/x/src/main/A.kt:1", "core/x/src/main/A.kt:2", "core/x/src/main/A.kt:3",
                          "core/x/src/main/A.kt:4"], [v.split(": ")[0] for v in found])

    def test_if分支挑名字拦下(self):
        page = "val t = if (item.nameCn.isNotBlank()) item.nameCn else item.name\n"
        self.assertEqual(1, len(self.violations({"app/src/main/A.kt": page})))

    def test_判空新写法同样拦下(self):
        for page in (
            "val t = if (item.nameCn.isNullOrBlank()) item.name else item.nameCn\n",
            "val t = when { item.nameCn.isBlank() -> item.name\n else -> item.nameCn }\n",
            "val t = (item.nameCn ?: \"\").ifBlank { item.name }\n",
        ):
            self.assertEqual(1, len(self.violations({"app/src/main/A.kt": page})), page)

    def test_常见排版与改写同样拦下(self):
        for page in (
            "val t = if (\n    item.nameCn.isNotBlank()\n) item.nameCn else item.name\n",
            "val t = if (show(item) && item.nameCn.isNotBlank()) item.nameCn else item.name\n",
            "val t = when {\n    item.nameCn.isBlank() -> item.name\n    else -> item.nameCn\n}\n",
            "val t = when (item.nameCn) {\n    \"\" -> item.name\n    else -> item.nameCn\n}\n",
            "val t = item.nameCn.trim().unescapeHtmlEntities().ifBlank { item.name }\n",
            "val t = item.nameCn ?: item.name\n",
            "val a = displayNameOf(\n    name = it.username,\n    nameCn = it.nickname,\n)\n",
            "val a = displayNameOf(it.username.trim(), it.nickname)\n",
            "Text(user.nickname, style = s)\n",
        ):
            with self.subTest(page=page):
                found = self.violations({"feature/a/src/main/A.kt": page})
                self.assertEqual(1, len(found), found)

    def test_条件之外与分支结果里的名字判断不拦(self):
        page = (
            "val t = if (expanded) {\n    Row()\n} else Spacer()\nval hasCn = item.nameCn.isNotBlank()\n"
            "val u = when {\n    loading -> skeleton()\n    else -> show(item.nameCn.isBlank())\n}\n"
            "val v = when (item.type) {\n    1 -> item.name\n    else -> displayNameOf(item)\n}\n"
            "val w = dto.nameCn ?: \"\"\n"
            "Text(displayNameOf(user), style = s)\n"
        )
        self.assertEqual([], self.violations({"feature/a/src/main/A.kt": page}))

    def test_不挑显示名的用法不拦(self):
        page = (
            "val cn = legacy.filter { it.nameCn.isNotBlank() }.associate { it.id to it.nameCn }\n"
            "val hit = it.nameCn.contains(keyword, true) || it.name.contains(keyword, true)\n"
            "val label = relation.cn.ifBlank { relation.desc }\n"
            "val t = displayNameOf(subject)\n"
        )
        self.assertEqual([], self.violations({"core/x/src/main/A.kt": page}))

    def test_手写昵称回退拦下(self):
        page = (
            "val a = user.nickname.ifBlank { user.username }\n"
            "val b = user?.nickname?.unescapeHtmlEntities()?.ifBlank { user.username }\n"
            "val c = comment.creatorNickname.ifEmpty { comment.creatorUsername }\n"
            "val d = if (user.nickname.isBlank()) user.username else user.nickname\n"
        )
        found = self.violations({"core/x/src/main/A.kt": page})
        self.assertEqual(["core/x/src/main/A.kt:1", "core/x/src/main/A.kt:2", "core/x/src/main/A.kt:3",
                          "core/x/src/main/A.kt:4"], [v.split(": ")[0] for v in found])

    def test_昵称当译名传给作品的规则拦下(self):
        page = (
            "val a = displayNameOf(user.username, user.nickname)\n"
            "val b = displayNameOf(name = it.username, nameCn = it.nickname)\n"
        )
        self.assertEqual(2, len(self.violations({"feature/a/src/main/A.kt": page})))

    def test_原始昵称直接当显示文字拦下(self):
        page = (
            "Text(text = item.sender.nickname, style = s)\n"
            "CircleAvatar(url = u, description = comment.creatorNickname)\n"
        )
        self.assertEqual(2, len(self.violations({"feature/a/src/main/A.kt": page})))

    def test_用户规则与数据层原样存昵称不拦(self):
        page = (
            "val n = displayNameOf(user)\n"
            "val m = userDisplayNameOf(comment.creatorUsername, comment.creatorNickname, comment.creatorId)\n"
            "nickname = sender.nickname.unescapeHtmlEntities(),\n"
            "creatorNickname = user?.nickname?.unescapeHtmlEntities().orEmpty(),\n"
        )
        self.assertEqual([], self.violations({"core/data/src/main/A.kt": page}))

    def test_注释与放行文件不拦(self):
        files = {
            "core/x/src/main/A.kt": "// 显示名规则 nameCn.ifBlank { name } 只在 displayNameOf 里\nfun f() = Unit\n",
            "core/model/src/main/DisplayName.kt": "fun displayNameOf(name: String, nameCn: String) = nameCn.ifBlank { name }\n",
        }
        self.assertEqual([], self.violations(files, allowed={"core/model/src/main/DisplayName.kt"}))

    def test_放行文件不在或用不上了报出来(self):
        root = Path(tempfile.mkdtemp())
        for rel, text in {
            "core/model/src/main/DisplayName.kt": "fun displayNameOf(entity: NamedEntity) = entity.label\n",
            "core/model/src/main/UserName.kt": "fun userDisplayNameOf(u: String, nickname: String) = nickname.ifBlank { u }\n",
        }.items():
            (root / rel).parent.mkdir(parents=True, exist_ok=True)
            (root / rel).write_text(text, encoding="utf-8")
        allowed = {"core/model/src/main/DisplayName.kt", "core/model/src/main/UserName.kt", "core/model/src/main/Gone.kt"}
        found = unused_allowances(root, allowed)
        self.assertEqual(["core/model/src/main/DisplayName.kt", "core/model/src/main/Gone.kt"],
                         [v.split(": ")[0] for v in found])
        self.assertTrue(all("放行" in v for v in found), found)

    def test_测试源码不查(self):
        self.assertEqual([], self.violations({"core/x/src/test/A.kt": "val t = s.nameCn.ifBlank { s.name }\n"}))


if __name__ == "__main__":
    unittest.main()
