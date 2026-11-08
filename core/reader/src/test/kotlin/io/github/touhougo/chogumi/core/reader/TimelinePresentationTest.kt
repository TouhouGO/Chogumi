package io.github.touhougo.chogumi.core.reader

import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TimelineItemBo
import io.github.touhougo.chogumi.core.model.TimelineSegmentBo
import io.github.touhougo.chogumi.core.model.TimelineTargetBo
import io.github.touhougo.chogumi.core.res.R
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [presentationOf] 是全仓唯一按大类（`timelineCategoryOf(cat)`）分支画时间线的地方：每个 cat / type 组合各一条，未知 cat 落到兜底。
 * cat 是服务端给时间线动态分的大类，type 是大类下的具体动作，编号含义注在 [presentationOf] 的各分支旁。
 */
class TimelinePresentationTest {

    private fun assertPresentation(
        cat: Int,
        type: Int,
        filter: TimelineFilter?,
        shape: TimelineShape,
        verbRes: Int,
        tone: VerbTone = VerbTone.NORMAL,
        batch: Boolean = false,
        subjectType: SubjectType? = null,
    ) {
        val p = presentationOf(cat, type, batch, subjectType)
        assertEquals(filter, p.filter, "cat=$cat type=$type 的筛选项")
        assertEquals(shape, p.shape, "cat=$cat type=$type 的版式")
        assertEquals(verbRes, p.verbRes, "cat=$cat type=$type 的动词")
        assertEquals(tone, p.tone, "cat=$cat type=$type 的胶囊配色")
        assertTrue(p.visible, "cat=$cat type=$type 应展示")
    }

    @Test
    fun `进度 0 批量的书籍为读到`() = assertPresentation(
        4, 0, TimelineFilter.PROGRESS, TimelineShape.SUBJECT, R.string.timeline_verb_read_to, subjectType = SubjectType.BOOK,
    )

    @Test
    fun `进度 0 批量的动画、三次元与缺作品时为看到`() {
        for (subjectType in listOf(SubjectType.ANIME, SubjectType.REAL, null)) {
            assertPresentation(
                4, 0, TimelineFilter.PROGRESS, TimelineShape.SUBJECT, R.string.timeline_verb_watch_to, subjectType = subjectType,
            )
        }
    }

    @Test
    fun `一条批量进度的动词按它的作品类型取`() {
        // 实测：动画「看到」也记成批量进度（cat 4 type 0），epsUpdate 是话数、volsUpdate 是 0
        fun progress(type: SubjectType) = TimelineItemBo(
            id = 1, uid = 1, user = null, cat = 4, type = 0, createdAt = 0, text = "",
            targets = listOf(
                TimelineTargetBo(
                    title = "水星领航员", url = "",
                    subject = SubjectBo(id = 1, type = type, name = "ARIA", nameCn = "水星领航员"),
                    segments = listOf(TimelineSegmentBo.ProgressBatch("13", 13, "??", 0)),
                ),
            ),
        )
        assertEquals(R.string.timeline_verb_watch_to, presentationOf(progress(SubjectType.ANIME)).verbRes)
        assertEquals(R.string.timeline_verb_read_to, presentationOf(progress(SubjectType.BOOK)).verbRes)
    }

    @Test
    fun `进度 1 为想看而不是看过`() =
        assertPresentation(4, 1, TimelineFilter.PROGRESS, TimelineShape.SUBJECT, R.string.timeline_verb_wish)

    @Test
    fun `进度 2 为看过`() =
        assertPresentation(4, 2, TimelineFilter.PROGRESS, TimelineShape.SUBJECT, R.string.timeline_verb_watched)

    @Test
    fun `进度 3 为抛弃且胶囊置灰`() = assertPresentation(
        4, 3, TimelineFilter.PROGRESS, TimelineShape.SUBJECT, R.string.timeline_verb_dropped, VerbTone.MUTED,
    )

