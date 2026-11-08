package io.github.touhougo.chogumi.core.network.model.v0

import kotlinx.serialization.Serializable

/** v0 普通分页的四个字段在原始合同均可省略，不与 p1 的 required 分页共用。 */
@Serializable
data class PagedDto<T>(val data: List<T> = emptyList(), val total: Int = 0, val limit: Int = 0, val offset: Int = 0)
@Serializable
data class PageDto(val total: Int, val limit: Int, val offset: Int)
