package io.github.touhougo.chogumi.core.network.model.v0

import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EpisodeDto(
    val id: Int, @SerialName("subject_id") val subjectId: Int = 0,
    @Required val type: Int = 0, @Required val name: String = "",
    @Required @SerialName("name_cn") val nameCn: String = "", @Required val sort: Double = 0.0,
    val ep: Double? = null,
    @Required val airdate: String = "", @Required val comment: Int = 0,
    @Required val duration: String = "", @Required val desc: String = "", @Required val disc: Int = 0,
    @SerialName("duration_seconds") val durationSeconds: Int = 0,
)
@Serializable
data class EpisodeDetailDto(val id: Int, val type: Int, val name: String, @SerialName("name_cn") val nameCn: String, val sort: Double, val ep: Double? = null, val airdate: String, val comment: Int, val duration: String, val desc: String, val disc: Int, @SerialName("subject_id") val subjectId: Int)
