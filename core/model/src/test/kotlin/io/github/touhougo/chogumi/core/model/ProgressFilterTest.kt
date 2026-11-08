package io.github.touhougo.chogumi.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进度页「筛选」的两个口径：逐集数据到了哪一步（EP 按钮的守卫），
 * 以及「标签」的选项收集、面板里的次序与匹配。
 */
class ProgressFilterTest {

    // ---------- 逐集数据到了哪一步（EP 按钮的守卫，非「只看」专用） ----------

    private val oneEpisode = listOf(EpisodeBo(id = 1, subjectId = 1, type = EpisodeType.NORMAL, name = "e1", nameCn = "", sort = 1.0))
    private val oneState = mapOf(1 to EpisodeStatus.NONE)

    @Test
    fun `逐集数据 动画三次元要求章节与逐集状态都到齐`() {
        assertEquals(EpisodeDataState.READY, episodeDataState(SubjectType.ANIME, oneEpisode, oneState, loadFailed = false))
        assertEquals(EpisodeDataState.READY, episodeDataState(SubjectType.REAL, oneEpisode, oneState, loadFailed = false))
        // 章节请求还没返回（null）
        assertEquals(EpisodeDataState.LOADING, episodeDataState(SubjectType.ANIME, null, oneState, loadFailed = false))
        // 已返回的空列表 = 确实零集（如 eps = 0 的条目）；当成加载中会挂永久占位
        assertEquals(EpisodeDataState.READY, episodeDataState(SubjectType.ANIME, emptyList(), oneState, loadFailed = false))
        // 逐集状态没到
        assertEquals(EpisodeDataState.LOADING, episodeDataState(SubjectType.ANIME, oneEpisode, null, loadFailed = false))
    }

    @Test
    fun `逐集数据 请求失败且数据不齐是终态 不按一集没看往下算`() {
        assertEquals(EpisodeDataState.FAILED, episodeDataState(SubjectType.ANIME, null, null, loadFailed = true))
        // 章节已到、逐集状态请求失败：READY 会把下一集算成第 1 集
        assertEquals(EpisodeDataState.FAILED, episodeDataState(SubjectType.ANIME, oneEpisode, null, loadFailed = true))
        // 数据后来齐了（逐集状态从别处到了）就照常算
        assertEquals(EpisodeDataState.READY, episodeDataState(SubjectType.ANIME, oneEpisode, oneState, loadFailed = true))
    }

    @Test
    fun `逐集数据 不按逐集推进的类型恒为 READY`() {
        listOf(SubjectType.BOOK, SubjectType.MUSIC, SubjectType.GAME).forEach { type ->
            assertEquals(EpisodeDataState.READY, episodeDataState(type, null, null, loadFailed = false))
            assertEquals(EpisodeDataState.READY, episodeDataState(type, null, null, loadFailed = true))
        }
    }

    // ---------- 标签 ----------

    @Test
    fun `标签并集 命中任一即通过`() {
        assertTrue(matchesTagFilter(listOf("补番", "神作"), setOf("神作", "下饭")))
        assertFalse(matchesTagFilter(listOf("补番"), setOf("神作")))
        assertTrue(matchesTagFilter(emptyList(), emptySet()))
        assertFalse(matchesTagFilter(emptyList(), setOf("神作")))
    }

    private fun tagged(id: Int, updatedAt: Long, vararg tags: String) = UserCollectionBo(
        subjectId = id, subjectType = SubjectType.ANIME, type = CollectionType.DOING, updatedAt = updatedAt, tags = tags.toList(),
        subject = SubjectBo(id = id, type = SubjectType.ANIME, name = "作品$id", nameCn = "作品$id"),
    )

    @Test
    fun `标签选项按部数从多到少 同部数按拼音 丢弃空白`() {
        val items = listOf(
            tagged(1, 0L, "神作", "补番"),
            tagged(2, 0L, "补番", "  "),
            tagged(3, 0L, "治愈系", "补番"),
            tagged(4, 0L, "芳文社"),
        )
        val options = availableProgressTags(items, emptyMap())
        // 补番 3 部在前；其余各 1 部按拼音：芳 fang < 神 shen < 治 zhi（码点顺序是 治 < 神 < 芳，会排反）
        assertEquals(listOf("补番", "芳文社", "神作", "治愈系"), options.map { it.key })
        assertEquals(listOf(3, 1, 1, 1), options.map { it.count })
    }

    @Test
    fun `一部作品重复打同一个标签只算一次`() {
        val options = availableProgressTags(listOf(tagged(1, 0L, "补番", "补番")), emptyMap())
        assertEquals(listOf(1), options.map { it.count })
    }

    @Test
    fun `标签的封面是最近动过的三部 最近的在前`() {
        val items = listOf(
            tagged(1, 100L, "补番"),
            tagged(2, 400L, "补番"),
            tagged(3, 200L, "补番"),
            tagged(4, 300L, "补番"),
        )
        // 作品 1 收藏时间最旧，但刚看过一集：与「最近操作」同一个时刻
        val option = availableProgressTags(items, latestWatchedAt = mapOf(1 to 900L)).single()
        assertEquals(4, option.count)
        assertEquals(listOf(1, 2, 4), option.recent.map { it.id })
    }

    @Test
    fun `面板里打开时已选的标签排在最前 其余保持部数次序`() {
        val options = availableProgressTags(
            listOf(tagged(1, 0L, "补番", "神作"), tagged(2, 0L, "补番", "神作"), tagged(3, 0L, "补番", "芳文社")),
            emptyMap(),
        )
        assertEquals(listOf("补番", "神作", "芳文社"), options.map { it.key })
        assertEquals(listOf("芳文社", "补番", "神作"), progressPanelTags(options, pinned = setOf("芳文社")).map { it.key })
        assertEquals(options, progressPanelTags(options, pinned = emptySet()))
    }
}
