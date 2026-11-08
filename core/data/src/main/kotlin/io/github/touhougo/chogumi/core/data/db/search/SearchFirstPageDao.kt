package io.github.touhougo.chogumi.core.data.db.search

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface SearchFirstPageDao {

    @Query("SELECT * FROM search_first_pages WHERE cacheKey = :cacheKey")
    suspend fun get(cacheKey: String): SearchFirstPageEntity?

    @Upsert
    suspend fun upsert(page: SearchFirstPageEntity)
}
