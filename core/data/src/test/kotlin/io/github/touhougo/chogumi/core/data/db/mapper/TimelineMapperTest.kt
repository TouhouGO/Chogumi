package io.github.touhougo.chogumi.core.data.db.mapper

import io.github.touhougo.chogumi.core.model.COMMON_REACTIONS
import io.github.touhougo.chogumi.core.model.SUBJECT_COLLECT_REACTIONS
import io.github.touhougo.chogumi.core.model.TimelineReactionTarget
import io.github.touhougo.chogumi.core.model.TimelineSegmentBo
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.network.model.p1.AvatarDto
import io.github.touhougo.chogumi.core.network.model.p1.P1SlimSubjectDto
import io.github.touhougo.chogumi.core.network.model.p1.P1UserDto
import io.github.touhougo.chogumi.core.network.model.p1.PersonImagesDto
import io.github.touhougo.chogumi.core.network.model.p1.ReactionDto
import io.github.touhougo.chogumi.core.network.model.p1.SimpleUserDto
import io.github.touhougo.chogumi.core.network.model.p1.SlimCharacterDto
import io.github.touhougo.chogumi.core.network.model.p1.SlimGroupDto
import io.github.touhougo.chogumi.core.network.model.p1.IndexStatsDto
import io.github.touhougo.chogumi.core.network.model.p1.IndexSubjectStatsDto
import io.github.touhougo.chogumi.core.network.model.p1.SlimIndexDto
import io.github.touhougo.chogumi.core.network.model.p1.TimelineDailyDto
import io.github.touhougo.chogumi.core.network.model.p1.TimelineDto
import io.github.touhougo.chogumi.core.network.model.p1.TimelineMemoDto
import io.github.touhougo.chogumi.core.network.model.p1.TimelineMonoDto
import io.github.touhougo.chogumi.core.network.model.p1.TimelineProgressBatchDto
import io.github.touhougo.chogumi.core.network.model.p1.TimelineProgressDto
import io.github.touhougo.chogumi.core.network.model.p1.TimelineStatusDto
import io.github.touhougo.chogumi.core.network.model.p1.TimelineSubjectDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimelineMapperTest {
    @Test
    fun `好友动态目标用用户名作网页键_没有用户名时用数字id且普通用户名不变`() {
        val named = P1UserDto(42, "fixture_user", "Fixture User", AvatarDto(), group = 1, joinedAt = 0, sign = "", isFriend = false)
        val unnamed = named.copy(id = 43, username = "", nickname = "#43")
        val item = TimelineDto(
            id = 1,
            uid = 7,
            cat = 1,
            type = 2,
            createdAt = 0,
            memo = TimelineMemoDto(daily = TimelineDailyDto(users = listOf(named, unnamed))),
        ).toTimelineItem()

        assertEquals(listOf("https://bgm.tv/user/fixture_user", "https://bgm.tv/user/43"), item.targets.map { it.url })
    }

    @Test
    fun `好友动态显示名按昵称用户名与数字身份回退且只解码一层`() {
        val user = P1UserDto(42, "fixture_user", "", AvatarDto(), group = 1, joinedAt = 0, sign = "", isFriend = false)
        val users = listOf(
            user,
            user.copy(id = 43, username = "", nickname = "　 "),
            user.copy(id = 44, nickname = "A &amp; B"),
            user.copy(id = 45, nickname = "A &amp;amp; B"),
            user.copy(id = 46, nickname = "&#32;"),
        )
        val item = TimelineDto(id = 1, uid = 7, cat = 1, type = 2, createdAt = 0,
            memo = TimelineMemoDto(daily = TimelineDailyDto(users = users)))
            .toTimelineItem()

        assertEquals(listOf("fixture_user", "#43", "A & B", "A &amp; B", "fixture_user"), item.targets.map { it.title })
    }

    @Test
    fun `批量收藏保留每条评论评分且不能贴贴`() {
        val dto = TimelineDto(id = 3, uid = 42, cat = 3, type = 6, batch = true,
            createdAt = 1_700_000_000,
            memo = TimelineMemoDto(subject = listOf(
                TimelineSubjectDto(P1SlimSubjectDto(8, "A", type = 2), "第一条", 8.0, 10),
                TimelineSubjectDto(P1SlimSubjectDto(9, "B", type = 2), "第二条", 9.0, 11),
            )))
        val item = dto.toTimelineItem()
        assertEquals(listOf("第一条", "第二条"), item.targets.map { it.description })
        assertEquals(listOf(8.0, 9.0), item.targets.map { it.rating })
        assertEquals(1_700_000_000_000, item.createdAt)
        assertNull(item.reactionTarget)
    }

    /** 单条收藏的贴贴就是那条收藏的贴贴：贴到收藏上（collectID），能贴的是作品吐槽那一组。 */
    @Test
    fun `单条收藏的贴贴挂在那条收藏上`() {
        val item = TimelineDto(id = 5, uid = 42, cat = 3, type = 2, createdAt = 1_700_000_000,
            memo = TimelineMemoDto(subject = listOf(TimelineSubjectDto(P1SlimSubjectDto(8, "A", type = 2), "评论", 8.0, 10))))
            .toTimelineItem()
        assertEquals(TimelineReactionTarget.Collect(subjectId = 8, collectId = 10), item.reactionTarget)
        assertEquals(SUBJECT_COLLECT_REACTIONS, item.reactionTarget?.palette)
    }

    /** 服务端只给带吐槽的单条收藏回填那条收藏的贴贴；没写吐槽时贴了也回不到动态上，所以不能贴。 */
    @Test
    fun `没写吐槽的单条收藏不能贴贴`() {
        val item = TimelineDto(id = 7, uid = 42, cat = 3, type = 2, createdAt = 1_700_000_000,
            memo = TimelineMemoDto(subject = listOf(TimelineSubjectDto(P1SlimSubjectDto(8, "A", type = 2), "", 8.0, 10))))
            .toTimelineItem()
        assertNull(item.reactionTarget)
    }

    @Test
    fun `没有collectID的收藏不能贴贴`() {
        val item = TimelineDto(id = 6, uid = 42, cat = 3, type = 2, createdAt = 1_700_000_000,
            memo = TimelineMemoDto(subject = listOf(TimelineSubjectDto(P1SlimSubjectDto(8, "A", type = 2), "有吐槽但没有收藏编号", 8.0, null))))
            .toTimelineItem()
        assertNull(item.reactionTarget)
    }

    /** 服务端用 0 表示「没打分」：映射成 null，界面才不会画出「★ 0」（真机发现）。 */
    @Test
    fun `收藏评分为零视为未评分`() {
        val dto = TimelineDto(id = 4, uid = 42, cat = 3, type = 6, createdAt = 1_700_000_000,
            memo = TimelineMemoDto(subject = listOf(
                TimelineSubjectDto(P1SlimSubjectDto(8, "A", type = 2), "", 0.0, 10),
                TimelineSubjectDto(P1SlimSubjectDto(9, "B", type = 2), "", null, 11),
            )))
        assertEquals(listOf(null, null), dto.toTimelineItem().targets.map { it.rating })
    }

    /**
     * 服务端统计（成员数、章节数、关联数）以**结构化片段**给出，不是拼好的句子。
     *
     * 文案在渲染层的关键：mapper 只传值，措辞随资源语言变。
     * 若这里退回成 `description = "5 位成员"`，英文界面又会露中文。
     */
    @Test
    fun `服务端统计以结构化片段给出而非拼好的句子`() {
        val group = TimelineDto(id = 10, uid = 42, cat = 1, type = 3, createdAt = 1_700_000_000,
            memo = TimelineMemoDto(daily = TimelineDailyDto(groups = listOf(
                SlimGroupDto(1, "grp", false, "某小组", AvatarDto(), 1, 5, true, 0),
            ))))
        assertEquals(listOf(TimelineSegmentBo.GroupMembers(5)), group.toTimelineItem().targets.single().segments)
        assertEquals("", group.toTimelineItem().targets.single().description)

        val index = TimelineDto(id = 11, uid = 42, cat = 7, type = 0, createdAt = 1_700_000_000,
            memo = TimelineMemoDto(index = SlimIndexDto(id = 9, uid = 1, type = 0, title = "目录",
                `private` = false, total = 7, stats = IndexStatsDto(IndexSubjectStatsDto()), createdAt = 0, updatedAt = 0)))
        assertEquals(listOf(TimelineSegmentBo.IndexTotal(7)), index.toTimelineItem().targets.single().segments)

        val progress = TimelineDto(id = 12, uid = 42, cat = 4, type = 0, createdAt = 1_700_000_000,
            memo = TimelineMemoDto(progress = TimelineProgressDto(batch = TimelineProgressBatchDto(
                epsTotal = "12", epsUpdate = 5, volsTotal = "3", volsUpdate = 2,
                subject = P1SlimSubjectDto(8, "A", type = 2),
            ))))
        assertEquals(
            listOf(TimelineSegmentBo.ProgressBatch("12", 5, "3", 2)),
            progress.toTimelineItem().targets.first().segments,
        )
    }

    /**
     * 未知类别：作者与时间仍要兼容回退，原始 memo 仍要保留。
     *
     * **正文不由 mapper 造**——这里断言 `text` 为空、且兜底文案由渲染层负责
     * （未知类别由 core:reader 的 `presentationOf` 兜底画成「暂不支持」一行）。mapper 不塞
     * "此动态类型暂不支持…"这类句子：那是数据层替界面写文案。
     */
    @Test
    fun `未知类别保留作者时间与已有memo且不造正文`() {
        val author = UserBo(42, "canonical", "用户")
        val item = TimelineDto(7, 42, cat = 99, createdAt = 123,
            memo = TimelineMemoDto(status = TimelineStatusDto(tsukkomi = "仍须保留")))
            .toTimelineItem(fallbackUser = author)
        assertEquals(author, item.user)
        assertEquals(123_000, item.createdAt)
        assertEquals("", item.text)
        assertNull(item.reactionTarget)
    }

    @Test
    fun `空作者安全与reaction按当前用户派生`() {
        val item = TimelineDto(8, 42, cat = 5, type = 1,
            memo = TimelineMemoDto(status = TimelineStatusDto(tsukkomi = "A &amp; B")),
            reactions = listOf(ReactionDto(listOf(SimpleUserDto(42, "me", "我")), 1)))
            .toTimelineItem(currentUserId = 42)
        assertEquals(null, item.user)
        assertEquals("A & B", item.text)
        assertEquals(1, item.reactions.single().count)
        assertTrue(item.reactions.single().selected)
        assertEquals(TimelineReactionTarget.Status, item.reactionTarget)
        assertEquals(COMMON_REACTIONS, item.reactionTarget?.palette)
    }

    @Test
    fun `目标关联图像imageUrl正确映射`() {
        val userDto = P1UserDto(1, "fixture_user", "Fixture User", AvatarDto(medium = "https://img/user.jpg"), group = 1, joinedAt = 0, sign = "hello", isFriend = false)
        val characterDto = SlimCharacterDto(2, "char", "角色", 1, "主角", PersonImagesDto("https://img/char.jpg", "https://img/char.jpg", "https://img/char.jpg", "https://img/char.jpg"), 0, false, false)
        val groupDto = SlimGroupDto(3, "grp", false, "猫窝", AvatarDto(large = "https://img/group.jpg"), 1, 10, true, 0)

        val item = TimelineDto(
            id = 99, uid = 42, cat = 1, type = 2, createdAt = 100,
            memo = TimelineMemoDto(
                daily = TimelineDailyDto(users = listOf(userDto), groups = listOf(groupDto)),
                mono = TimelineMonoDto(characters = listOf(characterDto), persons = emptyList()),
            ),
        ).toTimelineItem()

        assertEquals("https://img/user.jpg", item.targets.find { it.title == "Fixture User" }?.imageUrl)
        assertEquals("https://img/group.jpg", item.targets.find { it.title == "猫窝" }?.imageUrl)
        assertEquals("https://img/char.jpg", item.targets.find { it.title == "角色" }?.imageUrl)
    }
}
