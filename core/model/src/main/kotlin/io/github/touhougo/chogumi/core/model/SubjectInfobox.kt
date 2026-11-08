package io.github.touhougo.chogumi.core.model

import kotlinx.serialization.Serializable

/** Bangumi 条目资料项；值已从 v0 的多态结构归一化为可显示文本。 */
@Serializable
data class SubjectInfoboxBo(
    val key: String,
    val values: List<String>,
)

/**
 * 资料表里一个值的显示文本，**全 App 唯一**（条目与角色 / 人物的资料表共用，v0 与 p1 两种结构都经这里）：
 * 带子键的写成「子键：值」；子键有、值空的是 wiki 模板没填的占位，丢掉（不留光杆「子键：」）；只有值的就是值。
 */
fun infoboxValueText(subKey: String?, value: String): String? {
    val k = subKey?.trim().orEmpty()
    val v = value.trim()
    return when {
        v.isEmpty() -> null
        k.isEmpty() -> v
        else -> "$k：$v"
    }
}
