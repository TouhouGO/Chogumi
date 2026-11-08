package io.github.touhougo.chogumi.core.network.model.bangumidata

import kotlinx.serialization.Serializable

/** bangumi-data 的整份数据（`dist/data.json`），只取用得到的字段：站点元数据、标题、译名都不解析。 */
@Serializable
data class BangumiDataDto(val items: List<BangumiDataItemDto> = emptyList())

/**
 * 一部作品：[begin] 是开播时刻（ISO 8601），[broadcast] 是放送周期（`R/<首播时刻>/P7D`，没有或空串是不定期），
 * [sites] 里 `site = "bangumi"` 的那条带 bgm.tv 条目 id。
 */
@Serializable
data class BangumiDataItemDto(
    val begin: String = "",
    val broadcast: String? = null,
    val sites: List<BangumiDataSiteDto> = emptyList(),
)

/** 作品在一个站点上的条目：[id] 是那个站点自己的 id（bgm.tv 是数字串），少数站点只有链接没有 id。 */
@Serializable
data class BangumiDataSiteDto(val site: String, val id: String? = null)
