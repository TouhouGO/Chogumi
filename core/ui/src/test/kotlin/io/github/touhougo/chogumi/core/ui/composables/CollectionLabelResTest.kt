package io.github.touhougo.chogumi.core.ui.composables

import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.res.R
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 收藏状态文案的两个映射：通用词 [CollectionType.labelRes] 与按条目类型分动词的
 * [CollectionType.labelResFor]。
 *
 * ## 为什么钉在这里
 *
 * [CollectionType.labelRes]（通用词）与 [CollectionType.labelResFor]（按条目类型的动词）的取值边界都锁在这里。
 * 依据 bgm.tv 网页端的收藏选择器：书籍 读 / 动画 看 / 音乐 听 / 游戏 玩；
 * **搁置与抛弃在 bgm.tv 不区分条目类型**，回落到通用词。
 */
class CollectionLabelResTest {

    @Test
    fun `通用词逐一映射_未知状态落中性占位`() {
        assertEquals(R.string.collection_wish, CollectionType.WISH.labelRes())
        assertEquals(R.string.collection_done, CollectionType.DONE.labelRes())
        assertEquals(R.string.collection_doing, CollectionType.DOING.labelRes())
        assertEquals(R.string.collection_on_hold, CollectionType.ON_HOLD.labelRes())
        assertEquals(R.string.collection_dropped, CollectionType.DROPPED.labelRes())
        assertEquals(R.string.no_content, CollectionType.UNKNOWN.labelRes())
    }

    @Test
    fun `书籍用读写动词`() {
        assertEquals(R.string.collection_wish_book, CollectionType.WISH.labelResFor(SubjectType.BOOK))
        assertEquals(R.string.collection_done_book, CollectionType.DONE.labelResFor(SubjectType.BOOK))
        assertEquals(R.string.collection_doing_book, CollectionType.DOING.labelResFor(SubjectType.BOOK))
    }

    @Test
    fun `音乐用听动词`() {
        assertEquals(R.string.collection_wish_music, CollectionType.WISH.labelResFor(SubjectType.MUSIC))
        assertEquals(R.string.collection_done_music, CollectionType.DONE.labelResFor(SubjectType.MUSIC))
        assertEquals(R.string.collection_doing_music, CollectionType.DOING.labelResFor(SubjectType.MUSIC))
    }

    @Test
    fun `游戏用玩动词`() {
        assertEquals(R.string.collection_wish_game, CollectionType.WISH.labelResFor(SubjectType.GAME))
        assertEquals(R.string.collection_done_game, CollectionType.DONE.labelResFor(SubjectType.GAME))
        assertEquals(R.string.collection_doing_game, CollectionType.DOING.labelResFor(SubjectType.GAME))
    }

    /**
     * 动画与三次元同用看类动词（bgm.tv 的 real 也在看类）；未知类型同样兜底到看类。动画组走按类型的键（`collection_wish_anime`），
     * 与通用词是两份键，文案逐字相同由 `StringsLocaleContractTest` 比对资源文本保证。
     */
    @Test
    fun `动画三次元与未知类型用看动词`() {
        listOf(SubjectType.ANIME, SubjectType.REAL, SubjectType.UNKNOWN).forEach { type ->
            assertEquals(R.string.collection_wish_anime, CollectionType.WISH.labelResFor(type), "$type 想看")
            assertEquals(R.string.collection_done_anime, CollectionType.DONE.labelResFor(type), "$type 看过")
            assertEquals(R.string.collection_doing_anime, CollectionType.DOING.labelResFor(type), "$type 在看")
        }
    }

    /**
     * 搁置/抛弃在 bgm.tv 不区分类目，两个函数都必须给通用键——
     * 若哪天有人给它们补上分类型键，会与 bgm.tv 文案不符，这条会红。
     */
    @Test
    fun `搁置与抛弃不按条目类型分动词`() {
        SubjectType.entries.forEach { type ->
            assertEquals(R.string.collection_on_hold, CollectionType.ON_HOLD.labelResFor(type), "$type 搁置")
            assertEquals(R.string.collection_dropped, CollectionType.DROPPED.labelResFor(type), "$type 抛弃")
        }
    }

    /** 未知状态不伪造已知语义：任何条目类型都落中性占位。 */
    @Test
    fun `未知状态在任何条目类型下都落中性占位`() {
        SubjectType.entries.forEach { type ->
            assertEquals(R.string.no_content, CollectionType.UNKNOWN.labelResFor(type), "$type 未知状态")
        }
    }
}
