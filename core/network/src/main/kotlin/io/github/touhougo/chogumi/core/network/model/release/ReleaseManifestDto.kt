package io.github.touhougo.chogumi.core.network.model.release

import kotlinx.serialization.Serializable

/**
 * 正式版附带的 release.json（tools/release/formal_release.py 写出），只取检查更新用得到的字段：版本名、versionCode，
 * 与附件的摘要表（键是附件文件名，安装包是其中唯一以 `.apk` 结尾的那个）。
 */
@Serializable
data class ReleaseManifestDto(
    val versionName: String,
    val versionCode: Int,
    val sha256: Map<String, String> = emptyMap(),
)
