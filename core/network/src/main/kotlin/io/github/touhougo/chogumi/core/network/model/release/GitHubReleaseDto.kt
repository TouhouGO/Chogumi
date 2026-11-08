package io.github.touhougo.chogumi.core.network.model.release

import kotlinx.serialization.Serializable

/** GitHub 接口的一个 Release，只取发布时写的说明（Markdown；没写为 null 或空串）。 */
@Serializable
data class GitHubReleaseDto(val body: String? = null)
