"""公司工具自测：真实名单可解析，人物身份与单用户标签货架按当前接口核对。"""

from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from check_companies import P1, check_company, parse_companies_from_model  # noqa: E402


ITEM = {"name": "京都动画", "tag": "京阿尼", "personId": 2481, "type": 2, "typeName": "ANIME"}
PERSON = {
    "id": 2481, "name": "京都アニメーション", "nameCN": "京都动画", "type": 2,
    "infobox": [{"key": "别名", "values": [{"k": "", "v": "京都动画"}]}],
    "info": "", "career": ["制作"], "summary": "动画公司",
    "images": {"large": "", "medium": "", "small": "", "grid": ""},
    "comment": 0, "collects": 0, "lock": False, "redirect": 0, "nsfw": False, "collectedAt": None,
}
SEARCH = {"data": [], "total": 116}


class FakeFetcher:
    def __init__(self, person=PERSON, search=SEARCH):
        self.person = person
        self.search = search
        self.calls = []

    def get(self, url):
        self.calls.append(("GET", url, None))
        return self.person

    def post(self, url, payload):
        self.calls.append(("POST", url, payload))
        return self.search


class CheckCompaniesTest(unittest.TestCase):
    def test_真实公司模型包含名字标签人物与类型(self):
        try:
            companies = parse_companies_from_model()
        except ValueError as error:
            self.fail(f"当前真实公司模型必须可解析：{error}")
        self.assertEqual(ITEM, companies[0])
        self.assertTrue(any(item["tag"] == "任天堂" and item["type"] == 4 for item in companies))

    def test_注释掉的公司不算进名单(self):
        model = (
            "private val ANIME_COMPANIES = listOf(\n"
            '    ExploreCompany(name = "京都动画", tag = "京阿尼", personId = 2481),\n'
            '    // ExploreCompany(name = "已撤下", tag = "撤下", personId = 1),\n'
            '    /* ExploreCompany(name = "也撤下", tag = "撤下", personId = 2), */\n'
            ")\n\n"
            "fun companiesOf(type: SubjectType) = when (type) {\n    SubjectType.ANIME -> ANIME_COMPANIES\n}\n"
        )
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            (folder / "SubjectType.kt").write_text("enum class SubjectType(val value: Int) { ANIME(2) }\n", encoding="utf-8")
            (folder / "ExploreCompany.kt").write_text(model, encoding="utf-8")
            companies = parse_companies_from_model(str(folder / "ExploreCompany.kt"))
        self.assertEqual([ITEM], companies)

    def test_人物核对后按用户标签查询排名货架(self):
        fetcher = FakeFetcher()
        result = check_company(fetcher, ITEM)
        self.assertTrue(result["passed"], result)
        self.assertEqual(116, result["tag_count"])
        self.assertEqual([
            ("GET", f"{P1}/persons/2481", None),
            ("POST", f"{P1}/search/subjects?limit=1&offset=0", {
                "keyword": "", "sort": "rank", "filter": {"type": [2], "tags": ["京阿尼"], "rank": [">=1"], "nsfw": False},
            }),
        ], fetcher.calls)

    def test_非公司与空标签货架失败(self):
        person = {**PERSON, "type": 1}
        result = check_company(FakeFetcher(person, {"data": [], "total": 0}), ITEM)
        self.assertFalse(result["passed"])
        self.assertFalse(result["is_company"])
        self.assertEqual(0, result["tag_count"])

    def test_人物不存在不查询货架(self):
        fetcher = FakeFetcher(None)
        result = check_company(fetcher, ITEM)
        self.assertFalse(result["passed"])
        self.assertEqual([("GET", f"{P1}/persons/2481", None)], fetcher.calls)


if __name__ == "__main__":
    unittest.main()
