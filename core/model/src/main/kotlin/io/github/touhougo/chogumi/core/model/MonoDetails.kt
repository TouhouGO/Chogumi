package io.github.touhougo.chogumi.core.model

import kotlinx.serialization.Serializable

/** 人物系实体的类别：角色（Character）或人物（Person）。两类实体在接口、标题、默认排序上的分歧都按它判。 */
enum class MonoType {
    CHARACTER,
    PERSON,
}

/**
 * 角色或人物详情。[careers] 是人物的职业代号（`seiyu`、`artist`…，服务端原样，文案由界面映射），角色为空；
 * [image] 是立绘 / 照片（大图优先）。
 */
data class MonoDetailsBo(
    val id: Int,
    override val name: String,
    override val nameCn: String,
    val careers: List<String>,
    val summary: String,
    val image: String?,
    val infobox: List<FactRow>,
    val collectCount: Int,
    val isCollected: Boolean,
) : NamedEntity

/**
 * 角色/人物关联关系领域模型。
 */
data class MonoRelationBo(
    val id: Int,
    override val name: String,
    override val nameCn: String,
    val relation: String,
    val image: String?,
) : NamedEntity

/**
 * 角色在一部作品里的定位（p1 `/characters/{id}/casts` 与 `/subjects/{id}/characters` 每项的 `type`，
 * 也是 `/persons/{id}/casts` 的 `type` 筛选参数）。
 * 声明顺序就是主次；[code] 是服务端取值（唯一映射，2026-10-05 对照网页实测：4 闲角、5 旁白）。
 * 可序列化：条目详情的角色列表连同定位整存进本地缓存（[RelatedCharacterBo]）。
 */
@Serializable
enum class CastRole(val code: Int) {
    MAIN(1),
    SUPPORTING(2),
    GUEST(3),
    EXTRA(4),
    NARRATOR(5),
}

/** 服务端定位值 → [CastRole]；不认识的值为 null。 */
fun castRoleOf(type: Int): CastRole? = CastRole.entries.find { it.code == type }

/** 作品项里出现的一个人（角色作品里的声优）。[avatarUrl] 取自 [RelatedImagesBo.avatarUrl]。 */
data class MonoPersonRefBo(val id: Int, val name: String, val avatarUrl: String?)

/**
 * 人物系实体的一部作品——角色的「出演作品」与人物的「参与作品」共用这一个模型：
 * 展示、排序（[sortMonoWorks]）与筛选（[monoWorkFilterOptions]）都只有一份。
 *
 * @property role 角色在这部作品里的定位；人物的作品为 null。
 * @property positions 人物在这部作品里的职位（中文名，接口顺序）；角色的作品为空。
 * @property actors 角色在这部作品里的声优；人物的作品为空。
 */
data class MonoWorkBo(
    val id: Int,
    override val name: String,
    override val nameCn: String,
    val image: String?,
    val subjectType: SubjectType,
    override val ratingScore: Double = 0.0,
    override val ratingTotal: Int = 0,
    override val date: SubjectYearMonth? = null,
    val role: CastRole? = null,
    val positions: List<String> = emptyList(),
    val actors: List<MonoPersonRefBo> = emptyList(),
) : NamedEntity, WorkSortKey

/** 人物演过的一个角色（人物页「代表角色」）：[subjectName] 是该角色所在的第一部作品。 */
data class MonoCharacterRefBo(
    val id: Int,
    override val name: String,
    override val nameCn: String,
    val avatarUrl: String?,
    val subjectName: String,
) : NamedEntity
