#!/usr/bin/env python3
"""公司名单核对脚本（只读）。

读取 core/model 里的公司名单（单一真源），逐条检查：
1. 人物 ID 存在，名字和显示名对得上；
2. 是公司（v0 type = 2），不是个人；
3. 按当前类型、单用户标签与排名条件查询货架，记录标签作品数，空货架视为核对失败。

输出表格；若有任何一条不通过则以非零状态码退出。

用法：
    python3 tools/check_companies.py
"""

import argparse
import hashlib
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

P1 = "https://next.bgm.tv/p1"
UA = "TouhouGO/Chogumi (company-probe)"

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, ".."))
MODEL_FILE = os.path.join(
    REPO_ROOT,
    "core/model/src/main/kotlin/io/github/touhougo/chogumi/core/model/ExploreCompany.kt",
)
CACHE_DIR = os.path.join(SCRIPT_DIR, ".cache_companies")

# Kotlin 的注释由门禁共用的词法器去掉：注释掉的公司不算进名单
sys.path.insert(0, os.path.join(SCRIPT_DIR, "guards"))
from sources import strip_comments  # noqa: E402

class Fetcher:
    """限速 + 429 退避 + 网络偶发中断重试的 HTTP 客户端，带磁盘缓存。"""

    def __init__(self, cache_dir, interval=0.5):
        self.cache_dir = cache_dir
        self.interval = interval
        self.last = 0.0
        os.makedirs(cache_dir, exist_ok=True)

    def _cache_path(self, url):
        name = urllib.parse.quote(url, safe="").replace("%", "_")
        if len(name) > 150:
            name = name[:100] + "_" + hashlib.md5(name.encode()).hexdigest()
        return os.path.join(self.cache_dir, name + ".json")

    def get(self, url):
        return self._request(url)

    def post(self, url, payload):
        return self._request(url, payload)

    def _request(self, url, payload=None):
        body = json.dumps(payload, ensure_ascii=False, sort_keys=True).encode("utf-8") if payload is not None else None
        cache_key = url if body is None else url + "#" + hashlib.sha256(body).hexdigest()
        path = self._cache_path(cache_key)
        if os.path.exists(path):
            with open(path, encoding="utf-8") as fh:
                return json.load(fh)

        for attempt in range(6):
            wait = self.interval - (time.time() - self.last)
            if wait > 0:
                time.sleep(wait)
            self.last = time.time()

            req = urllib.request.Request(url, data=body)
            req.add_header("User-Agent", UA)
            if body is not None:
                req.add_header("Content-Type", "application/json")
            try:
                with urllib.request.urlopen(req, timeout=30) as resp:
                    raw = resp.read()
                data = json.loads(raw)
                with open(path, "w", encoding="utf-8") as fh:
                    json.dump(data, fh, ensure_ascii=False)
                return data
            except urllib.error.HTTPError as err:
                if err.code == 429:
                    back = 2.0 * (attempt + 1)
                    time.sleep(back)
                    continue
                if err.code == 404:
                    return None
                raise
            except (urllib.error.URLError, TimeoutError, ConnectionError, OSError):
                back = 1.0 * (attempt + 1)
                time.sleep(back)
                continue
        raise RuntimeError("连续失败，无法获取: " + url)


def parse_companies_from_model(model_file=MODEL_FILE):
    """从 core/model 的 ExploreCompany.kt 解析公司列表（单一真源）；同目录的 SubjectType.kt 给类型编号，注释不算。"""
    if not os.path.exists(model_file):
        raise FileNotFoundError(f"Model file not found: {model_file}")

    with open(model_file, "r", encoding="utf-8") as fh:
        content = strip_comments(fh.read())

    type_file = os.path.join(os.path.dirname(model_file), "SubjectType.kt")
    with open(type_file, encoding="utf-8") as fh:
        types = {name: int(value) for name, value in re.findall(r"\b([A-Z]+)\((\d+)\)", strip_comments(fh.read()))}
    bindings = re.findall(r"SubjectType\.(\w+)\s*->\s*(\w+_COMPANIES)", content)
    lists = dict(re.findall(r"val\s+(\w+_COMPANIES)\s*=\s*listOf\((.*?)\n\)", content, re.S))
    company_pattern = re.compile(
        r'ExploreCompany\(\s*name\s*=\s*("(?:\\.|[^"\\])*"),\s*tag\s*=\s*("(?:\\.|[^"\\])*"),\s*personId\s*=\s*(\d+)\s*,?\s*\)'
    )
    companies = []
    for type_name, list_name in bindings:
        for name, tag, pid in company_pattern.findall(lists[list_name]):
            companies.append({
                "name": json.loads(name), "tag": json.loads(tag), "personId": int(pid),
                "type": types[type_name], "typeName": type_name,
            })
    if not companies:
        raise ValueError(f"未能从 {model_file} 解析出任何公司，请检查解析规则是否与源码形状一致")
    return companies


