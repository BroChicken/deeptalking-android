package com.deeptalking.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CharacterDao {
    @Query("SELECT * FROM characters ORDER BY id ASC")
    fun observeAll(): Flow<List<CharacterEntity>>

    @Query("SELECT * FROM characters WHERE id = :id LIMIT 1")
    suspend fun get(id: String): CharacterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CharacterEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<CharacterEntity>)

    @Query("DELETE FROM characters WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM characters")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM characters")
    suspend fun count(): Int
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE characterId = :characterId ORDER BY timestamp ASC")
    fun observeForCharacter(characterId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE characterId = :characterId ORDER BY timestamp ASC")
    suspend fun getForCharacter(characterId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE characterId = :characterId")
    suspend fun deleteForCharacter(characterId: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}

@Dao
interface ConfigDao {
    @Query("SELECT * FROM config WHERE id = 0 LIMIT 1")
    fun observe(): Flow<ConfigEntity?>

    @Query("SELECT * FROM config WHERE id = 0 LIMIT 1")
    suspend fun get(): ConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ConfigEntity)
}
