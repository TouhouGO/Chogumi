package io.github.touhougo.chogumi.core.network.model.p1

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable

/** GET /p1/trending/subjects 条目：精简条目 + 热度计数 */
@Serializable
data class P1TrendingSubjectDto(
    val subject: P1SlimSubjectDto,
    @Required val count: Int = 0,
)

/** GET /p1/trending/subjects 响应 */
@Serializable
data class P1TrendingPageDto(
    @Required val data: List<P1TrendingSubjectDto> = emptyList(),
    @Required val total: Int = 0,
)
