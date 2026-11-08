package io.github.touhougo.chogumi.feature.profile

import io.github.touhougo.chogumi.core.data.db.mapper.toBo
import io.github.touhougo.chogumi.core.data.db.mapper.toDetailsBo
import io.github.touhougo.chogumi.core.data.db.mapper.toEntryBo
import io.github.touhougo.chogumi.core.model.IndexCategory
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.SubjectYearMonth
import io.github.touhougo.chogumi.core.network.model.p1.AvatarDto
import io.github.touhougo.chogumi.core.network.model.p1.BlogEntryDto
import io.github.touhougo.chogumi.core.network.model.p1.EpisodeDto
import io.github.touhougo.chogumi.core.network.model.p1.GroupTopicDto
import io.github.touhougo.chogumi.core.network.model.p1.IndexDto
import io.github.touhougo.chogumi.core.network.model.p1.IndexRelatedDto
import io.github.touhougo.chogumi.core.network.model.p1.IndexStatsDto
import io.github.touhougo.chogumi.core.network.model.p1.IndexSubjectStatsDto
import io.github.touhougo.chogumi.core.network.model.p1.P1RatingDto
import io.github.touhougo.chogumi.core.network.model.p1.P1SlimSubjectDto
import io.github.touhougo.chogumi.core.network.model.p1.P1UserDto
import io.github.touhougo.chogumi.core.network.model.p1.PersonImagesDto
import io.github.touhougo.chogumi.core.network.model.p1.SlimBlogEntryDto
import io.github.touhougo.chogumi.core.network.model.p1.SlimCharacterDto
import io.github.touhougo.chogumi.core.network.model.p1.SlimGroupDto
import io.github.touhougo.chogumi.core.network.model.p1.SlimIndexDto
import io.github.touhougo.chogumi.core.network.model.p1.SubjectImagesDto
import io.github.touhougo.chogumi.core.network.model.p1.SubjectTopicDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 日志 / 目录只读页的**映射**单测。
 * 翻页逻辑由 core:common 的 `PagedList` 与 `Pager` 承载（首屏三态 + 触底续取），全仓分页列表共用。
 */
class ProfileReadOnlyMappingTest {

    private val user = P1UserDto(id = 9, username = "fixture_user", nickname = "Fixture User", avatar = AvatarDto("s", "m", "l"))

    @Test
    fun `日志列表项映射_摘要与时间`() {
        val dto = SlimBlogEntryDto(
            id = 1, type = 1, uid = 9, user = user, title = "标题&lt;b&gt;", icon = "",
            summary = "摘要", replies = 3, `public` = true, createdAt = 1_600_000_000, updatedAt = 1_600_000_000,
        )
        val bo = dto.toBo()
        assertEquals(1, bo.id)
        assertEquals("标题<b>", bo.title, "HTML 实体要反转义")
        assertEquals("摘要", bo.summary)
        assertEquals(3, bo.replies)
        assertEquals(1_600_000_000L * 1000, bo.createdAt, "p1 Unix 秒 → 毫秒")
        assertEquals("Fixture User", bo.author?.nickname)
    }

    @Test
    fun `日志详情映射_正文保留 BBCode 原文与留言数`() {
        val dto = BlogEntryDto(
            id = 2, type = 1, uid = 9, user = user, title = "T", icon = "",
            content = "[b]加粗[/b]", tags = emptyList(), views = 0, replies = 14,
            createdAt = 0, updatedAt = 0, noreply = 0, related = 0, `public` = true,
        )
        val bo = dto.toBo(currentUserId = null)
        assertEquals("[b]加粗[/b]", bo.content, "正文必须原样交给 BBCode 解析器")
        assertEquals(14, bo.replies, "详情副行的留言数")
    }

    @Test
    fun `日志详情映射_当前账号写的日志标成自己的`() {
        val dto = BlogEntryDto(
            id = 2, type = 1, uid = 9, user = user, title = "T", icon = "",
            content = "", tags = emptyList(), views = 0, replies = 0,
            createdAt = 0, updatedAt = 0, noreply = 0, related = 0, `public` = true,
        )
        assertEquals(true, dto.toBo(currentUserId = 9).isOwn)
        assertEquals(true, dto.toBo(currentUserId = 9).openingPost().anchor.isOwn, "正文楼跟着日志：长按菜单不对自己出加好友、绝交")
        assertEquals(false, dto.toBo(currentUserId = 10).isOwn)
        assertEquals(false, dto.toBo(currentUserId = null).isOwn, "未登录没有自己的日志")
    }

