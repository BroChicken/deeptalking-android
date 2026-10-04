package com.deeptalking.core.data

import com.deeptalking.core.database.ConfigDao
import com.deeptalking.core.database.ConfigEntity
import com.deeptalking.core.model.AppConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class ConfigRepository(private val dao: ConfigDao) {

    fun observe(): Flow<AppConfig> =
        dao.observe().map { entity -> entity?.let(::decode) ?: AppConfig() }

    suspend fun current(): AppConfig = observe().first()

    suspend fun update(config: AppConfig) {
        dao.upsert(ConfigEntity(payload = AppJson.encodeToString(config)))
    }

    private fun decode(entity: ConfigEntity): AppConfig? =
        runCatching { AppJson.decodeFromString<AppConfig>(entity.payload) }.getOrNull()
}