    @Test
    fun `收藏 1 到 14 各有动词与配色`() {
        val expected = mapOf(
            1 to (R.string.timeline_collect_wish_book to VerbTone.NORMAL),
            2 to (R.string.timeline_collect_wish_anime to VerbTone.NORMAL),
            3 to (R.string.timeline_collect_wish_music to VerbTone.NORMAL),
            4 to (R.string.timeline_collect_wish_game to VerbTone.NORMAL),
            5 to (R.string.timeline_collect_done_book to VerbTone.DONE),
            6 to (R.string.timeline_collect_done_anime to VerbTone.DONE),
            7 to (R.string.timeline_collect_done_music to VerbTone.DONE),
            8 to (R.string.timeline_collect_done_game to VerbTone.DONE),
            9 to (R.string.timeline_collect_doing_book to VerbTone.NORMAL),
            10 to (R.string.timeline_collect_doing_anime to VerbTone.NORMAL),
            11 to (R.string.timeline_collect_doing_music to VerbTone.NORMAL),
            12 to (R.string.timeline_collect_doing_game to VerbTone.NORMAL),
            13 to (R.string.timeline_collect_on_hold to VerbTone.MUTED),
            14 to (R.string.timeline_collect_dropped to VerbTone.MUTED),
        )
        expected.forEach { (type, verbAndTone) ->
            assertPresentation(3, type, TimelineFilter.COLLECT, TimelineShape.SUBJECT, verbAndTone.first, verbAndTone.second)
            assertNull(presentationOf(3, type).batchCountRes, "单部收藏没有计数文案")
        }
    }

    @Test
    fun `收藏未知 type 落到更新了收藏`() =
        assertPresentation(3, 99, TimelineFilter.COLLECT, TimelineShape.SUBJECT, R.string.timeline_collect_unknown)

    @Test
    fun `一次收藏多部画成横排海报且计数单位随条目类型`() {
        val units = mapOf(
            1 to R.plurals.timeline_batch_count_book,
            2 to R.plurals.timeline_batch_count_anime,
            3 to R.plurals.timeline_batch_count_music,
            4 to R.plurals.timeline_batch_count_game,
            6 to R.plurals.timeline_batch_count_anime,
            12 to R.plurals.timeline_batch_count_game,
        )
        units.forEach { (type, unit) ->
            val p = presentationOf(3, type, batch = true)
            assertEquals(TimelineShape.STRIP, p.shape)
            assertEquals(unit, p.batchCountRes, "type=$type 的计数单位")
        }
    }

    @Test
    fun `状态 1 吐槽画成正文、归吐槽`() =
        assertPresentation(5, 1, TimelineFilter.STATUS, TimelineShape.TEXT, R.string.timeline_verb_tsukkomi)

    @Test
    fun `状态 0 改签名与 2 改昵称画成一行、也归吐槽`() {
        assertPresentation(5, 0, TimelineFilter.STATUS, TimelineShape.LINE, R.string.timeline_verb_sign)
        assertPresentation(5, 2, TimelineFilter.STATUS, TimelineShape.LINE, R.string.timeline_verb_nickname)
    }

    @Test
    fun `日志与目录画成标题卡并各带图标`() {
        assertPresentation(6, 1, TimelineFilter.BLOG, TimelineShape.TITLE_CARD, R.string.timeline_verb_blog)
        assertEquals(R.drawable.article_24, presentationOf(6, 1).iconRes)
        assertPresentation(7, 1, TimelineFilter.INDEX, TimelineShape.TITLE_CARD, R.string.timeline_verb_index)
        assertEquals(R.drawable.list_alt_24, presentationOf(7, 1).iconRes)
    }

    @Test
    fun `人物 0 创建与 1 收藏`() {
        assertPresentation(8, 0, TimelineFilter.MONO, TimelineShape.MONO, R.string.timeline_verb_mono_created)
        assertPresentation(8, 1, TimelineFilter.MONO, TimelineShape.MONO, R.string.timeline_verb_mono_collected)
    }

    @Test
    fun `日常 2 加好友画成头像叠放`() =
        assertPresentation(1, 2, TimelineFilter.FRIEND, TimelineShape.AVATARS, R.string.timeline_verb_add_friend)

    @Test
    fun `日常 3 加入与 4 创建小组画成标题卡`() {
        assertPresentation(1, 3, TimelineFilter.GROUP, TimelineShape.TITLE_CARD, R.string.timeline_verb_join_group)
        assertPresentation(1, 4, TimelineFilter.GROUP, TimelineShape.TITLE_CARD, R.string.timeline_verb_create_group)
        assertEquals(R.drawable.group_24, presentationOf(1, 3).iconRes)
    }

    @Test
    fun `日常 0 1 5 不属于任何类别并画成一行`() {
        assertPresentation(1, 0, null, TimelineShape.LINE, R.string.timeline_verb_mystery)
        assertPresentation(1, 1, null, TimelineShape.LINE, R.string.timeline_verb_register)
        assertPresentation(1, 5, null, TimelineShape.LINE, R.string.timeline_verb_join_eden)
    }

