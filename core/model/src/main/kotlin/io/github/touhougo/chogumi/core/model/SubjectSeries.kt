package io.github.touhougo.chogumi.core.model

/**
 * 一部作品所在的系列：沿「前传」往前、沿「续集」往后找到的同类型作品，不含作品本身。
 * [before] 从最早到紧挨着的前一部，[after] 从紧挨着的后一部到最晚。
 */
data class SubjectSeries(
    val before: List<SubjectRelationBo>,
    val after: List<SubjectRelationBo>,
) {
    val isEmpty: Boolean get() = before.isEmpty() && after.isEmpty()
}

