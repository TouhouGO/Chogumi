package io.github.touhougo.chogumi.core.reader

import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.TimelineItemBo
import io.github.touhougo.chogumi.core.model.TimelineSegmentBo
import io.github.touhougo.chogumi.core.model.TimelineTargetBo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 时间线连续剧集的本地合并展示。
 *
 * 覆盖：
 * 1. 连续剧集合并：同一用户、同一作品、连续看第 1、2、3 集合并为 1-3，时间与锚点为最新一条。
 * 2. 非连续断开：1、2、4 集，1-2 合并，4 独立，禁止跨洞合并。
 * 3. 干扰项打断：两集之间穿插了他人的动态，或同一用户换了作品，断开分组不跨项合并。
 * 4. 非收视类型不合并：吐槽、收藏动态即使连续也不做剧集合并。
 * 5. 连续同一用户的分组只出一次头，交替发布时不跨人合并。
 */
class TimelineMergeTest {

    private fun createProgressItem(
        id: Long,
        uid: Int,
        subjectId: Int,
        sort: Double,
        createdAt: Long,
    ): TimelineItemBo {
        val subject = SubjectBo(
            id = subjectId,
            type = io.github.touhougo.chogumi.core.model.SubjectType.ANIME,
            name = "Subject $subjectId",
            nameCn = "作品 $subjectId",
        )
        val target = TimelineTargetBo(
            title = subject.nameCn,
            url = "https://bgm.tv/subject/$subjectId",
            subject = subject,
            segments = listOf(TimelineSegmentBo.EpisodeProgress(sort = sort, name = "第${sort}集")),
        )
        return TimelineItemBo(
            id = id,
            uid = uid,
            user = null,
            cat = 4, // 收视进度
            type = 0,
            createdAt = createdAt,
            text = "",
            targets = listOf(target),
        )
    }

    private fun createStatusItem(id: Long, uid: Int, createdAt: Long): TimelineItemBo {
        return TimelineItemBo(
            id = id,
            uid = uid,
            user = null,
            cat = 5,
            type = 1,
            createdAt = createdAt,
            text = "吐槽内容 $id",
        )
    }

    @Test
    fun `连续剧集合并为单条且范围正确`() {
        // 时间降序：最新是第 3 集，其次第 2 集，最早第 1 集
        val items = listOf(
            createProgressItem(id = 303L, uid = 1, subjectId = 100, sort = 3.0, createdAt = 3000L),
            createProgressItem(id = 302L, uid = 1, subjectId = 100, sort = 2.0, createdAt = 2000L),
            createProgressItem(id = 301L, uid = 1, subjectId = 100, sort = 1.0, createdAt = 1000L),
        )

        val groups = mergeConsecutiveTimelineItems(items)

        assertEquals(1, groups.size, "3 条连续集数应合并为 1 个分组")
        val group = groups[0]
        assertTrue(group.isMerged)
        assertEquals(303L, group.anchor.id, "交互锚点必须是最新一条")
        assertEquals(3000L, group.anchor.createdAt, "时间戳取最新一条")
        assertEquals(1.0, group.episodeStart)
        assertEquals(3.0, group.episodeEnd)
        assertEquals(3, group.items.size)
    }

    @Test
    fun `非连续剧集断开禁止跨洞合并`() {
        // 用户看了第 4 集，第 2 集，第 1 集（缺少第 3 集）
        val items = listOf(
            createProgressItem(id = 304L, uid = 1, subjectId = 100, sort = 4.0, createdAt = 4000L),
            createProgressItem(id = 302L, uid = 1, subjectId = 100, sort = 2.0, createdAt = 2000L),
            createProgressItem(id = 301L, uid = 1, subjectId = 100, sort = 1.0, createdAt = 1000L),
        )

        val groups = mergeConsecutiveTimelineItems(items)

        assertEquals(2, groups.size, "缺少第 3 集应断开为 2 个分组")
        // 第一组：第 4 集独立
        assertEquals(304L, groups[0].anchor.id)
        assertFalse(groups[0].isMerged)
        assertNull(groups[0].episodeStart)

        // 第二组：第 1-2 集连续合并
        assertEquals(302L, groups[1].anchor.id)
        assertTrue(groups[1].isMerged)
        assertEquals(1.0, groups[1].episodeStart)
        assertEquals(2.0, groups[1].episodeEnd)
    }

