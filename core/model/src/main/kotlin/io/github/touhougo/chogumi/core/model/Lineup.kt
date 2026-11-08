package io.github.touhougo.chogumi.core.model

/** 阵容里的一个名字：声优或制作公司（都是人物条目）。 */
data class LineupName(val id: Int, val name: String, val avatarUrl: String?)

/**
 * 一部作品的阵容：主角与配角的声优、制作公司，各自按人去重。
 * 由 [subjectLineupOf] 从作品的角色与制作人员算出。
 */
data class SubjectLineup(val voiceActors: List<LineupName>, val studios: List<LineupName>)

/** 一个名字出现在几部里。 */
data class LineupCount(val name: LineupName, val works: Int)

/** 一组收藏的阵容：出现最多的声优与制作公司，从多到少。 */
data class Lineup(val voiceActors: List<LineupCount>, val studios: List<LineupCount>)

/** 阵容里声优取几个：前三站上台，后面一排六个（我的设计决定）。 */
const val LINEUP_VOICE_LIMIT = 9

/** 阵容里制作公司取几家（我的设计决定）。 */
const val LINEUP_STUDIO_LIMIT = 8

/** 声优只数这两种定位：客串、群演、旁白不算「绕不开」。 */
private val COUNTED_ROLES = setOf(CastRole.MAIN, CastRole.SUPPORTING)

/**
 * 一部作品的阵容（唯一取法）：主角与配角的声优，加上职位是 [SubjectTypeSpec.studioPosition] 的制作人员。
 * 这个类型不算阵容时返回 null。
 */
fun subjectLineupOf(type: SubjectType, characters: List<RelatedCharacterBo>, persons: List<RelatedPersonBo>): SubjectLineup? {
    val studioPosition = subjectTypeSpec(type).studioPosition ?: return null
    val voiceActors = characters.filter { it.role in COUNTED_ROLES }
        .flatMap { it.actors }
        .distinctBy { it.id }
        .map { LineupName(it.id, it.name, it.images?.avatarUrl()) }
    val studios = persons.filter { it.relation == studioPosition }
        .distinctBy { it.id }
        .map { LineupName(it.id, it.name, it.images?.avatarUrl()) }
    return SubjectLineup(voiceActors, studios)
}
