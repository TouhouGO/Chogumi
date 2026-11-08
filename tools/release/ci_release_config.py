#!/usr/bin/env python3
"""CI 发版（.github/workflows/release.yml）：把仓库 Secrets 里的正式密钥、口令与 bgm.tv 应用凭据落成 formal_release.py 读的受控文件。

    python3 -I tools/release/ci_release_config.py <仓库外的空目录>

读环境变量：
- `CHOGUMI_RELEASE_KEYSTORE`：密钥库 `chogumi-release.p12` 的 base64；
- `CHOGUMI_RELEASE_PASSWORD`：密钥库口令（PKCS12 的私钥口令与它相同，写成两个文件）；
- `CHOGUMI_RELEASE_CERT_SHA256`：批准的证书指纹，写在 workflow 文件里随代码审阅，不从密钥反推；
- `CHOGUMI_OAUTH_CLIENT_ID`、`CHOGUMI_OAUTH_CLIENT_SECRET`：写进仓库根的 oauth-app.properties（已 gitignore）。

目录布局与本机相同（new_release_key.write_release_config），写完按 formal_release.load_config 核验，最后打出 release.json 的路径。
只报缺哪一项，不回显任何值。
"""
from __future__ import annotations

import base64
import os
from pathlib import Path
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from formal_release import ReleaseError, repository_roots  # noqa: E402
from new_release_key import (ARCHIVE, CONFIG, KEY_PASS, KEYSTORE, STORE_PASS, find_build_tools,  # noqa: E402
                             write_private, write_release_config)

OAUTH_FILE = "oauth-app.properties"


def required(environ: dict, name: str) -> str:
    value = environ.get(name, "")
    if not value.strip():
        raise ReleaseError(f"缺少 {name}：在仓库 Settings → Secrets and variables → Actions 里设置")
    return value


def write_ci_release(directory: Path, root: Path, environ: dict, build_tools: Path, roots: list[Path]) -> Path:
    """写出密钥库、两个口令文件、archive/、release.json 与 oauth-app.properties，返回核验过的 release.json 路径。"""
    try:
        # 换行（GNU base64 每 76 个字符折一行）不算内容
        keystore = base64.b64decode("".join(required(environ, "CHOGUMI_RELEASE_KEYSTORE").split()), validate=True)
    except ValueError:
        raise ReleaseError("CHOGUMI_RELEASE_KEYSTORE 不是有效的 base64") from None
    password = required(environ, "CHOGUMI_RELEASE_PASSWORD").strip("\r\n")
    credentials = {key: required(environ, f"CHOGUMI_OAUTH_{key}").strip() for key in ("CLIENT_ID", "CLIENT_SECRET")}
    if any(not re.fullmatch(r"[\x21-\x7e]+", value) for value in credentials.values()):
        raise ReleaseError("bgm.tv 应用凭据只能是不含空白的 ASCII")
    directory = directory.resolve()
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    if any(directory.iterdir()):
        raise ReleaseError(f"{directory} 不是空目录：CI 每次发版都从空目录开始")
    (directory / ARCHIVE).mkdir(mode=0o700)
    fd = os.open(directory / KEYSTORE, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as stream:
        stream.write(keystore)
    for name in (STORE_PASS, KEY_PASS):
        write_private(directory / name, password + "\n")
    oauth = root / OAUTH_FILE
    if oauth.exists():
        raise ReleaseError(f"仓库根已有 {OAUTH_FILE}：CI 只在干净的检出里写它")
    write_private(oauth, "".join(f"{key}={value}\n" for key, value in credentials.items()))
    write_release_config(directory, required(environ, "CHOGUMI_RELEASE_CERT_SHA256").strip(), build_tools, roots)
    return directory / CONFIG


def main() -> int:
    if len(sys.argv) != 2:
        print("用法：ci_release_config.py <仓库外的空目录>", file=sys.stderr)
        return 2
    root = Path(__file__).resolve().parents[2]
    try:
        build_tools = find_build_tools()
        if build_tools is None:
            raise ReleaseError("找不到含 aapt2、apksigner、zipalign 的 build-tools")
        print(write_ci_release(Path(sys.argv[1]), root, dict(os.environ), build_tools.resolve(), repository_roots(root)))
        return 0
    except ReleaseError as error:
        print(error, file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
