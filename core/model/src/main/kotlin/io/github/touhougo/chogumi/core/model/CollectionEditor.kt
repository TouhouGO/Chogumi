package io.github.touhougo.chogumi.core.model

import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.orEmpty
import java.text.Normalizer

/** 收藏面板里的进度：画什么、存什么由规格表的进度单位（[ProgressUnit]）定，见 [CollectionEditor.saved]。 */
sealed interface CollectionProgress {
    /** 游戏、音乐、不认识的类型：没有进度区，保存时不碰进度。 */
    data object None : CollectionProgress

    /**
     * 动画、三次元：看到哪一集（[upTo]，一集都没看是 [EpisodeProgressTarget.Empty]）。与章节方格同一个锚点
     * （[EpisodeTracking.currentEpisode]），保存时走「看到第 N 集」。不拿收藏记录里的计数当集号：分季条目的集号接着上一季数
     * （第二季从 202 起）。
     */
    data class Episodes(val upTo: EpisodeProgressTarget) : CollectionProgress

    /** 书籍：读到第几话、第几卷（收藏记录里的计数，随收藏一起写）。 */
    data class Book(val chapters: Int, val volumes: Int) : CollectionProgress
}

/** 面板上此刻看到的一张表：已保存的收藏叠上没保存的修改（[CollectionEditor.formOf]）。 */
data class CollectionForm(
    val type: CollectionType,
    val rate: Int,
    val progress: CollectionProgress,
    val tags: List<String>,
    val comment: String,
    val isPrivate: Boolean,
)

/**
 * 收藏面板上没保存的修改：只记动过的项，null = 没动，跟着已保存的收藏走。面板开着时收藏被别处改了（回读、网页），
 * 没动的项照新的显示，保存也不会把旧值写回去。关掉面板没保存时由页面暂存，下次打开接着改。
 *
 * @property finishAll 选「看过」时进度一起标到最后（[CollectionEditor.offersFinishAll] 时才问，默认开）。
 */
data class CollectionEdits(
    val type: CollectionType? = null,
    val rate: Int? = null,
    val progress: CollectionProgress? = null,
    val tags: List<String>? = null,
    val comment: String? = null,
    val isPrivate: Boolean? = null,
    val finishAll: Boolean = true,
)

/**
 * 保存一张表要做的两件事。
 *
 * @property modify 收藏记录改哪些字段：只发改了的（[CollectionModify.changedFrom]），书籍的话卷数也在这里。
 * @property watchUpTo 动画、三次元：显式进度目标（含空进度），进度没改为 null。
 */
data class CollectionSave(val modify: CollectionModify, val watchUpTo: EpisodeProgressTarget?)

/** 保存一张表的结果：存好了，或者哪一步没成（收藏记录、进度各有各的失败说法）。 */
sealed interface CollectionSaveResult {
    data object Saved : CollectionSaveResult

    data class Failed(val step: Step, val failure: Failure) : CollectionSaveResult

    /** 收藏记录（状态、评分、标签、吐槽、可见性，书籍的话卷数）；进度（动画、三次元「看到第 N 集」）。 */
    enum class Step { RECORD, PROGRESS }
}

/**
 * 收藏面板改的那一部作品：作品、已保存的收藏（null = 还没收藏）、章节与逐集状态。面板的判定只经 [editor]，
 * 详情页（页面状态里本来就有这几样）与列表页（长按时现取）都拼成这一份交给面板。
 *
 * @param episodes 章节：没到时进度尺占位，失败时就地重试；书籍、游戏、音乐用不到。
 */
data class CollectionWork(
    val subject: SubjectBo,
    val collection: UserCollectionBo?,
    val episodes: Load<List<EpisodeBo>>,
    val watchStates: Map<Int, EpisodeStatus>,
) {
    /** 这部作品的收藏判定。总话数、总卷数取规格表的 `eps` / `volumes`（未知为 0）。 */
    val editor: CollectionEditor by lazy {
        CollectionEditor(
            subjectType = subject.type,
            episodes = episodes.orEmpty(),
            watchStates = watchStates,
            totalChapters = subject.eps ?: 0,
            totalVolumes = subject.volumes ?: 0,
            collection = collection,
        )
    }
}

