package io.github.touhougo.chogumi.core.data.db.mapper

import io.github.touhougo.chogumi.core.model.CastRole
import io.github.touhougo.chogumi.core.model.CommentState
import io.github.touhougo.chogumi.core.model.FactRow
import io.github.touhougo.chogumi.core.model.MonoPersonRefBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.network.model.p1.AvatarDto
import io.github.touhougo.chogumi.core.network.model.p1.CharacterCastDto
import io.github.touhougo.chogumi.core.network.model.p1.CharacterDto
import io.github.touhougo.chogumi.core.network.model.p1.CharacterRelationDto
import io.github.touhougo.chogumi.core.network.model.p1.CharacterSubjectDto
import io.github.touhougo.chogumi.core.network.model.p1.CommentBaseDto
import io.github.touhougo.chogumi.core.network.model.p1.CommentDto
import io.github.touhougo.chogumi.core.network.model.p1.InfoboxItemDto
import io.github.touhougo.chogumi.core.network.model.p1.InfoboxValueDto
import io.github.touhougo.chogumi.core.network.model.p1.P1SlimSubjectDto
import io.github.touhougo.chogumi.core.network.model.p1.P1UserDto
import io.github.touhougo.chogumi.core.network.model.p1.PersonDto
import io.github.touhougo.chogumi.core.network.model.p1.PersonImagesDto
import io.github.touhougo.chogumi.core.network.model.p1.PersonRelationDto
import io.github.touhougo.chogumi.core.network.model.p1.PersonRelationTypeDto
import io.github.touhougo.chogumi.core.network.model.p1.PersonWorkDto
import io.github.touhougo.chogumi.core.network.model.p1.ReactionDto
import io.github.touhougo.chogumi.core.network.model.p1.SimpleUserDto
import io.github.touhougo.chogumi.core.network.model.p1.SlimCharacterDto
import io.github.touhougo.chogumi.core.network.model.p1.SlimPersonDto
import io.github.touhougo.chogumi.core.network.model.p1.SubjectImagesDto
import io.github.touhougo.chogumi.core.network.model.p1.SubjectStaffPositionDto
import io.github.touhougo.chogumi.core.network.model.p1.SubjectStaffPositionTypeDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MonoMapperTest {

    @Test
    fun `Infobox 空列表与异常畸形数据容错`() {
        // 空列表
        assertEquals(emptyList(), emptyList<InfoboxItemDto>().toFactRows())

        // 混合脏数据：空 key、纯空格 key、空 values、全空白 values、正常条目
        val dirtyInfobox = listOf(
            InfoboxItemDto(key = "", values = listOf(InfoboxValueDto(v = "值"))),
            InfoboxItemDto(key = "   ", values = listOf(InfoboxValueDto(v = "值"))),
            InfoboxItemDto(key = "性别", values = emptyList()),
            InfoboxItemDto(key = "血型", values = listOf(InfoboxValueDto(v = ""), InfoboxValueDto(v = "   "))),
            InfoboxItemDto(key = "身高", values = listOf(InfoboxValueDto(v = "165cm"))),
            InfoboxItemDto(
                key = "别名",
                values = listOf(
                    InfoboxValueDto(k = "日文", v = "アーニャ"),
                    InfoboxValueDto(k = "英文", v = "Anya"),
                ),
            ),
        )

        val result = dirtyInfobox.toFactRows()
        assertEquals(2, result.size)
        assertEquals(FactRow(key = "身高", value = "165cm"), result[0])
        assertEquals(FactRow(key = "别名", value = "日文：アーニャ、英文：Anya"), result[1])
    }

    @Test
    fun `Infobox 模版未填占位符过滤与真实样本清洗`() {
        val sample = listOf(
            InfoboxItemDto(
                key = "别名",
                values = listOf(
                    InfoboxValueDto(v = "花ひなこ"),
                    InfoboxValueDto(v = "", k = "第二中文名"),
                    InfoboxValueDto(v = "Naoko Yamada", k = "英文名"),
                    InfoboxValueDto(v = "   ", k = "日文名"),
                ),
            ),
            InfoboxItemDto(
                key = "全空模版",
                values = listOf(
                    InfoboxValueDto(v = "", k = "第二中文名"),
                    InfoboxValueDto(v = "   ", k = "日文名"),
                ),
            ),
        )

        val result = sample.toFactRows()
        assertEquals(1, result.size)
        assertEquals(FactRow(key = "别名", value = "花ひなこ、英文名：Naoko Yamada"), result[0])

        // 仅包含空键名与有值键名的子项清洗单项断言（真实番茄汤场景）
        val tomatoSoupSample = listOf(
            InfoboxItemDto(
                key = "别名",
                values = listOf(
                    InfoboxValueDto(v = "", k = "第二中文名"),
                    InfoboxValueDto(v = "Tomato Soup", k = "英文名"),
                    InfoboxValueDto(v = "", k = "日文名"),
                    InfoboxValueDto(v = "", k = "纯假名"),
                    InfoboxValueDto(v = "", k = "罗马字"),
                    InfoboxValueDto(v = "", k = "昵称"),
                ),
            ),
        )
        val cleanedResult = tomatoSoupSample.toFactRows()
        assertEquals(1, cleanedResult.size)
        assertEquals(FactRow(key = "别名", value = "英文名：Tomato Soup"), cleanedResult[0])
    }

    @Test
    fun `评论 9 态严格映射与敏感数据拦截`() {
        val user = P1UserDto(
            id = 100,
            username = "test_user",
            nickname = "测试昵称",
            avatar = AvatarDto(large = "large.jpg"),
        )

        // 1. 正常状态：0 (正常), 3 (置顶), 4 (合并)
        listOf(0, 3, 4).forEach { normalState ->
            val comment = CommentDto(
                id = 1L,
                mainID = 10L,
                creatorID = 100,
                relatedID = 0L,
                createdAt = 1700000000L,
                content = "正常讨论内容 &amp; 分析",
                state = normalState,
                replies = emptyList(),
                user = user,
            )
            val bo = comment.toCommentItemBo()
            assertEquals(CommentState.NORMAL, bo.state)
            assertEquals("正常讨论内容 & 分析", bo.content)
        }

        // 2. 删除/下沉状态：1, 2, 5, 6, 7
        // 关键安全断言：故意传入非空敏感内容，断言强制清空为 ""，绝不泄露原始内容！
        listOf(1, 2, 5, 6, 7).forEach { deletedState ->
            val dirtyContent = "敏感内容/违规广告/攻击言论 state=$deletedState"
            val comment = CommentDto(
                id = 2L,
                mainID = 10L,
                creatorID = 100,
                relatedID = 0L,
                createdAt = 1700000000L,
                content = dirtyContent,
                state = deletedState,
                replies = emptyList(),
                user = user,
            )
            val bo = comment.toCommentItemBo()
            assertEquals(CommentState.DELETED, bo.state)
            assertEquals("", bo.content)
            assertFalse(bo.content.contains("敏感内容"))
            assertFalse(bo.content.contains("违规广告"))
        }

        // 3. 折叠状态：8
        val foldedComment = CommentDto(
            id = 3L,
            mainID = 10L,
            creatorID = 100,
            relatedID = 0L,
            createdAt = 1700000000L,
            content = "被折叠的内容",
            state = 8,
            replies = emptyList(),
            user = user,
        )
        val foldedBo = foldedComment.toCommentItemBo()
        assertEquals(CommentState.FOLDED, foldedBo.state)
        assertEquals("被折叠的内容", foldedBo.content)

        // 4. 未知状态兜底
        val unknownComment = CommentDto(
            id = 4L,
            mainID = 10L,
            creatorID = 100,
            relatedID = 0L,
            createdAt = 1700000000L,
            content = "未知状态正常内容",
            state = 999,
            replies = emptyList(),
            user = user,
        )
        val unknownBo = unknownComment.toCommentItemBo()
        assertEquals(CommentState.NORMAL, unknownBo.state)
        assertEquals("未知状态正常内容", unknownBo.content)

        // 未知状态且正文为空时降级为空内容
        val unknownEmptyComment = CommentDto(
            id = 5L,
            mainID = 10L,
            creatorID = 100,
            relatedID = 0L,
            createdAt = 1700000000L,
            content = "",
            state = 999,
            replies = emptyList(),
            user = user,
        )
        val unknownEmptyBo = unknownEmptyComment.toCommentItemBo()
        assertEquals(CommentState.NORMAL, unknownEmptyBo.state)
        assertEquals("", unknownEmptyBo.content)
    }

    @Test
    fun `评论楼中楼两层树映射`() {
        val user1 = P1UserDto(id = 1, username = "u1", nickname = "User1", avatar = AvatarDto(large = "a1.jpg"))
        val user2 = P1UserDto(id = 2, username = "u2", nickname = "User2", avatar = AvatarDto(large = "a2.jpg"))

        val commentDto = CommentDto(
            id = 1001L,
            mainID = 500L,
            creatorID = 1,
            relatedID = 0L,
            createdAt = 1700000000L,
            content = "主楼评论",
            state = 0,
            replies = listOf(
                CommentBaseDto(
                    id = 1002L,
                    mainID = 500L,
                    creatorID = 2,
                    relatedID = 1001L,
                    createdAt = 1700000010L,
                    content = "楼中楼回复",
                    state = 0,
                    user = user2,
                    reactions = listOf(
                        ReactionDto(users = listOf(SimpleUserDto(id = 2, username = "u2", nickname = "User2")), value = 1),
                    ),
                ),
                CommentBaseDto(
                    id = 1003L,
                    mainID = 500L,
                    creatorID = 1,
                    relatedID = 1001L,
                    createdAt = 1700000020L,
                    content = "已被删除的楼中楼",
                    state = 6, // 用户删除
                    user = user1,
                ),
            ),
            user = user1,
            reactions = listOf(
                ReactionDto(users = listOf(SimpleUserDto(id = 1, username = "u1", nickname = "User1")), value = 2),
            ),
        )

        val threadBo = commentDto.toCommentThreadBo(currentUserId = 1)
        assertEquals(1001, threadBo.anchor.id)
        assertEquals("主楼评论", threadBo.anchor.content)
        assertEquals(CommentState.NORMAL, threadBo.anchor.state)
        assertEquals(1, threadBo.anchor.reactions.size)
        assertEquals(2, threadBo.anchor.reactions[0].value)
        assertEquals(1, threadBo.anchor.reactions[0].count)
        assertTrue(threadBo.anchor.reactions[0].selected) // currentUserId = 1

        assertEquals(2, threadBo.replies.size)
        val reply1 = threadBo.replies[0]
        assertEquals(1002, reply1.id)
        assertEquals("楼中楼回复", reply1.content)
        assertEquals(CommentState.NORMAL, reply1.state)
        assertFalse(reply1.reactions[0].selected) // currentUserId = 1, user is 2

        val reply2 = threadBo.replies[1]
        assertEquals(1003, reply2.id)
        assertEquals(CommentState.DELETED, reply2.state)
        assertEquals("", reply2.content)
    }

    @Test
    fun `CharacterDto 与 PersonDto 详情映射`() {
        val charDto = CharacterDto(
            id = 10,
            name = "Anya Forger",
            nameCN = "阿尼亚·福杰",
            role = 1,
            infobox = listOf(InfoboxItemDto(key = "性别", values = listOf(InfoboxValueDto(v = "女")))),
            info = "短简介",
            summary = "完整角色描述",
            images = PersonImagesDto(large = "l.jpg", medium = "m.jpg", small = "s.jpg", grid = "g.jpg"),
            comment = 120,
            collects = 500,
            lock = false,
            redirect = 0,
            nsfw = false,
            collectedAt = 1700000000L,
        )
        val charBo = charDto.toBo()
        assertEquals(10, charBo.id)
        assertEquals("Anya Forger", charBo.name)
        assertEquals("阿尼亚·福杰", charBo.nameCn)
        assertEquals("完整角色描述", charBo.summary)
        assertEquals("l.jpg", charBo.image, "立绘取大图")
        assertEquals(emptyList(), charBo.careers)
        assertEquals(1, charBo.infobox.size)
        assertEquals(500, charBo.collectCount)
        assertTrue(charBo.isCollected)

        val personDto = PersonDto(
            id = 20,
            name = "Atsumi Tanezaki",
            nameCN = "种崎敦美",
            type = 1,
            infobox = listOf(InfoboxItemDto(key = "职业", values = listOf(InfoboxValueDto(v = "声优")))),
            info = "短简介",
            career = listOf("seiyu"),
            summary = "人物简介",
            images = PersonImagesDto(large = "pl.jpg", medium = "pm.jpg", small = "ps.jpg", grid = "pg.jpg"),
            comment = 80,
            collects = 300,
            lock = false,
            redirect = 0,
            nsfw = false,
            collectedAt = null,
        )
        val personBo = personDto.toBo()
        assertEquals(20, personBo.id)
        assertEquals("Atsumi Tanezaki", personBo.name)
        assertEquals("种崎敦美", personBo.nameCn)
        assertEquals(listOf("seiyu"), personBo.careers)
        assertEquals("人物简介", personBo.summary)
        assertEquals(1, personBo.infobox.size)
        assertEquals("pl.jpg", personBo.image)
        assertEquals(300, personBo.collectCount)
        assertFalse(personBo.isCollected)
    }

    @Test
    fun `出演作品、参与作品与关联角色人物映射`() {
        val castDto = CharacterSubjectDto(
            subject = P1SlimSubjectDto(
                id = 101,
                name = "SPY×FAMILY",
                nameCN = "间谍过家家",
                type = 2,
                images = SubjectImagesDto(large = "sl.jpg", common = "sc.jpg", medium = "sm.jpg", small = "ss.jpg", grid = "sg.jpg"),
            ),
            casts = listOf(
                CharacterCastDto(
                    person = SlimPersonDto(
                        id = 201,
                        name = "Atsumi Tanezaki",
                        nameCN = "种崎敦美",
                        type = 1,
                        info = "",
                        career = listOf("seiyu"),
                        comment = 0,
                        lock = false,
                        nsfw = false,
                    ),
                    relation = 1,
                    summary = "CV",
                ),
            ),
            type = 2,
        )
        val castBo = castDto.toBo()
        assertEquals(101, castBo.id)
        assertEquals("SPY×FAMILY", castBo.name)
        assertEquals("间谍过家家", castBo.nameCn)
        assertEquals("sl.jpg", castBo.image)
        assertEquals(SubjectType.ANIME, castBo.subjectType)
        // 每项的 type 是角色在该作品里的定位：2 = 配角；声优带 id，头像只用方图（这里没给图 → null）
        assertEquals(CastRole.SUPPORTING, castBo.role)
        assertEquals(listOf(MonoPersonRefBo(id = 201, name = "种崎敦美", avatarUrl = null)), castBo.actors)
        assertEquals(emptyList(), castBo.positions)

        val workDto = PersonWorkDto(
            subject = P1SlimSubjectDto(
                id = 102,
                name = "Frieren",
                nameCN = "葬送的芙莉莲",
                type = 2,
                images = SubjectImagesDto(large = "fl.jpg", common = "fc.jpg", medium = "fm.jpg", small = "fs.jpg", grid = "fg.jpg"),
            ),
            positions = listOf(
                SubjectStaffPositionDto(
                    type = SubjectStaffPositionTypeDto(id = 1, en = "Main", cn = "主角", jp = "主人公"),
                    summary = "",
                    appearEps = "",
                ),
            ),
        )
        val workBo = workDto.toBo()
        assertEquals(102, workBo.id)
        assertEquals("Frieren", workBo.name)
        assertEquals("葬送的芙莉莲", workBo.nameCn)
        assertEquals(listOf("主角"), workBo.positions)
        assertEquals(null, workBo.role, "人物作品不带定位")
        assertEquals(emptyList(), workBo.actors)

        val relationDto = CharacterRelationDto(
            character = SlimCharacterDto(
                id = 11,
                name = "Loid Forger",
                nameCN = "洛伊德·福杰",
                role = 1,
                info = "",
                images = PersonImagesDto(large = "ll.jpg", medium = "lm.jpg", small = "ls.jpg", grid = "lg.jpg"),
                comment = 0,
                lock = false,
                nsfw = false,
            ),
            relation = PersonRelationTypeDto(id = 1, cn = "养父", desc = "Father"),
            spoiler = false,
            ended = false,
            comment = "",
        )
        val relBo = relationDto.toBo()
        assertEquals(11, relBo.id)
        assertEquals("Loid Forger", relBo.name)
        assertEquals("洛伊德·福杰", relBo.nameCn)
        assertEquals("养父", relBo.relation)
        assertEquals("lm.jpg", relBo.image)

        val personRelDto = PersonRelationDto(
            person = SlimPersonDto(
                id = 22,
                name = "Takuya Eguchi",
                nameCN = "江口拓也",
                type = 1,
                info = "",
                career = listOf("seiyu"),
                images = PersonImagesDto(large = "el.jpg", medium = "em.jpg", small = "es.jpg", grid = "eg.jpg"),
                comment = 0,
                lock = false,
                nsfw = false,
            ),
            relation = PersonRelationTypeDto(id = 2, cn = "合作", desc = "Co-worker"),
            spoiler = false,
            ended = false,
            comment = "",
        )
        val personRelBo = personRelDto.toBo()
        assertEquals(22, personRelBo.id)
        assertEquals("Takuya Eguchi", personRelBo.name)
        assertEquals("江口拓也", personRelBo.nameCn)
        assertEquals("合作", personRelBo.relation)
        assertEquals("em.jpg", personRelBo.image)
    }

    @Test
    fun `关联角色与人物头像取整张 medium 由界面顶对齐裁圆`() {
        // p1 的 grid 是 `/r/100x100/` 居中裁的方块，全身立绘会切掉头（PersonImageTest 的实测表）；
        // 头像取整张的 medium，CircleAvatar 顶对齐裁圆。
        val characterRel = CharacterRelationDto(
            character = SlimCharacterDto(
                id = 7,
                name = "Grid Character",
                nameCN = "方图角色",
                role = 1,
                info = "",
                images = PersonImagesDto(large = "cl.jpg", medium = "cm.jpg", small = "cs.jpg", grid = "cg.jpg"),
                comment = 0,
                lock = false,
                nsfw = false,
            ),
            relation = PersonRelationTypeDto(id = 1, cn = "同事", desc = "Colleague"),
            spoiler = false,
            ended = false,
            comment = "",
        )
        assertEquals("cm.jpg", characterRel.toBo().image, "取整张的 medium，不取居中裁的 grid")

        val characterGridOnly = characterRel.copy(
            character = characterRel.character.copy(
                images = PersonImagesDto(large = "", medium = "", small = "", grid = "cg.jpg"),
            ),
        )
        assertEquals("cg.jpg", characterGridOnly.toBo().image, "整图都缺时退到 grid，总比空白强")

        val personRel = PersonRelationDto(
            person = SlimPersonDto(
                id = 8,
                name = "Grid Person",
                nameCN = "方图人物",
                type = 1,
                info = "",
                career = listOf("seiyu"),
                images = PersonImagesDto(large = "pl.jpg", medium = "pm.jpg", small = "ps.jpg", grid = "pg.jpg"),
                comment = 0,
                lock = false,
                nsfw = false,
            ),
            relation = PersonRelationTypeDto(id = 2, cn = "合作", desc = "Co-worker"),
            spoiler = false,
            ended = false,
            comment = "",
        )
        assertEquals("pm.jpg", personRel.toBo().image, "人物关联同样取整张的 medium")

        val personNoImage = personRel.copy(
            person = personRel.person.copy(
                images = PersonImagesDto(large = "", medium = "", small = "", grid = ""),
            ),
        )
        assertEquals(null, personNoImage.toBo().image, "四档都空时返回 null，由界面画占位底色")
    }

    @Test
    fun `同一角色的两条关系各自映射成关系文案`() {
        val character = SlimCharacterDto(
            id = 18159,
            name = "Anya Forger",
            nameCN = "阿尼亚·福杰",
            role = 1,
            info = "",
            images = PersonImagesDto(large = "large.jpg", medium = "med.jpg", small = "sm.jpg", grid = "grid.jpg"),
            comment = 0,
            lock = false,
            nsfw = false,
        )
        val rel1 = CharacterRelationDto(
            character = character,
            relation = PersonRelationTypeDto(id = 101, cn = "同事", desc = "Colleague"),
            spoiler = false,
            ended = false,
            comment = "",
        )
        val rel2 = CharacterRelationDto(
            character = character,
            relation = PersonRelationTypeDto(id = 102, cn = "朋友", desc = "Friend"),
            spoiler = false,
            ended = false,
            comment = "",
        )

        val bo1 = rel1.toBo()
        val bo2 = rel2.toBo()

        assertEquals(18159, bo1.id)
        assertEquals(18159, bo2.id)
        assertEquals("同事", bo1.relation)
        assertEquals("朋友", bo2.relation)
    }

    @Test
    fun `同一人物的两条关系各自映射成关系文案`() {
        val person = SlimPersonDto(
            id = 20001,
            name = "Test Person",
            nameCN = "测试人物",
            type = 1,
            info = "",
            career = listOf("director"),
            images = null,
            comment = 0,
            lock = false,
            nsfw = false,
        )
        val rel1 = PersonRelationDto(
            person = person,
            relation = PersonRelationTypeDto(id = 201, cn = "同事", desc = "Colleague"),
            spoiler = false,
            ended = false,
            comment = "",
        )
        val rel2 = PersonRelationDto(
            person = person,
            relation = PersonRelationTypeDto(id = 202, cn = "朋友", desc = "Friend"),
            spoiler = false,
            ended = false,
            comment = "",
        )

        val bo1 = rel1.toBo()
        val bo2 = rel2.toBo()

        assertEquals(20001, bo1.id)
        assertEquals(20001, bo2.id)
        assertEquals("同事", bo1.relation)
        assertEquals("朋友", bo2.relation)
    }

    @Test
    fun `作品项带上评分与从 info 解析的年月，供作品排序使用`() {
        val subject = P1SlimSubjectDto(
            id = 544020, name = "w", nameCN = "", type = 2,
            info = "15话 / 2025年12月10日 / 初見浩一",
            rating = io.github.touhougo.chogumi.core.network.model.p1.P1RatingDto(score = 5.75, total = 510),
        )
        val work = PersonWorkDto(subject = subject, positions = emptyList()).toBo()
        assertEquals(5.75, work.ratingScore)
        assertEquals(510, work.ratingTotal)
        assertEquals(io.github.touhougo.chogumi.core.model.SubjectYearMonth(2025, 12), work.date)

        val cast = CharacterSubjectDto(subject = subject.copy(info = "没有日期"), casts = emptyList(), type = 1).toBo()
        assertEquals(510, cast.ratingTotal)
        assertEquals(null, cast.date)
    }
}