    @Test
    fun `穿插他人动态时断开分组`() {
        // User 1 看第 2 集，中间插了 User 2 的动态，然后 User 1 看第 1 集
        val items = listOf(
            createProgressItem(id = 202L, uid = 1, subjectId = 100, sort = 2.0, createdAt = 2000L),
            createProgressItem(id = 999L, uid = 2, subjectId = 200, sort = 5.0, createdAt = 1500L),
            createProgressItem(id = 201L, uid = 1, subjectId = 100, sort = 1.0, createdAt = 1000L),
        )

        val groups = mergeConsecutiveTimelineItems(items)

        assertEquals(3, groups.size, "中间穿插他人动态不应跨项合并")
        assertFalse(groups[0].isMerged)
        assertFalse(groups[1].isMerged)
        assertFalse(groups[2].isMerged)
    }

    @Test
    fun `同一用户换了作品时断开分组`() {
        // User 1 先看作品 100 的第 1 集，再看作品 200 的第 2 集：集数相邻但不是同一部
        val items = listOf(
            createProgressItem(id = 202L, uid = 1, subjectId = 200, sort = 2.0, createdAt = 2000L),
            createProgressItem(id = 101L, uid = 1, subjectId = 100, sort = 1.0, createdAt = 1000L),
        )

        val groups = mergeConsecutiveTimelineItems(items)

        assertEquals(2, groups.size, "不同作品的相邻集数不合并")
        assertFalse(groups[0].isMerged)
        assertFalse(groups[1].isMerged)
    }

    @Test
    fun `非收视类型不参与剧集合并`() {
        val items = listOf(
            createStatusItem(id = 502L, uid = 1, createdAt = 2000L),
            createStatusItem(id = 501L, uid = 1, createdAt = 1000L),
        )
        val groups = mergeConsecutiveTimelineItems(items)
        assertEquals(2, groups.size, "纯状态类型不合并")
        assertFalse(groups[0].isMerged)
        assertFalse(groups[1].isMerged)
    }

    @Test
    fun `连续同一用户动态合并为一个用户组只出一次头`() {
        val item1 = TimelineDisplayGroup(anchor = createStatusItem(id = 1L, uid = 10, createdAt = 3000L), items = emptyList())
        val item2 = TimelineDisplayGroup(anchor = createStatusItem(id = 2L, uid = 10, createdAt = 2000L), items = emptyList())
        val item3 = TimelineDisplayGroup(anchor = createStatusItem(id = 3L, uid = 20, createdAt = 1000L), items = emptyList())

        val userGroups = groupConsecutiveByUser(listOf(item1, item2, item3))
        assertEquals(2, userGroups.size)
        assertEquals(10, userGroups[0].uid)
        assertEquals(2, userGroups[0].items.size)
        assertEquals(20, userGroups[1].uid)
        assertEquals(1, userGroups[1].items.size)
    }

    @Test
    fun `用户交替发布动态时不跨人合并`() {
        val item1 = TimelineDisplayGroup(anchor = createStatusItem(id = 1L, uid = 10, createdAt = 3000L), items = emptyList())
        val item2 = TimelineDisplayGroup(anchor = createStatusItem(id = 2L, uid = 20, createdAt = 2000L), items = emptyList())
        val item3 = TimelineDisplayGroup(anchor = createStatusItem(id = 3L, uid = 10, createdAt = 1000L), items = emptyList())

        val userGroups = groupConsecutiveByUser(listOf(item1, item2, item3))
        assertEquals(3, userGroups.size)
        assertEquals(10, userGroups[0].uid)
        assertEquals(20, userGroups[1].uid)
        assertEquals(10, userGroups[2].uid)
    }
}