    @Test
    fun `目录列表项映射_创建时间由秒转毫秒`() {
        // 服务端给 Unix 秒；列表页按毫秒格式化日期，漏转会显示成 1970 年（2026-10-04 真机发现）。
        val dto = SlimIndexDto(
            id = 5, uid = 9, user = user, type = 1, title = "我的目录", `private` = false, total = 12,
            stats = testIndexStats(), createdAt = 1_781_600_877, updatedAt = 1_790_773_930,
        )
        assertEquals(1_781_600_877_000L, dto.toBo().createdAt)
    }

    @Test
    fun `目录详情映射_描述与收藏数`() {
        val dto = IndexDto(
            id = 5, uid = 9, user = user, type = 1, title = "我的目录", desc = "说明",
            `private` = false, total = 12, replies = 0, collects = 7,
            stats = testIndexStats(), award = 0, createdAt = 1_600_000_000, updatedAt = 1_600_000_000,
        )
        val bo = dto.toDetailsBo(currentUserId = null)
        assertEquals("我的目录", bo.title)
        assertEquals("说明", bo.description)
        assertEquals(12, bo.total)
        assertEquals(7, bo.collects)
        assertNotNull(bo.creator)
    }

    @Test
    fun `目录详情映射_类型数只留非零项按类型声明顺序_收藏态看 collectedAt`() {
        val dto = IndexDto(
            id = 1, uid = 1, user = user, type = 0, title = "t", desc = "", `private` = false, total = 9, replies = 2,
            collects = 15, stats = IndexStatsDto(subject = IndexSubjectStatsDto(anime = 1, book = 2, music = 4, game = 0)),
            award = 0, createdAt = 1_275_741_374, updatedAt = 1_746_800_408, collectedAt = 1_700_000_000,
        )
        val bo = dto.toDetailsBo(currentUserId = null)
        assertEquals(listOf(SubjectType.BOOK to 2, SubjectType.ANIME to 1, SubjectType.MUSIC to 4), bo.typeCounts)
        assertEquals(2, bo.replies)
        assertEquals(1_746_800_408_000L, bo.updatedAt)
        assertEquals(true, bo.isCollected)
        assertEquals(false, dto.copy(collectedAt = null).toDetailsBo(currentUserId = null).isCollected)
    }

    @Test
    fun `目录条目映射_类型、副行原文、评分与日期`() {
        val subject = P1SlimSubjectDto(
            id = 12, name = "ちょびっツ", nameCN = "人形电脑天使心", type = 2, info = "27话 / 2002年4月2日 / 浅香守生",
            rating = P1RatingDto(score = 7.49, total = 5535),
        )
        val bo = IndexRelatedDto(id = 2, cat = 0, rid = 1, type = 2, sid = 12, order = 0, comment = "", award = "", createdAt = 0, subject = subject)
            .toEntryBo()
        assertNotNull(bo)
        assertEquals(SubjectType.ANIME, bo.subjectType)
        assertEquals("27话 / 2002年4月2日 / 浅香守生", bo.info)
        assertEquals(7.49, bo.ratingScore, 0.0)
        assertEquals(5535, bo.ratingTotal)
        assertEquals(SubjectYearMonth(2002, 4), bo.date)
    }

    @Test
    fun `目录收录按行自己的类别映射_作品、角色、人物各取各的_其余类别丢弃`() {
        val subject = P1SlimSubjectDto(
            id = 88, name = "Subject", nameCN = "条目", type = 1,
            images = SubjectImagesDto("l", "c", "m", "s", "g"),
        )
        val subjectRow = IndexRelatedDto(
            id = 1, cat = 0, rid = 0, type = 1, sid = 88, order = 3, comment = "作者评语", award = "",
            createdAt = 0, subject = subject,
        )
        val bo = subjectRow.toEntryBo()
        assertNotNull(bo)
        assertEquals(IndexCategory.SUBJECT, bo.category)
        assertEquals(88, bo.targetId)
        assertEquals("条目", bo.nameCn)
        assertEquals("作者评语", bo.comment)

        // 角色行（cat = 1）取 character，不看顺带的 subject；头像走整张立绘（avatarUrl 取 medium）
        val character = SlimCharacterDto(
            id = 99, name = "Chara", nameCN = "角色", role = 1, info = "女", comment = 0, lock = false, nsfw = false,
            images = PersonImagesDto(large = "cl", medium = "cm", small = "cs", grid = "cg"),
        )
        val characterRow = IndexRelatedDto(
            id = 2, cat = 1, rid = 0, type = 0, sid = 99, order = 1, comment = "", award = "",
            createdAt = 0, subject = subject, character = character,
        )
        val characterBo = characterRow.toEntryBo()
        assertNotNull(characterBo)
        assertEquals(IndexCategory.CHARACTER, characterBo.category)
        assertEquals(99, characterBo.targetId)
        assertEquals("cm", characterBo.cover)

        // 缺嵌套对象、或不认识的类别都不渲染
        assertNull(characterRow.copy(character = null).toEntryBo(), "角色行缺 character 不伪造")
        assertNull(subjectRow.copy(cat = 3).toEntryBo(), "章节行缺 episode 不伪造")
        assertNull(subjectRow.copy(cat = 99).toEntryBo(), "不认识的类别不展示")
    }

