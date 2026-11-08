#!/usr/bin/env python3
"""负责人一次性生成正式密钥：问一次口令，建好密钥库、两个口令文件与 formal_release.py 读的 release.json。

只在负责人自己的终端里运行，口令只由本人输入；已有任何一个目标文件就拒绝，绝不覆盖已有密钥。
写出的配置当场交给 formal_release.load_config 核验，与发布管线用同一份规则。
"""
from __future__ import annotations

import argparse
import getpass
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from formal_release import ReleaseError, load_config, repository_roots  # noqa: E402

DEFAULT_DIR = Path.home() / ".config/chogumi-release"
KEY_ALIAS = "chogumi"
DNAME = "CN=Chogumi, O=TouhouGO"
KEYSTORE = "chogumi-release.p12"
STORE_PASS = "store-pass.txt"
KEY_PASS = "key-pass.txt"
CONFIG = "release.json"
ARCHIVE = "archive"
BUILD_TOOLS_REQUIRED = ("aapt2", "apksigner", "zipalign")
ANDROID_STUDIO_KEYTOOL = Path("/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool")
MIN_PASSWORD_LENGTH = 6


def find_keytool() -> Path | None:
    """第一个能跑的 keytool：JAVA_HOME、PATH、Android Studio 自带的 JDK。

    macOS 的 /usr/bin/keytool 只是转发器，没装独立 JDK 时一运行就失败，所以每个候选都试跑一次。
    """
    candidates = []
    if os.environ.get("JAVA_HOME"):
        candidates.append(str(Path(os.environ["JAVA_HOME"]) / "bin/keytool"))
    candidates += [shutil.which("keytool"), str(ANDROID_STUDIO_KEYTOOL)]
    for candidate in candidates:
        if not candidate or not os.access(candidate, os.X_OK):
            continue
        if subprocess.run([candidate, "-help"], capture_output=True).returncode == 0:
            return Path(candidate)
    return None


def version_key(name: str) -> tuple:
    return tuple(int(part) if part.isdigit() else -1 for part in re.split(r"[.-]", name))


def find_build_tools() -> Path | None:
    """SDK 里版本最高、三件工具齐全的 build-tools 目录；SDK 取 ANDROID_HOME / ANDROID_SDK_ROOT，否则 macOS 默认位置。"""
    sdks = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"), str(Path.home() / "Library/Android/sdk")]
    for sdk in sdks:
        if not sdk:
            continue
        base = Path(sdk) / "build-tools"
        if not base.is_dir():
            continue
        for version in sorted((p for p in base.iterdir() if p.is_dir()), key=lambda p: version_key(p.name), reverse=True):
            if all(os.access(version / name, os.X_OK) for name in BUILD_TOOLS_REQUIRED):
                return version
    return None


def write_private(path: Path, text: str) -> None:
    """新建当前用户独占（0600）的文件；文件已存在就失败，不覆盖。"""
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as stream:
        stream.write(text)


def certificate_sha256(keytool: Path, keystore: Path, store_pass: Path) -> str:
    output = subprocess.run(
        [str(keytool), "-J-Duser.language=en", "-list", "-v", "-keystore", str(keystore), "-alias", KEY_ALIAS,
         "-storepass:file", str(store_pass)],
        capture_output=True, text=True,
    )
    match = re.search(r"SHA256:\s*((?:[0-9A-F]{2}:){31}[0-9A-F]{2})", output.stdout)
    if output.returncode or not match:
        raise ReleaseError("读不出新密钥的证书指纹")
    return match.group(1)


def write_release_config(directory: Path, certificate: str, build_tools: Path, roots: list[Path]) -> dict:
    """在 directory 里写 release.json（指向同目录的密钥库、两个口令文件与 archive/），按发布管线的规则核验后返回。

    目录布局只有这一份：本机生成密钥与 CI 落 Secrets（ci_release_config.py）都经这里。
    """
    config = {
        "keystore": str(directory / KEYSTORE),
        "keyAlias": KEY_ALIAS,
        "storePasswordFile": str(directory / STORE_PASS),
        "keyPasswordFile": str(directory / KEY_PASS),
        "certificateSha256": certificate,
        "buildTools": str(build_tools),
        "archiveDir": str(directory / ARCHIVE),
    }
    write_private(directory / CONFIG, json.dumps(config, ensure_ascii=False, indent=2) + "\n")
    load_config(str(directory / CONFIG), roots)
    return config


