#!/usr/bin/env python3
"""构建未签名 release，经仓库外受控材料签名、核验后原子归档 APK 与同版 R8 mapping。"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import zipfile


class ReleaseError(RuntimeError):
    """发布条件不满足；错误只描述缺项，不回显私密文件内容或签名工具输出。"""


def outside_repository(path: Path, roots: list[Path], label: str) -> Path:
    if not path.is_absolute():
        raise ReleaseError(f"{label}必须使用仓库外的绝对路径")
    resolved = path.resolve()
    if any(resolved == root.resolve() or root.resolve() in resolved.parents for root in roots):
        raise ReleaseError(f"{label}不得放在仓库内")
    return resolved


def private_file(value: str, roots: list[Path], label: str) -> Path:
    path = outside_repository(Path(value), roots, label)
    try:
        info = path.stat()
    except OSError:
        raise ReleaseError(f"缺少{label}，请通过受控文件渠道提供") from None
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise ReleaseError(f"{label}须为当前用户独占的普通文件，权限不允许组或其他用户访问")
    return path


def repository_roots(root: Path) -> list[Path]:
    """仓库所在的目录：工作区本身与主仓库（工作树时 git 目录在主仓库里）。密钥、口令与配置都不得放进其中任何一个。"""
    common = Path(tool(["git", "-C", root, "rev-parse", "--git-common-dir"], "仓库范围核验").strip())
    return [root, (common if common.is_absolute() else root / common).resolve().parent]


def load_config(value: str, roots: list[Path]) -> dict:
    path = private_file(value, roots, "发布配置")
    try:
        config = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        raise ReleaseError("发布配置必须是有效的 UTF-8 JSON") from None
    required = {"keystore", "keyAlias", "storePasswordFile", "keyPasswordFile",
                "certificateSha256", "buildTools", "archiveDir"}
    if not isinstance(config, dict) or set(config) != required or any(
            not isinstance(v, str) or not v.strip() for v in config.values()):
        raise ReleaseError("发布配置只接收 keystore、keyAlias、storePasswordFile、keyPasswordFile、"
                           "certificateSha256、buildTools、archiveDir；不得写入口令值")
    if config["keyAlias"].lower() == "androiddebugkey":
        raise ReleaseError("正式发布不得使用 debug 签名别名")
    digest = config["certificateSha256"].replace(":", "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise ReleaseError("发布证书 SHA-256 必须是 64 位十六进制公钥指纹")
    config["certificateSha256"] = digest
    for key, label in (("keystore", "签名密钥文件"), ("storePasswordFile", "密钥库口令文件"),
                       ("keyPasswordFile", "私钥口令文件")):
        config[key] = private_file(config[key], roots, label)
    if config["storePasswordFile"] == config["keyPasswordFile"]:
        raise ReleaseError("两个单行口令须使用不同安全文件，避免 apksigner 顺序读取同一文件")
    if config["keystore"] == (Path.home() / ".android/debug.keystore").resolve():
        raise ReleaseError("正式发布不得使用默认 debug 密钥")
    config["buildTools"] = outside_repository(Path(config["buildTools"]), roots, "SDK Build Tools")
    for name in ("aapt2", "apksigner", "zipalign"):
        tool = config["buildTools"] / name
        if not tool.is_file() or not os.access(tool, os.X_OK):
            raise ReleaseError(f"SDK Build Tools 缺少可执行的 {name}")
    config["archiveDir"] = outside_repository(Path(config["archiveDir"]), roots, "发布归档目录")
    return config


def tool(args: list, label: str) -> str:
    try:
        result = subprocess.run([str(arg) for arg in args], capture_output=True, text=True,
                                env=dict(os.environ, LC_ALL="C"))
    except OSError:
        raise ReleaseError(f"{label}无法启动") from None
    if result.returncode:
        raise ReleaseError(f"{label}失败；未生成可发布归档")
    return result.stdout


def release_metadata(metadata: dict) -> dict:
    if not isinstance(metadata, dict):
        raise ReleaseError("release 输出元数据必须是对象")
    elements = metadata.get("elements", [])
    if metadata.get("variantName") != "release" or metadata.get("artifactType", {}).get("type") != "APK":
        raise ReleaseError("只接受正式 release APK，benchmark/debug 不可用于发布")
    if len(elements) != 1 or elements[0].get("filters"):
        raise ReleaseError("发布归档要求一个完整的 release APK")
    output = elements[0]
    name, code = output.get("versionName"), output.get("versionCode")
    filename = output.get("outputFile", "")
    if not isinstance(name, str) or not re.fullmatch(r"[0-9A-Za-z._-]+", name) or not isinstance(code, int):
        raise ReleaseError("release 输出元数据缺少有效版本")
    if Path(filename).name != filename or not filename.endswith(".apk"):
        raise ReleaseError("release 输出元数据包含无效 APK 路径")
    return {"applicationId": metadata["applicationId"], "versionName": name, "versionCode": code,
            "outputFile": filename}


def certificate_digest(output: str, expected: str) -> str:
    """`apksigner verify --print-certs` 里签名证书的 SHA-256。

    build-tools 36 及以前按签名者编号输出（`Signer #1 certificate …`），37 起按签名方案输出（`V3.0 Signer: certificate …`），
    同一证书可能出现在几个方案下；所有行必须是同一个证书，且等于批准的指纹。
    """
    digests = {d.lower() for d in re.findall(
        r"(?:Signer #\d+|V[\d.]+ Signer:) certificate SHA-256 digest: ([0-9a-fA-F]{64})", output)}
    if re.search(r"certificate DN:.*Android Debug", output, re.IGNORECASE):
        raise ReleaseError("正式发布不得使用 Android Debug 证书")
    if digests != {expected}:
        raise ReleaseError("APK 签名证书与批准的发布证书不一致")
    return expected


def verify_mapping(apk: Path, mapping: Path) -> str:
    with mapping.open(encoding="utf-8") as stream:
        header = stream.read(4096)
    match = re.search(r"^# pg_map_id: (\S+)$", header, re.MULTILINE)
    if not match or "# compiler: R8" not in header:
        raise ReleaseError("release mapping 缺少 R8 身份")
    map_id = match.group(1)
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if re.fullmatch(r"classes\d*\.dex", name):
                for raw in re.findall(rb"~~R8(\{[^\x00]*\})", archive.read(name)):
                    marker = json.loads(raw)
                    if marker.get("pg-map-id") == map_id and marker.get("compilation-mode") == "release":
                        return map_id
    raise ReleaseError("APK 与 mapping 的 R8 身份不一致，不能混用其它构建的映射")


def verify_apk(apk: Path, metadata: dict, config: dict) -> str:
    tools = config["buildTools"]
    digest = certificate_digest(tool([tools / "apksigner", "verify", "--print-certs", apk], "APK 签名核验"),
                                config["certificateSha256"])
    badging = tool([tools / "aapt2", "dump", "badging", apk], "APK 身份核验")
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.MULTILINE)
    if not package or tuple(package.groups()) != (metadata["applicationId"], str(metadata["versionCode"]), metadata["versionName"]):
        raise ReleaseError("APK 包名或版本与本次 release 输出不一致")
    if "application-debuggable" in badging:
        raise ReleaseError("正式发布 APK 不得可调试")
    tool([tools / "zipalign", "-c", "-P", "16", "4", apk], "APK 对齐核验")
    return digest


def source_revision(root: Path) -> str:
    if tool(["git", "-C", root, "status", "--porcelain"], "源码状态核验").strip():
        raise ReleaseError("正式发布须从干净、已提交的源码构建")
    return tool(["git", "-C", root, "rev-parse", "HEAD"], "源码版本核验").strip()


def archive_release(root: Path, config: dict) -> Path:
    revision = source_revision(root)
    result = subprocess.run([str(root / "gradlew"), "--project-dir", str(root), ":app:assembleRelease",
                             "--max-workers=4", "--console=plain"], env=dict(os.environ, CI="false"))
    if result.returncode:
        raise ReleaseError("正式 release 构建失败，未执行签名")
    if source_revision(root) != revision:
        raise ReleaseError("构建期间源码提交变化，未执行签名")
    outputs = root / "app/build/outputs"
    metadata = release_metadata(json.loads((outputs / "apk/release/output-metadata.json").read_text(encoding="utf-8")))
    unsigned = outputs / "apk/release" / metadata["outputFile"]
    mapping = outputs / "mapping/release/mapping.txt"
    map_id = verify_mapping(unsigned, mapping)
    basename = f"chogumi-{metadata['versionName']}-{metadata['versionCode']}"
    archives = config["archiveDir"]
    archives.mkdir(parents=True, exist_ok=True)
    destination = archives / basename
    if destination.exists():
        raise ReleaseError("该版本归档已经存在，不覆盖已发布产物")
    with tempfile.TemporaryDirectory(prefix=".release-", dir=archives) as temp:
        stage = Path(temp)
        aligned = stage / "aligned.apk"
        signed = stage / f"{basename}.apk"
        tools = config["buildTools"]
        tool([tools / "zipalign", "-P", "16", "-f", "4", unsigned, aligned], "APK 对齐")
        tool([tools / "apksigner", "sign", "--ks", config["keystore"], "--ks-key-alias", config["keyAlias"],
              "--ks-pass", f"file:{config['storePasswordFile']}", "--key-pass", f"file:{config['keyPasswordFile']}",
              "--out", signed, aligned], "受控正式签名")
        digest = verify_apk(signed, metadata, config)
        archived_mapping = stage / f"mapping-{metadata['versionName']}-{metadata['versionCode']}.txt"
        shutil.copy2(mapping, archived_mapping)
        if verify_mapping(signed, archived_mapping) != map_id:
            raise ReleaseError("签名期间 mapping 变化，未归档")
        aligned.unlink()
        hashes = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in stage.iterdir() if p.is_file()}
        manifest = {"applicationId": metadata["applicationId"], "versionName": metadata["versionName"],
                    "versionCode": metadata["versionCode"], "sourceCommit": revision,
                    "certificateSha256": digest, "r8MapId": map_id, "sha256": hashes,
                    "deviceAcceptance": "待正式签名包设备验收，不以 benchmark 代替"}
        (stage / "release.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        if source_revision(root) != revision:
            raise ReleaseError("签名期间源码变化，未归档")
        stage.rename(destination)
    return destination


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    value = os.environ.get("CHOGUMI_RELEASE_CONFIG", "")
    if not value:
        print("缺少 CHOGUMI_RELEASE_CONFIG：只提供仓库外受控 JSON 配置的安全路径，不要粘贴密钥或口令。", file=sys.stderr)
        return 2
    try:
        config = load_config(value, repository_roots(root))
        destination = archive_release(root, config)
        print(f"正式签名与同版 mapping 已归档：{destination}；设备验收仍须单独完成。")
        return 0
    except (ReleaseError, OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        message = str(error) if isinstance(error, ReleaseError) else "发布文件不可读取或格式无效，未生成可发布归档"
        print(message, file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