    @Test
    fun `目录收录的章节、日志、两种话题_标题与所属各取各的`() {
        val subject = P1SlimSubjectDto(id = 88, name = "Subject", nameCN = "条目", type = 2)
        fun row(cat: Int, sid: Int) = IndexRelatedDto(
            id = 10 + cat, cat = cat, rid = 0, type = 0, sid = sid, order = 0, comment = "", award = "", createdAt = 0,
        )
        val episode = row(3, 500).copy(
            episode = EpisodeDto(
                id = 500, subjectID = 88, sort = 12.5, type = 0, disc = 0, name = "EP name", nameCN = "", duration = "",
                airdate = "", comment = 0, desc = "", subject = subject,
            ),
        ).toEntryBo()
        assertNotNull(episode)
        assertEquals(IndexCategory.EPISODE, episode.category)
        assertEquals(500, episode.targetId)
        assertEquals("EP name", episode.name)
        assertEquals(12.5, episode.episodeSort)
        assertEquals("条目", episode.parentName, "章节的所属作品取中文名")

        val blog = row(4, 600).copy(
            blog = SlimBlogEntryDto(
                id = 600, type = 1, uid = 9, user = user, title = "日志&amp;标题", icon = "", summary = "", replies = 0,
                `public` = true, createdAt = 0, updatedAt = 0,
            ),
        ).toEntryBo()
        assertNotNull(blog)
        assertEquals(IndexCategory.BLOG, blog.category)
        assertEquals("日志&标题", blog.name)
        assertEquals("Fixture User", blog.parentName, "日志的所属是作者的显示名")

        val group = SlimGroupDto(
            id = 1, name = "a", nsfw = false, title = "小组", icon = AvatarDto(), creatorID = 1, members = 0, accessible = true, createdAt = 0,
        )
        val groupTopic = row(5, 700).copy(
            groupTopic = GroupTopicDto(
                id = 700, title = "小组话题", creatorID = 9, parentID = 1, replyCount = 0, createdAt = 0, updatedAt = 0, state = 0,
                display = 1, group = group, replies = emptyList(),
            ),
        ).toEntryBo()
        assertNotNull(groupTopic)
        assertEquals(IndexCategory.GROUP_TOPIC, groupTopic.category)
        assertEquals("小组", groupTopic.parentName)

        val subjectTopic = row(6, 800).copy(
            subjectTopic = SubjectTopicDto(
                id = 800, title = "作品话题", creatorID = 9, parentID = 88, replyCount = 0, createdAt = 0, updatedAt = 0, state = 0,
                display = 1, subject = subject, replies = emptyList(),
            ),
        ).toEntryBo()
        assertNotNull(subjectTopic)
        assertEquals(IndexCategory.SUBJECT_TOPIC, subjectTopic.category)
        assertEquals(800, subjectTopic.targetId)
        assertEquals("条目", subjectTopic.parentName)
    }

    @Test
    fun `目录详情映射_类别数只留非零项_作品数是各类型之和`() {
        val dto = IndexDto(
            id = 1, uid = 1, user = user, type = 0, title = "t", desc = "", `private` = false, total = 9, replies = 2,
            collects = 15,
            stats = IndexStatsDto(
                subject = IndexSubjectStatsDto(anime = 1, book = 2), character = 5, person = 0, episode = 2, groupTopic = 1,
            ),
            award = 0, createdAt = 0, updatedAt = 0,
        )
        assertEquals(
            listOf(IndexCategory.SUBJECT to 3, IndexCategory.CHARACTER to 5, IndexCategory.EPISODE to 2, IndexCategory.GROUP_TOPIC to 1),
            dto.toDetailsBo(currentUserId = null).categoryCounts,
        )
    }

    @Test
    fun `目录条目封面缺 large 回退 common`() {
        val subject = P1SlimSubjectDto(
            id = 88, name = "S", nameCN = "", type = 1,
            images = SubjectImagesDto("", "common-url", "m", "s", "g"),
        )
        val row = IndexRelatedDto(id = 1, cat = 0, rid = 0, type = 1, sid = 88, order = 0, comment = "", award = "", createdAt = 0, subject = subject)
        assertEquals("common-url", row.toEntryBo()?.cover)
    }

    private fun testIndexStats() = IndexStatsDto(
        subject = IndexSubjectStatsDto(),
    )
}
