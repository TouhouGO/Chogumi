package io.github.touhougo.chogumi.core.data.db.mapper

import io.github.touhougo.chogumi.core.model.FactRow
import io.github.touhougo.chogumi.core.model.MonoCharacterRefBo
import io.github.touhougo.chogumi.core.model.MonoDetailsBo
import io.github.touhougo.chogumi.core.model.MonoPersonRefBo
import io.github.touhougo.chogumi.core.model.MonoRelationBo
import io.github.touhougo.chogumi.core.model.MonoWorkBo
import io.github.touhougo.chogumi.core.model.RelatedImagesBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.castRoleOf
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.model.infoboxValueText
import io.github.touhougo.chogumi.core.model.parseSubjectYearMonth
import io.github.touhougo.chogumi.core.network.model.p1.CharacterDto
import io.github.touhougo.chogumi.core.network.model.p1.CharacterRelationDto
import io.github.touhougo.chogumi.core.network.model.p1.CharacterSubjectDto
import io.github.touhougo.chogumi.core.network.model.p1.InfoboxItemDto
import io.github.touhougo.chogumi.core.network.model.p1.InfoboxValueDto
import io.github.touhougo.chogumi.core.network.model.p1.P1SlimSubjectDto
import io.github.touhougo.chogumi.core.network.model.p1.PersonCharacterDto
import io.github.touhougo.chogumi.core.network.model.p1.PersonDto
import io.github.touhougo.chogumi.core.network.model.p1.PersonImagesDto
import io.github.touhougo.chogumi.core.network.model.p1.PersonRelationDto
import io.github.touhougo.chogumi.core.network.model.p1.PersonWorkDto

/**
 * p1 人物图 DTO → 领域图片档位容器。
 *
 * 只做字段搬运，不做档位选择：取哪一档由 [RelatedImagesBo] 的档位函数单一真源决定
 * （圆形头像用 avatarUrl()，海报用 posterUrl()）。
 */
internal fun PersonImagesDto.toRelatedImagesBo() =
    RelatedImagesBo(large = large, medium = medium, small = small, grid = grid)

/** v0 人物图 DTO（字段同 p1 的 [PersonImagesDto]）→ 领域图片档位容器。 */
internal fun io.github.touhougo.chogumi.core.network.model.v0.PersonImagesDto.toRelatedImagesBo() =
    RelatedImagesBo(large = large, medium = medium, small = small, grid = grid)

/** p1 资料表的一个值 → 显示文本（规则见 [infoboxValueText]）。 */
fun InfoboxValueDto.toDisplayText(): String? = infoboxValueText(k, v)

/**
 * 单条 Infobox 项解析，异常或空项返回 null（单条容错，不崩整卡）。
 */
fun InfoboxItemDto.toFactRow(): FactRow? {
    val trimmedKey = key.trim()
    if (trimmedKey.isBlank()) return null
    val displayValues = runCatching {
        values.mapNotNull { it.toDisplayText() }.filter { it.isNotBlank() }
    }.getOrDefault(emptyList())
    if (displayValues.isEmpty()) return null
    return FactRow(
        key = trimmedKey,
        value = displayValues.joinToString("、"),
    )
}

/**
 * Infobox 列表容错映射。
 */
fun List<InfoboxItemDto>?.toFactRows(): List<FactRow> = orEmpty().mapNotNull { it.toFactRow() }

fun CharacterDto.toBo() = MonoDetailsBo(
    id = id,
    name = name,
    nameCn = nameCN,
    careers = emptyList(),
    summary = summary.ifBlank { info },
    image = images?.toRelatedImagesBo()?.posterUrl(),
    infobox = infobox.toFactRows(),
    collectCount = collects,
    isCollected = (collectedAt ?: 0L) > 0L,
)

fun PersonDto.toBo() = MonoDetailsBo(
    id = id,
    name = name,
    nameCn = nameCN,
    careers = career,
    summary = summary.ifBlank { info },
    image = images?.toRelatedImagesBo()?.posterUrl(),
    infobox = infobox.toFactRows(),
    collectCount = collects,
    isCollected = (collectedAt ?: 0L) > 0L,
)

/** 作品条目的公共部分（角色出演与人物参与共用）。日期取自 `info` 文本：接口没有日期字段。 */
private fun P1SlimSubjectDto.toMonoWorkBo() = MonoWorkBo(
    id = id,
    name = name,
    nameCn = nameCN,
    image = images?.toRelatedImagesBo()?.posterUrl(),
    subjectType = SubjectType.from(type) ?: SubjectType.UNKNOWN,
    ratingScore = rating.score,
    ratingTotal = rating.total,
    date = parseSubjectYearMonth(info),
)

/**
 * 角色出演作品项：带上定位（`type` 1 主角 / 2 配角 / 3 客串，[castRoleOf]）与声优。
 * 声优头像取 [RelatedImagesBo.avatarUrl]（整张立绘，界面顶对齐裁圆）。
 */
fun CharacterSubjectDto.toBo(): MonoWorkBo = subject.toMonoWorkBo().copy(
    role = castRoleOf(type),
    actors = casts.map { cast ->
        MonoPersonRefBo(
            id = cast.person.id,
            name = displayNameOf(name = cast.person.name, nameCn = cast.person.nameCN),
            avatarUrl = cast.person.images?.toRelatedImagesBo()?.avatarUrl(),
        )
    },
)

/** 人物参与作品项：带上职位（中文名优先，接口顺序）。 */
fun PersonWorkDto.toBo(): MonoWorkBo = subject.toMonoWorkBo().copy(
    positions = positions.map { pos ->
        pos.type.cn.ifBlank { pos.type.en.ifBlank { pos.type.jp.ifBlank { pos.summary } } }
    }.filter { it.isNotBlank() }.distinct(),
)

/** 人物演过的一个角色：作品名取第一段关系的条目。头像取 [RelatedImagesBo.avatarUrl]。 */
fun PersonCharacterDto.toBo(): MonoCharacterRefBo = MonoCharacterRefBo(
    id = character.id,
    name = character.name,
    nameCn = character.nameCN,
    avatarUrl = character.images?.toRelatedImagesBo()?.avatarUrl(),
    subjectName = relations.firstOrNull()?.subject?.let { displayNameOf(name = it.name, nameCn = it.nameCN) }.orEmpty(),
)

/** 角色关联关系映射。头像取 [RelatedImagesBo.avatarUrl]（整张立绘，界面顶对齐裁圆）。 */
fun CharacterRelationDto.toBo(): MonoRelationBo = MonoRelationBo(
    id = character.id,
    name = character.name,
    nameCn = character.nameCN,
    relation = relation.cn.ifBlank { relation.desc },
    image = character.images?.toRelatedImagesBo()?.avatarUrl(),
)

/** 人物关联关系映射，头像同 [CharacterRelationDto.toBo]。 */
fun PersonRelationDto.toBo(): MonoRelationBo = MonoRelationBo(
    id = person.id,
    name = person.name,
    nameCn = person.nameCN,
    relation = relation.cn.ifBlank { relation.desc },
    image = person.images?.toRelatedImagesBo()?.avatarUrl(),
)