    @Test
    fun `维基画成作品行且胶囊置灰`() = assertPresentation(
        2, 2, TimelineFilter.WIKI, TimelineShape.SUBJECT, R.string.timeline_verb_new_subject, VerbTone.MUTED,
    )

    @Test
    fun `天窗不展示`() {
        val p = presentationOf(9, 0)
        assertFalse(p.visible)
        assertNull(p.filter)
    }

    @Test
    fun `未知 cat 落到兜底且照常展示`() {
        val p = presentationOf(42, 0)
        assertNull(p.filter)
        assertEquals(TimelineShape.LINE, p.shape)
        assertEquals(R.string.timeline_verb_unsupported, p.verbRes)
        assertTrue(p.visible)
    }

    @Test
    fun `类别只列出同类动态，全部列出一切，不展示的哪一类都不列`() {
        val blog = TimelineItemBo(
            id = 1, uid = 1, user = null, cat = 6, type = 1, createdAt = 0, text = "",
            targets = listOf(TimelineTargetBo(title = "日志", url = "")),
        )
        val progress = blog.copy(cat = 4, type = 2)
        val doujin = blog.copy(cat = 9, type = 1)
        assertTrue(TimelineFilter.PROGRESS.accepts(progress))
        assertFalse(TimelineFilter.PROGRESS.accepts(blog))
        assertTrue(TimelineFilter.BLOG.accepts(blog))
        assertFalse(TimelineFilter.COLLECT.accepts(progress))
        assertTrue(TimelineFilter.ALL.accepts(blog))
        assertTrue(TimelineFilter.ALL.accepts(progress))
        assertFalse(TimelineFilter.ALL.accepts(doujin))
    }

    @Test
    fun `类别与网页端动态分类同序，每一类都有动态归它`() {
        assertEquals(
            listOf(
                TimelineFilter.ALL, TimelineFilter.STATUS, TimelineFilter.COLLECT, TimelineFilter.PROGRESS,
                TimelineFilter.BLOG, TimelineFilter.MONO, TimelineFilter.FRIEND, TimelineFilter.GROUP,
                TimelineFilter.WIKI, TimelineFilter.INDEX,
            ),
            TimelineFilter.entries,
        )
        val owned = (1..9).flatMap { cat -> (0..14).map { type -> presentationOf(cat, type).filter } }.toSet()
        assertEquals(TimelineFilter.entries.toSet() - TimelineFilter.ALL, owned.filterNotNull().toSet())
    }

    @Test
    fun `批量进度一个大于 0 的话数卷数都没有时不展示，有任一进度照常展示`() {
        // 实测：新收藏书籍时一并提交的 0 进度，服务端记成 epsUpdate = 0、volsUpdate = 0、总数「??」
        fun progress(eps: Int?, vols: Int?) = TimelineItemBo(
            id = 1, uid = 1, user = null, cat = 4, type = 0, createdAt = 0, text = "",
            targets = listOf(
                TimelineTargetBo(
                    title = "学生会也有洞！", url = "",
                    segments = listOf(TimelineSegmentBo.ProgressBatch("??", eps, "??", vols)),
                ),
            ),
        )
        assertFalse(presentationOf(progress(0, 0)).visible)
        assertFalse(presentationOf(progress(null, 0)).visible)
        assertTrue(presentationOf(progress(3, 0)).visible)
        assertTrue(presentationOf(progress(0, 1)).visible)
        // 单集进度（cat 4 type 2）不受影响
        assertTrue(presentationOf(progress(0, 0).copy(type = 2, targets = listOf(TimelineTargetBo(title = "第 1 话", url = "")))).visible)
    }

    @Test
    fun `要画目标的版式没有目标时不展示，吐槽与单行照常展示`() {
        // 实测：日志删了或设成私密后，这条动态的 memo 是空对象
        val hollowBlog = TimelineItemBo(id = 1, uid = 1, user = null, cat = 6, type = 1, createdAt = 0, text = "")
        assertFalse(presentationOf(hollowBlog).visible)
        assertFalse(TimelineFilter.BLOG.accepts(hollowBlog))
        val blog = hollowBlog.copy(targets = listOf(TimelineTargetBo(title = "相反的你和我", url = "")))
        assertTrue(presentationOf(blog).visible)
        assertTrue(presentationOf(hollowBlog.copy(cat = 5, type = 1, text = "吐槽")).visible)
        assertTrue(presentationOf(hollowBlog.copy(cat = 1, type = 1)).visible)
    }
}