/**
 * 收藏面板对一部作品的全部判定（纯函数）：已保存的样子、叠上修改后的样子、改没改、该建议什么、保存发什么。
 * 面板显示与 ViewModel 保存都只经这里，不各判一遍。
 *
 * @param subjectType 作品类型，定进度区画什么。
 * @param episodes 完整章节列表：正片的「看到这里」与最后一集从这里取。没到之前正片为空，进度区不能改。
 * @param watchStates 逐集状态（「看到这里」的真源）。
 * @param totalChapters 书籍总话数（0 = 未知）。
 * @param totalVolumes 书籍总卷数（0 = 未知）。
 * @param collection 已保存的收藏；null = 还没收藏，保存即新建。
 */
class CollectionEditor(
    val subjectType: SubjectType,
    episodes: List<EpisodeBo>,
    watchStates: Map<Int, EpisodeStatus>,
    val totalChapters: Int,
    val totalVolumes: Int,
    val collection: UserCollectionBo?,
) {
    /** 正片：动画、三次元的进度尺按它排（格上写 sort）。 */
    val mains: List<EpisodeBo> = EpisodeTracking.mainEpisodes(episodes)

    /** 已保存的样子。还没收藏时是新建的默认表：「想看」，其余为空。 */
    val saved: CollectionForm = CollectionForm(
        type = collection?.type ?: CollectionType.WISH,
        rate = collection?.rate ?: 0,
        progress = when (subjectTypeSpec(subjectType).progressUnit) {
            ProgressUnit.EPISODE -> CollectionProgress.Episodes(targetOf(EpisodeTracking.currentEpisode(episodes, watchStates)))
            ProgressUnit.VOLUME -> CollectionProgress.Book(collection?.epStatus ?: 0, collection?.volStatus ?: 0)
            null -> CollectionProgress.None
        },
        tags = collection?.tags.orEmpty(),
        comment = collection?.comment.orEmpty(),
        isPrivate = collection?.isPrivate ?: false,
    )

    fun formOf(edits: CollectionEdits): CollectionForm = CollectionForm(
        type = edits.type ?: saved.type,
        rate = edits.rate ?: saved.rate,
        progress = edits.progress ?: saved.progress,
        tags = edits.tags ?: saved.tags,
        comment = edits.comment ?: saved.comment,
        isPrivate = edits.isPrivate ?: saved.isPrivate,
    )

    /** 有没有要保存的：还没收藏时保存就是新建，总有；已收藏时看表和已保存的一不一样（「一起标完」开关不算）。 */
    fun isDirty(edits: CollectionEdits): Boolean = collection == null || formOf(edits) != saved

    /** 进度区显示不显示：有进度单位，且不是「想看」（还没开始的作品不记进度）。 */
    fun showsProgress(form: CollectionForm): Boolean = form.progress != CollectionProgress.None && form.type != CollectionType.WISH

    /** 状态上方建议「标为看过」：进度到头了，还不是看过。 */
    fun suggestsDone(form: CollectionForm): Boolean = form.type != CollectionType.DONE && atEnd(form.progress)

    /** 选了「看过」、进度还没到头、又知道头在哪：多问一句要不要一起标到最后。 */
    fun offersFinishAll(form: CollectionForm): Boolean =
        form.type == CollectionType.DONE && !atEnd(form.progress) && finished(form.progress) != null

    fun saveOf(edits: CollectionEdits): CollectionSave {
        val form = formOf(edits)
        val progress = when {
            // 「想看」不显示进度区：改过的进度不跟着存
            form.type == CollectionType.WISH -> saved.progress
            edits.finishAll && offersFinishAll(form) -> finished(form.progress) ?: form.progress
            else -> form.progress
        }
        val book = progress as? CollectionProgress.Book
        val modify = CollectionModify(
            type = form.type,
            rate = form.rate,
            epStatus = book?.chapters,
            volStatus = book?.volumes,
            comment = form.comment,
            private = form.isPrivate,
            tags = form.tags,
        ).changedFrom(collection)
        val watchUpTo = (progress as? CollectionProgress.Episodes)?.takeIf { it != saved.progress }?.upTo
        return CollectionSave(modify, watchUpTo)
    }

    /** 动画的进度尺上第几格：空进度为 0，第 i 格是第 i 集正片（格上写它的 sort）。 */
    fun episodeIndexOf(progress: CollectionProgress.Episodes): Int = (EpisodeTracking.progressIndexOf(mains, progress.upTo) ?: -1) + 1

    /** 动画进度尺上第 [index] 格是看到第几集。 */
    fun episodesAt(index: Int): CollectionProgress.Episodes = CollectionProgress.Episodes(targetOf(mains.getOrNull(index - 1)))

    /**
     * 动画进度尺能拖到第几格：最后一集已播出的正片（[EpisodeTracking.airState]，播出日不明按已播出算）；之后的还没播，
     * 灰着、拖不过去。[today] 是业务日。
     */
    fun airedUpTo(today: java.time.LocalDate): Int = mains.indexOfLast { EpisodeTracking.airState(it, today) != AirState.UNAIRED } + 1

    /** 进度到头了：动画看完正片（[EpisodeTracking.watchedAllMains]）；书籍读到最后一话（话数未知时看卷数，都未知就不算）。 */
    private fun atEnd(progress: CollectionProgress): Boolean = when (progress) {
        CollectionProgress.None -> false
        is CollectionProgress.Episodes -> EpisodeTracking.watchedAllMains(
            mains,
            EpisodeTracking.progressIndexOf(mains, progress.upTo)?.let(mains::getOrNull),
        )
        is CollectionProgress.Book -> when {
            totalChapters > 0 -> progress.chapters >= totalChapters
            totalVolumes > 0 -> progress.volumes >= totalVolumes
            else -> false
        }
    }

    /** 标到最后是什么样：动画是最后一集正片；书籍是总话数与总卷数（未知的那一项不动）。不知道头在哪为 null。 */
    private fun finished(progress: CollectionProgress): CollectionProgress? = when (progress) {
        CollectionProgress.None -> null
        is CollectionProgress.Episodes -> mains.lastOrNull()?.let { CollectionProgress.Episodes(targetOf(it)) }
        is CollectionProgress.Book -> CollectionProgress.Book(
            chapters = totalChapters.takeIf { it > 0 } ?: progress.chapters,
            volumes = totalVolumes.takeIf { it > 0 } ?: progress.volumes,
        ).takeIf { totalChapters > 0 || totalVolumes > 0 }
    }
}

