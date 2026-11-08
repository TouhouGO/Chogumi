package io.github.touhougo.chogumi.core.data.db.calendar

import androidx.room.Entity

/** 每日放送缓存（TTL 12h；复合主键 星期+条目） */
@Entity(tableName = "calendar", primaryKeys = ["weekday", "subjectId"])
data class CalendarEntity(
    val weekday: Int,
    val subjectId: Int,
    val name: String,
    val nameCn: String = "",
    val imageLarge: String? = null,
    val watchers: Int = 0,
    val updatedAt: Long = 0L,
    /**
     * 条目是否 NSFW。
     *
     * 服务端对匿名请求会先剔除 NSFW 条目，所以本字段**不是** NSFW 过滤的真源，
     * 真源是 `subjects` 表（[io.github.touhougo.chogumi.core.data.db.subject.SubjectEntity.nsfw]）。
     * 它只在 subjects 表查不到该条目时作为**兜底**，防止本地缓存路径漏放 NSFW
     * （见 `CalendarRepository.CalendarEntity.toBo()` 的伪造分支）。
     */
    val nsfw: Boolean = false,
)
