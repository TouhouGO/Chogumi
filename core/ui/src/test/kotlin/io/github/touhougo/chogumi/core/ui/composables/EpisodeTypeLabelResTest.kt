package io.github.touhougo.chogumi.core.ui.composables

import io.github.touhougo.chogumi.core.model.EpisodeType
import io.github.touhougo.chogumi.core.res.R
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 集类型 → 前缀文案的**唯一映射** [episodeTypeLabelRes]。
 *
 * ## 为什么钉在这里
 *
 * 依据 `tools/openapi/generated/p1.bundled.json` 的 `EpisodeType`，接口一共给了 7 个取值
 * （0 本篇 / 1 特别篇 / 2 OP / 3 ED / 4 预告 / 5 MAD / 6 其他）。这个映射逐一对齐它们，
 * **一个 else 兜底都不留**：把未知值回落成「SP」的话，脏数据（`type = 99`）会在特别篇列表里
 * 冒充正常数据。未知值拿到 null，界面据此不显示（见 [io.github.touhougo.chogumi.feature.subjectdetails] 的特别篇列表）。
 *
 * 文案的中英两版在 `core:res` 的同名 key 上，由 `StringsLocaleContractTest` 保证不漏译。
 */
class EpisodeTypeLabelResTest {

    @Test
    fun `逐一映射接口的七个取值`() {
        // 正片没有前缀：返回 null 而不是空串，调用方据此跳过
        assertNull(episodeTypeLabelRes(EpisodeType.NORMAL))
        assertEquals(R.string.episode_type_sp, episodeTypeLabelRes(EpisodeType.SPECIAL))
        assertEquals(R.string.episode_type_op, episodeTypeLabelRes(EpisodeType.OP))
        assertEquals(R.string.episode_type_ed, episodeTypeLabelRes(EpisodeType.ED))
        assertEquals(R.string.episode_type_pre, episodeTypeLabelRes(EpisodeType.PRE))
        assertEquals(R.string.episode_type_mad, episodeTypeLabelRes(EpisodeType.MAD))
        assertEquals(R.string.episode_type_other, episodeTypeLabelRes(EpisodeType.OTHER))
    }

    @Test
    fun `接口没给的取值不给任何前缀`() {
        // 99 不映射成 "SP"：脏数据不该冒充特别篇。
        assertNull(episodeTypeLabelRes(99))
        assertNull(episodeTypeLabelRes(-1))
        assertNull(episodeTypeLabelRes(Int.MAX_VALUE))
    }
}