/**
 * 收藏的文字输入规矩，照服务端的校验（bangumi/server `web/req/collection.go`）：标签最多 [MAX_TAGS] 个、
 * 每个至少 [MIN_TAG_LENGTH] 个字、不含空白，校验前先做 NFKC 归一；吐槽最多 [MAX_COMMENT] 个字。字数按字符（码点）算，
 * 不按 UTF-16 长度：一个表情是一个字。
 */
object CollectionInput {
    const val MAX_TAGS = 10
    const val MIN_TAG_LENGTH = 2
    const val MAX_COMMENT = 380

    /** 加一个标签行不行。 */
    enum class TagProblem {
        /** 不到 [MIN_TAG_LENGTH] 个字。 */
        TOO_SHORT,

        /** 已经有这个标签。 */
        DUPLICATE,

        /** 已满 [MAX_TAGS] 个。 */
        FULL,
    }

    /**
     * 一段输入里的标签：与服务端一样先做 NFKC 归一（全角字母数字变半角，全角空格等各种空格变成普通空格），再按空白拆开。
     */
    fun tagsIn(raw: String): List<String> =
        Normalizer.normalize(raw, Normalizer.Form.NFKC).split(WHITESPACE).filter { it.isNotEmpty() }

    /** [tag]（已经过 [tagsIn]）能不能加进 [tags]；null = 能。 */
    fun tagProblem(tags: List<String>, tag: String): TagProblem? = when {
        tag in tags -> TagProblem.DUPLICATE
        tags.size >= MAX_TAGS -> TagProblem.FULL
        length(tag) < MIN_TAG_LENGTH -> TagProblem.TOO_SHORT
        else -> null
    }

    /** 字数：按字符（码点）数。 */
    fun length(text: CharSequence): Int = Character.codePointCount(text, 0, text.length)

    private val WHITESPACE = Regex("\\s+")
}
