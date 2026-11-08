package io.github.touhougo.chogumi.core.model

/** 用户对条目的收藏 */
data class UserCollectionBo(
    val subjectId: Int,
    val subjectType: SubjectType,
    val type: CollectionType,
    val rate: Int = 0,
    val tags: List<String> = emptyList(),
    val epStatus: Int = 0,
    val volStatus: Int = 0,
    val comment: String = "",
    val isPrivate: Boolean = false,
    /** 收藏最近更新时间（epoch 毫秒；DAO 排序与首页 hero"最近更新"依据） */
    val updatedAt: Long = 0L,
    val subject: SubjectBo? = null,
    val rawSubjectType: Int = subjectType.value,
    val rawType: Int = type.value,
)