def create_release_key(directory: Path, password: str, keytool: Path, build_tools: Path,
                       roots: list[Path]) -> dict:
    """在 directory 里生成密钥库、口令文件与配置，返回核验过的配置。任何一个目标已存在就拒绝。"""
    if len(password) < MIN_PASSWORD_LENGTH:
        raise ReleaseError(f"口令至少 {MIN_PASSWORD_LENGTH} 个字符（keytool 的下限）")
    if not all(" " < ch <= "~" for ch in password):
        raise ReleaseError("口令只能用英文字母、数字和半角符号，不含空格（PKCS12 密钥库不接受中文等非 ASCII 字符）")
    directory = directory.expanduser().resolve()
    targets = [directory / name for name in (KEYSTORE, STORE_PASS, KEY_PASS, CONFIG)]
    existing = [path.name for path in targets if path.exists()]
    if existing:
        raise ReleaseError(f"{directory} 里已有 {'、'.join(existing)}：不覆盖已有密钥，换一个目录或先确认旧密钥的去向")
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    (directory / ARCHIVE).mkdir(mode=0o700, exist_ok=True)
    keystore, store_pass, key_pass, config_path = targets

    created: list[Path] = []
    try:
        for path in (store_pass, key_pass):
            write_private(path, password + "\n")
            created.append(path)
        # 上面已确认密钥库不存在，此后出现的文件都是这次 keytool 写的：失败（含写到一半）一并删掉
        created.append(keystore)
        # PKCS12 的私钥口令就是密钥库口令：不传 -keypass，keytool 用同一个口令
        result = subprocess.run(
            [str(keytool), "-J-Duser.language=en", "-genkeypair", "-storetype", "PKCS12", "-keystore", str(keystore), "-alias", KEY_ALIAS,
             "-keyalg", "RSA", "-keysize", "4096", "-validity", "36500", "-dname", DNAME,
             "-storepass:file", str(store_pass)],
            capture_output=True, text=True,
        )
        if result.returncode or not keystore.is_file():
            raise ReleaseError("keytool 生成密钥失败")
        keystore.chmod(0o600)
        created.append(config_path)
        return write_release_config(directory, certificate_sha256(keytool, keystore, store_pass), build_tools, roots)
    except BaseException:
        for path in reversed(created):
            path.unlink(missing_ok=True)
        raise


def read_password() -> str:
    first = getpass.getpass("正式密钥口令：")
    if getpass.getpass("再输入一次：") != first:
        raise ReleaseError("两次输入的口令不一致")
    return first


def main() -> int:
    parser = argparse.ArgumentParser(description="一次性生成 Chogumi 正式签名密钥与 formal_release.py 的配置")
    parser.add_argument("--dir", type=Path, default=DEFAULT_DIR, help=f"放密钥与配置的仓库外目录（默认 {DEFAULT_DIR}）")
    parser.add_argument("--build-tools", type=Path, help="Android SDK build-tools 的版本目录（默认取 SDK 里最新的）")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    try:
        keytool = find_keytool()
        if keytool is None:
            raise ReleaseError("找不到 keytool：装 JDK，或设置 JAVA_HOME 指向 Android Studio 自带的 jbr")
        build_tools = args.build_tools or find_build_tools()
        if build_tools is None:
            raise ReleaseError("找不到含 aapt2、apksigner、zipalign 的 build-tools：用 --build-tools 指定")
        config = create_release_key(args.dir, read_password(), keytool, build_tools.resolve(), repository_roots(root))
    except ReleaseError as error:
        print(error, file=sys.stderr)
        return 1
    directory = Path(config["keystore"]).parent
    print(f"正式密钥已生成：{config['keystore']}")
    print(f"证书指纹（SHA-256）：{config['certificateSha256']}")
    print(f"把 {directory} 里的 {KEYSTORE} 和口令各存一份离线备份；两处都丢了就发不了能覆盖升级的包。")
    print(f"发布：CHOGUMI_RELEASE_CONFIG=\"{directory / CONFIG}\" python3 -I tools/release/formal_release.py")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
