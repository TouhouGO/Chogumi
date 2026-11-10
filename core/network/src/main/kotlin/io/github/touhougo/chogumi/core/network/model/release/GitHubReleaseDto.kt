package io.github.touhougo.chogumi.core.network.model.release

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GitHub 接口的一个 Release：发布时写的说明（Markdown；没写为 null 或空串），是不是预发布，与它的附件。
 * 只读说明的地方（`releases/tags/v<版本>`）只用 [body]。不带令牌读，接口不给草稿，所以不读草稿标记。
 */
@Serializable
data class GitHubReleaseDto(
    val body: String? = null,
    val prerelease: Boolean = false,
    val assets: List<GitHubReleaseAssetDto> = emptyList(),
)

/** Release 的一个附件：文件名与下载地址（github.com 上的地址，跳转到 GitHub 的文件服务器）。 */
@Serializable
data class GitHubReleaseAssetDto(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
)
