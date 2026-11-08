package io.github.touhougo.chogumi.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionEditorTest {

    /** 正片 sort 为 [sorts]，外加一集 SP。 */
    private fun episodes(sorts: IntRange): List<EpisodeBo> = sorts.map { sort ->
        EpisodeBo(id = 1000 + sort, subjectId = 8, type = EpisodeType.NORMAL, name = "ep$sort", nameCn = "", sort = sort.toDouble(), airdate = "2026-01-01")
    } + EpisodeBo(id = 9000, subjectId = 8, type = EpisodeType.SPECIAL, name = "sp", nameCn = "", sort = 999.0, airdate = "2026-01-01")

    private fun watched(sorts: IntRange): Map<Int, EpisodeStatus> = sorts.associate { (1000 + it) to EpisodeStatus.DONE }

    private val doing = UserCollectionBo(
        subjectId = 8, subjectType = SubjectType.ANIME, type = CollectionType.DOING,
        rate = 7, tags = listOf("原标签"), epStatus = 5, comment = "原吐槽", isPrivate = false,
    )

    /** 12 集正片的动画，看到第 5 集。 */
    private fun anime(collection: UserCollectionBo? = doing, sorts: IntRange = 1..12, watchedUpTo: Int = 5) = CollectionEditor(
        subjectType = SubjectType.ANIME,
        episodes = episodes(sorts),
        watchStates = watched(sorts.first..watchedUpTo),
        totalChapters = 0,
        totalVolumes = 0,
        collection = collection,
    )

    private fun book(chapters: Int, volumes: Int, totalChapters: Int, totalVolumes: Int) = CollectionEditor(
        subjectType = SubjectType.BOOK,
        episodes = emptyList(),
        watchStates = emptyMap(),
        totalChapters = totalChapters,
        totalVolumes = totalVolumes,
        collection = doing.copy(subjectType = SubjectType.BOOK, epStatus = chapters, volStatus = volumes),
    )

    @Test
    fun `动画的进度是看到第几集 从逐集状态取 不取收藏记录里的计数`() {
        // 分季条目：正片从 202 集起，看到第 206 集；收藏记录里的计数是 5
        val editor = anime(sorts = 202..252, watchedUpTo = 206)
        assertEquals(CollectionProgress.Episodes(EpisodeProgressTarget.Episode(1206, 206.0)), editor.saved.progress)
    }

    @Test
    fun `进度区按规格表 书籍记话卷 游戏音乐没有进度`() {
        assertEquals(CollectionProgress.Book(30, 3), book(chapters = 30, volumes = 3, totalChapters = 0, totalVolumes = 0).saved.progress)
        listOf(SubjectType.GAME, SubjectType.MUSIC).forEach { type ->
            val editor = CollectionEditor(type, emptyList(), emptyMap(), 0, 0, doing.copy(subjectType = type))
            assertEquals(CollectionProgress.None, editor.saved.progress)
            assertFalse(editor.showsProgress(editor.saved))
        }
    }

    @Test
    fun `没动的项跟着已保存的收藏走`() {
        val edits = CollectionEdits(rate = 9)
        // 面板开着时网页上改了吐槽：没动的吐槽照新的显示，保存也不发
        val editor = anime(collection = doing.copy(comment = "网页上改的"))
        assertEquals("网页上改的", editor.formOf(edits).comment)
        assertEquals(CollectionModify(rate = 9), editor.saveOf(edits).modify)
    }

    @Test
    fun `改没改看表和已保存的一不一样 一起标完的开关不算`() {
        val editor = anime()
        assertFalse(editor.isDirty(CollectionEdits()))
        assertFalse(editor.isDirty(CollectionEdits(rate = 7, finishAll = false)))
        assertTrue(editor.isDirty(CollectionEdits(rate = 8)))
        assertTrue(editor.isDirty(CollectionEdits(progress = CollectionProgress.Episodes(EpisodeProgressTarget.Episode(1006, 6.0)))))
    }

    @Test
    fun `还没收藏时保存就是新建 总有可保存的 整张表照发但不发零进度`() {
        val editor = anime(collection = null, watchedUpTo = 0)
        assertEquals(CollectionType.WISH, editor.saved.type)
        assertTrue(editor.isDirty(CollectionEdits()))
        assertEquals(
            CollectionModify(type = CollectionType.WISH, rate = 0, comment = "", private = false, tags = emptyList()),
            editor.saveOf(CollectionEdits()).modify,
        )
    }

    @Test
    fun `动画改了进度 保存时看到第几集是正片的集号`() {
        val editor = anime(sorts = 202..252, watchedUpTo = 206)
        val save = editor.saveOf(CollectionEdits(progress = CollectionProgress.Episodes(EpisodeProgressTarget.Episode(1210, 210.0))))
        assertEquals(EpisodeProgressTarget.Episode(1210, 210.0), save.watchUpTo)
        assertEquals("动画的进度不进收藏记录（服务端只许书籍这样改）", CollectionModify(), save.modify)
    }

    @Test
    fun `进度拖回原处不写`() {
        assertNull(anime().saveOf(CollectionEdits(progress = CollectionProgress.Episodes(EpisodeProgressTarget.Episode(1005, 5.0)), rate = 8)).watchUpTo)
    }

    @Test
    fun `退到一集都没看保存显式空进度目标`() {
        assertEquals(EpisodeProgressTarget.Empty, anime().saveOf(CollectionEdits(progress = CollectionProgress.Episodes(EpisodeProgressTarget.Empty))).watchUpTo)
    }

    @Test
    fun `看到最后一集还不是看过 建议标为看过`() {
        val editor = anime()
        assertFalse(editor.suggestsDone(editor.saved))
        val atEnd = editor.formOf(CollectionEdits(progress = CollectionProgress.Episodes(EpisodeProgressTarget.Episode(1012, 12.0))))
        assertTrue(editor.suggestsDone(atEnd))
        assertFalse(editor.suggestsDone(atEnd.copy(type = CollectionType.DONE)))
    }

    @Test
    fun `选看过且没看完 问要不要一起标完 默认标到最后一集`() {
        val editor = anime()
        val edits = CollectionEdits(type = CollectionType.DONE)
        assertTrue(editor.offersFinishAll(editor.formOf(edits)))
        assertEquals(EpisodeProgressTarget.Episode(1012, 12.0), editor.saveOf(edits).watchUpTo)
        assertEquals(CollectionModify(type = CollectionType.DONE), editor.saveOf(edits).modify)

        val unwatched = anime(watchedUpTo = 0)
        assertEquals(CollectionProgress.Episodes(EpisodeProgressTarget.Empty), unwatched.saved.progress)
        assertTrue(unwatched.offersFinishAll(unwatched.formOf(edits)))
        assertEquals(EpisodeProgressTarget.Episode(1012, 12.0), unwatched.saveOf(edits).watchUpTo)
    }

    @Test
    fun `不一起标完时只写改过的进度`() {
        val editor = anime()
        assertNull(editor.saveOf(CollectionEdits(type = CollectionType.DONE, finishAll = false)).watchUpTo)
        assertEquals(EpisodeProgressTarget.Episode(1008, 8.0), editor.saveOf(CollectionEdits(type = CollectionType.DONE, finishAll = false, progress = CollectionProgress.Episodes(EpisodeProgressTarget.Episode(1008, 8.0)))).watchUpTo)
    }

    @Test
    fun `已经看完或章节还没到时不问一起标完`() {
        val done = anime(watchedUpTo = 12)
        assertFalse(done.offersFinishAll(done.formOf(CollectionEdits(type = CollectionType.DONE))))
        val loading = CollectionEditor(SubjectType.ANIME, emptyList(), emptyMap(), 0, 0, doing)
        val form = loading.formOf(CollectionEdits(type = CollectionType.DONE))
        assertFalse(loading.offersFinishAll(form))
        assertNull("章节没到：不写进度", loading.saveOf(CollectionEdits(type = CollectionType.DONE)).watchUpTo)
    }

    @Test
    fun `想看不显示进度区 改过的进度不跟着存`() {
        val editor = anime()
        val edits = CollectionEdits(type = CollectionType.WISH, progress = CollectionProgress.Episodes(EpisodeProgressTarget.Episode(1009, 9.0)))
        assertFalse(editor.showsProgress(editor.formOf(edits)))
        assertNull(editor.saveOf(edits).watchUpTo)
    }

    @Test
    fun `书籍的话卷数随收藏记录写 只发改了的`() {
        val editor = book(chapters = 30, volumes = 3, totalChapters = 0, totalVolumes = 0)
        val save = editor.saveOf(CollectionEdits(progress = CollectionProgress.Book(31, 3)))
        assertEquals(CollectionModify(epStatus = 31), save.modify)
        assertNull(save.watchUpTo)
    }

    @Test
    fun `书籍标看过一起标完 写总话数与总卷数 未知的那项不动`() {
        assertEquals(
            CollectionModify(type = CollectionType.DONE, epStatus = 120, volStatus = 12),
            book(chapters = 30, volumes = 3, totalChapters = 120, totalVolumes = 12).saveOf(CollectionEdits(type = CollectionType.DONE)).modify,
        )
        assertEquals(
            CollectionModify(type = CollectionType.DONE, volStatus = 12),
            book(chapters = 30, volumes = 3, totalChapters = 0, totalVolumes = 12).saveOf(CollectionEdits(type = CollectionType.DONE)).modify,
        )
    }

    @Test
    fun `书籍读到最后一话才算到头 话数未知看卷数 都未知不算`() {
        val known = book(chapters = 120, volumes = 3, totalChapters = 120, totalVolumes = 12)
        assertTrue(known.suggestsDone(known.saved))
        val byVolume = book(chapters = 0, volumes = 12, totalChapters = 0, totalVolumes = 12)
        assertTrue(byVolume.suggestsDone(byVolume.saved))
        val unknown = book(chapters = 500, volumes = 50, totalChapters = 0, totalVolumes = 0)
        assertFalse(unknown.suggestsDone(unknown.saved))
        assertFalse(unknown.offersFinishAll(unknown.formOf(CollectionEdits(type = CollectionType.DONE))))
    }

    @Test
    fun `进度尺第几格与集号互换 0 格是一集都没看`() {
        val editor = anime(sorts = 202..252, watchedUpTo = 206)
        assertEquals(5, editor.episodeIndexOf(CollectionProgress.Episodes(EpisodeProgressTarget.Episode(1206, 206.0))))
        assertEquals(0, editor.episodeIndexOf(CollectionProgress.Episodes(EpisodeProgressTarget.Empty)))
        assertEquals(CollectionProgress.Episodes(EpisodeProgressTarget.Episode(1206, 206.0)), editor.episodesAt(5))
        assertEquals(CollectionProgress.Episodes(EpisodeProgressTarget.Empty), editor.episodesAt(0))
    }

    @Test
    fun `进度尺把 sort 为零的真实正片与空进度分开`() {
        val editor = anime(sorts = 0..2, watchedUpTo = -1)

        assertEquals(CollectionProgress.Episodes(EpisodeProgressTarget.Empty), editor.saved.progress)
        assertEquals(1, editor.episodeIndexOf(CollectionProgress.Episodes(EpisodeProgressTarget.Episode(1000, 0.0))))
        assertEquals(CollectionProgress.Episodes(EpisodeProgressTarget.Episode(1000, 0.0)), editor.episodesAt(1))
        assertNotEquals(editor.episodesAt(0), editor.episodesAt(1))
        assertEquals(2, editor.episodeIndexOf(CollectionProgress.Episodes(EpisodeProgressTarget.Episode(1001, 1.0))))
        assertEquals(CollectionProgress.Episodes(EpisodeProgressTarget.Episode(1001, 1.0)), editor.episodesAt(2))
    }

    @Test
    fun `进度尺拖到最后一集已播出的正片 播出日不明按已播出`() {
        val today = java.time.LocalDate.of(2026, 10, 12)
        val episodes = (1..4).map { sort ->
            EpisodeBo(
                id = 1000 + sort, subjectId = 8, type = EpisodeType.NORMAL, name = "ep$sort", nameCn = "", sort = sort.toDouble(),
                airdate = when (sort) {
                    1 -> "2026-10-01"
                    2 -> ""
                    else -> "2026-10-20"
                },
            )
        }
        assertEquals(2, CollectionEditor(SubjectType.ANIME, episodes, emptyMap(), 0, 0, doing).airedUpTo(today))
        assertEquals(0, CollectionEditor(SubjectType.ANIME, emptyList(), emptyMap(), 0, 0, doing).airedUpTo(today))
    }

    @Test
    fun `标签按空白拆开 全角空格也算 与服务端一样做 NFKC 归一`() {
        assertEquals(listOf("热血", "冒险"), CollectionInput.tagsIn(" 热血 冒险 "))
        assertEquals(listOf("热血", "冒险"), CollectionInput.tagsIn("热血　冒险"))
        assertEquals(listOf("ONEPIECE"), CollectionInput.tagsIn("ＯＮＥＰＩＥＣＥ"))
        assertEquals(emptyList<String>(), CollectionInput.tagsIn("  "))
    }

    @Test
    fun `标签至少两个字 不重复 最多十个`() {
        assertEquals(CollectionInput.TagProblem.TOO_SHORT, CollectionInput.tagProblem(emptyList(), "神"))
        assertNull(CollectionInput.tagProblem(emptyList(), "神作"))
        assertEquals(CollectionInput.TagProblem.DUPLICATE, CollectionInput.tagProblem(listOf("神作"), "神作"))
        val full = (1..10).map { "标签$it" }
        assertEquals(CollectionInput.TagProblem.FULL, CollectionInput.tagProblem(full, "再来一个"))
    }

    @Test
    fun `字数按字符算 一个表情是一个字`() {
        assertEquals(1, CollectionInput.length("😀"))
        assertEquals(CollectionInput.TagProblem.TOO_SHORT, CollectionInput.tagProblem(emptyList(), "😀"))
        assertEquals(3, CollectionInput.length("看完😀"))
    }
}
