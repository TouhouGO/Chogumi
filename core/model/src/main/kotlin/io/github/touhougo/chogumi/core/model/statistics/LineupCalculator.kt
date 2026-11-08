package io.github.touhougo.chogumi.core.model.statistics

import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.LINEUP_STUDIO_LIMIT
import io.github.touhougo.chogumi.core.model.LINEUP_VOICE_LIMIT
import io.github.touhougo.chogumi.core.model.Lineup
import io.github.touhougo.chogumi.core.model.LineupCount
import io.github.touhougo.chogumi.core.model.LineupName
import io.github.touhougo.chogumi.core.model.SubjectLineup
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.UserCollectionBo
import io.github.touhougo.chogumi.core.model.subjectTypeSpec

/**
 * 一条收藏算不算进阵容（唯一判定）：看过或在看，且这个类型算阵容（[SubjectTypeSpec.studioPosition]）。
 * 统计只数这些，同步也只给这些补拉角色与制作人员。
 */
fun countsForLineup(type: CollectionType, subjectType: SubjectType): Boolean =
    type in SETTLED_OR_ONGOING && subjectTypeSpec(subjectType).studioPosition != null

/**
 * [collections] 里算进阵容（[countsForLineup]）的作品：每个声优、每家制作公司出现在几部，各取最多的几个。
 * 只数 [lineups] 里有的作品（还没拉到的不算）；一个名字都没有时为 null。一样多的按名字排，结果稳定。
 */
internal fun lineupOf(collections: List<UserCollectionBo>, lineups: Map<Int, SubjectLineup>): Lineup? {
    val counted = collections
        .filter { countsForLineup(it.type, it.subjectType) }
        .mapNotNull { lineups[it.subjectId] }
    fun rank(namesOf: (SubjectLineup) -> List<LineupName>, limit: Int): List<LineupCount> {
        val people = LinkedHashMap<Int, LineupName>()
        val works = HashMap<Int, Int>()
        for (lineup in counted) {
            for (name in namesOf(lineup)) {
                people.putIfAbsent(name.id, name)
                works[name.id] = (works[name.id] ?: 0) + 1
            }
        }
        return people.values
            .map { LineupCount(it, works.getValue(it.id)) }
            .sortedWith(compareByDescending<LineupCount> { it.works }.thenBy { it.name.name })
            .take(limit)
    }
    val lineup = Lineup(rank({ it.voiceActors }, LINEUP_VOICE_LIMIT), rank({ it.studios }, LINEUP_STUDIO_LIMIT))
    return lineup.takeIf { it.voiceActors.isNotEmpty() || it.studios.isNotEmpty() }
}
