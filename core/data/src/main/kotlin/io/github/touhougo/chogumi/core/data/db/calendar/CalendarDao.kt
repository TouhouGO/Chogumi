package io.github.touhougo.chogumi.core.data.db.calendar

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
interface CalendarDao {

    @Query("select * from calendar")
    suspend fun getAll(): List<CalendarEntity>

    @Upsert
    suspend fun upsertAll(items: List<CalendarEntity>)

    @Query("delete from calendar")
    suspend fun deleteAll()

    /** 原子替换全部放送缓存（不拆成 deleteAll + upsertAll 两步写：中途被打断会留下半张表） */
    @Transaction
    suspend fun replaceAll(items: List<CalendarEntity>) {
        deleteAll()
        upsertAll(items)
    }
}