def check_company(fetcher, item):
    """核对单家公司，返回详细字典。"""
    pid = item["personId"]
    name = item["name"]
    type_id = item["type"]
    tag = item["tag"]

    # 1. 查人物详情（p1 与 v0 数据同构，p1 带详细 infobox 结构）
    person_url = f"{P1}/persons/{pid}"
    person = fetcher.get(person_url)

    if not person:
        return {
            "item": item,
            "exists": False,
            "name_match": False,
            "is_company": False,
            "tag_count": 0,
            "passed": False,
            "reason": "人物 ID 不存在 (404)",
        }

    # 收集全部名字别名
    names_collected = set()
    for f in ("name", "nameCN"):
        val = person.get(f)
        if val:
            names_collected.add(val.strip().lower())

    for box in person.get("infobox") or []:
        for val_item in box.get("values") or []:
            v = val_item.get("v")
            if v:
                names_collected.add(v.strip().lower())
            k = val_item.get("k")
            if k:
                names_collected.add(k.strip().lower())

    target_clean = name.strip().lower()
    # 名字匹配：目标名等于某个别名，或是某个名字的子串/包含别名
    name_matched = any(
        target_clean == n or target_clean in n or n in target_clean
        for n in names_collected
    )

    # 2. 检查是否为公司（type = 2）
    raw_type = person.get("type", 0)
    is_company = (raw_type == 2)

    # 单用户标签与排名口径：与无时间条件的公司货架使用同一个 p1 搜索接口，匿名显式排除 NSFW。
    subjects = fetcher.post(f"{P1}/search/subjects?limit=1&offset=0", {
        "keyword": "", "sort": "rank",
        "filter": {"type": [type_id], "tags": [tag], "rank": [">=1"], "nsfw": False},
    })
    tag_count = subjects["total"] if subjects else 0
    has_tag_works = tag_count > 0

    reasons = []
    if not name_matched:
        reasons.append(f"名字对不上 (返回名: {person.get('name')}/{person.get('nameCN')})")
    if not is_company:
        type_desc = {1: "个人", 2: "公司", 3: "组合"}.get(raw_type, str(raw_type))
        reasons.append(f"条目类型为 {raw_type} ({type_desc})，非公司")
    if not has_tag_works:
        reasons.append(f"用户标签 {tag} 的排名货架为空")

    passed = name_matched and is_company and has_tag_works
    return {
        "item": item,
        "exists": True,
        "raw_name": person.get("name", ""),
        "raw_name_cn": person.get("nameCN", ""),
        "raw_type": raw_type,
        "name_match": name_matched,
        "is_company": is_company,
        "tag_count": tag_count,
        "passed": passed,
        "reason": "；".join(reasons) if reasons else "通过",
    }


def render_markdown_table(results):
    lines = [
        "| 序号 | 分类 | 公司名 | 用户标签 | 人物 ID | 真实条目名 / 中文名 | 名字匹配 | 条目类型 (2=公司) | 标签排名作品数 | 判定 |",
        "| :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |",
    ]
    for idx, r in enumerate(results, 1):
        item = r["item"]
        cat = item["typeName"]
        name = item["name"]
        pid = item["personId"]
        r_name = f"{r.get('raw_name', '')} / {r.get('raw_name_cn', '')}" if r["exists"] else "不存在"
        match_str = "通过" if r["name_match"] else "不通过"
        type_str = "2（公司）" if r["is_company"] else str(r.get("raw_type", "?"))
        cnt = r["tag_count"]
        cnt_str = f"{cnt} 部"
        verdict = "**通过**" if r["passed"] else f"**不通过** ({r['reason']})"
        lines.append(
            f"| {idx} | {cat} | {name} | {item['tag']} | {pid} | {r_name} | {match_str} | {type_str} | {cnt_str} | {verdict} |"
        )
    return "\n".join(lines)


def main():
    argparse.ArgumentParser(description="核对探索页公司名单").parse_args()

    fetcher = Fetcher(CACHE_DIR)
    companies = parse_companies_from_model()
    print(f"=== 读取 core/model 名单，共 {len(companies)} 家公司 ===")

    results = []
    failed_count = 0
    for item in companies:
        res = check_company(fetcher, item)
        results.append(res)
        if not res["passed"]:
            failed_count += 1

    table = render_markdown_table(results)
    print("\n" + table + "\n")

    if failed_count > 0:
        print(f"核对未通过：有 {failed_count} 家公司不满足条件，请修正名单。", file=sys.stderr)
        sys.exit(1)
    print(f"core/model 名单全部 {len(companies)} 家公司核对通过。")
    sys.exit(0)

if __name__ == "__main__":
    main()
