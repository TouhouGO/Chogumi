"""测试网络客户端门禁（唯一实现处；tools/ci/check.sh lint 运行，单跑：python3 tools/guards/check_test_clients.py）。

规矩见 docs/dev/guide.md §3「与生产同构」。接口测试的客户端只用 core:network 测试夹具的 `testApiClient`（包着生产的
`createHttpClient`）或直接用 `createHttpClient`。测试里自己 `install(ContentNegotiation)` 拼出来的客户端少了生产的
`expectSuccess`、JSON 配置、鉴权与超时，错误响应会走另一条路，测试因为错的原因通过。

检查项：测试源码（常规 `test` / `androidTest` / `testFixtures` 与 `baselineprofile/src/main`）里
- 不得在代码里引用 `ContentNegotiation`：引用它只为自己装插件拼客户端（`install(ContentNegotiation)`、命名参数、先存成值再装都一样）；
- 不得自己造 `HttpClient`：调用（带不带泛型参数）与函数引用 `::HttpClient` 都算，占位、只喂一个网页的客户端也一样，一律 `testApiClient`；
  只当类型写（`lateinit var client: HttpClient`）不算。
`src/testFixtures` 也只能经生产工厂造客户端；同集里另写裸 `HttpClient(...)` 同样会漏掉生产配置。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

from sources import is_import_line, kotlin_typealiases, normalize_code, test_sources

CONTENT_NEGOTIATION = re.compile(r"\bContentNegotiation\b")
BARE_CLIENT = re.compile(r"(?<![\w.])(?:io\.ktor\.client\.)?HttpClient\s*(?:<[^()]*>\s*)?\(|::\s*HttpClient\b")
ALIASED_CLIENT = re.compile(r"(?m)^\s*import\s+io\.ktor\.client\.HttpClient\s+as\s+\w+")


def find_violations(root: Path) -> list[str]:
    violations: list[str] = []
    paths = test_sources(root)
    typealiases = kotlin_typealiases(paths)
    for path in paths:
        rel = path.relative_to(root).as_posix()
        text = normalize_code(path.read_text(encoding="utf-8"), typealiases)
        reported = set()
        for m in CONTENT_NEGOTIATION.finditer(text):
            i = text.count("\n", 0, m.start()) + 1
            if is_import_line(text, m.start()) or i in reported:
                continue
            reported.add(i)
            violations.append(
                f"{rel}:{i}: 测试自己装 ContentNegotiation——接口测试的客户端用 testApiClient(engine)"
                "（core:network 测试夹具，包着生产的 createHttpClient），缺什么补进 testApiClient"
            )
        for pattern in (BARE_CLIENT, ALIASED_CLIENT):
            for m in pattern.finditer(text):
                i = text.count("\n", 0, m.start()) + 1
                if i in reported:
                    continue
                reported.add(i)
                violations.append(
                    f"{rel}:{i}: 测试自己 HttpClient(...) 拼客户端——用 testApiClient(engine)；"
                    "占位也一样（不发请求就在处理器里 error(...)）"
                )
    return violations


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    violations = find_violations(root)
    for v in violations:
        print(v, file=sys.stderr)
    if violations:
        print(f"\n测试网络客户端门禁未通过：{len(violations)} 处（规矩见 docs/dev/guide.md §3「与生产同构」）", file=sys.stderr)
        return 1
    print("测试网络客户端门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
