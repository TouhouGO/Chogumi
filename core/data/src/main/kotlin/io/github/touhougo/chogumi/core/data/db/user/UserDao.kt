package io.github.touhougo.chogumi.core.data.db.user

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface UserDao {

    @Query("select * from users where id = :id")
    suspend fun get(id: Int): UserEntity?

    @Query("select * from users where username = :username")
    suspend fun getByUsername(username: String): UserEntity?

    @Upsert
    suspend fun upsert(user: UserEntity)
}
