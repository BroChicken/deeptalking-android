package com.deeptalking.core.data.legacy

import com.deeptalking.core.data.AppJson
import com.deeptalking.core.data.CharacterRepository
import com.deeptalking.core.data.ChatRepository
import com.deeptalking.core.data.ConfigRepository
import com.deeptalking.core.database.CharacterDao
import com.deeptalking.core.database.CharacterEntity
import com.deeptalking.core.database.ConfigDao
import com.deeptalking.core.database.ConfigEntity
import com.deeptalking.core.database.MessageDao
import com.deeptalking.core.database.MessageEntity
import com.deeptalking.core.model.LorebookOrigin
import com.deeptalking.core.model.MemoryCategory
import com.deeptalking.core.model.MemorySubject
import com.deeptalking.core.model.PromiseStatus
import com.deeptalking.core.model.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyMapperTest {

    private val singleJson = """
        {
          "config": {
            "apiPlatform": "custom",
            "apiBaseUrl": "https://example.com/v1/",
            "apiKey": "SHOULD_NOT_LEAK",
            "modelName": "my-model",
            "temperature": 1.3,
            "stream": false,
            "reasoningEffort": "high",
            "proactiveEnabled": false,
            "styleCritique": false,
            "quickReplyRepair": false
          },
          "characters": {
            "c1": {
              "id": "c1",
              "entityType": "character",
              "basicInfo": {
                "name": "Aria",
                "avatar": "🦊",
                "gender": "female",
                "age": "20",
                "background": "born in woods",
                "worldView": "should not merge",
                "personality": "calm",
                "userAddress": " 小 主 人 ",
                "occupation": "mage"
              },
              "dynamicState": {
                "currentSituation": "at camp",
                "recentDevelopment": "found a map",
                "currentMood": "calm",
                "currentEmotionalTendency": "curious",
                "currentGoal": "find temple",
                "currentFocus": "the map",
                "currentLocation": "forest",
                "currentImportantOthers": "Bob"
              },
              "memory": {
                "instant": [
                  { "id": "m1", "role": "user", "content": "hello", "timestamp": "2026-01-01T00:00:00.000Z",
                    "images": ["data:image/png;base64,AAAA"] }
                ],
                "shortTerm": [
                  { "id": "st1", "content": "they met", "timestamp": "2026-01-01T00:01:00.000Z",
                    "eventTime": "2026-01-01", "sourceMessageIds": ["m1"], "timeRef": { "anchor": "today" } }
                ],
                "longTerm": {
                  "userProfile": [
                    { "id": "lt1", "key": "name", "value": "Aria likes tea", "tags": ["tea"],
                      "importance": 5, "subject": "user", "evidence": "q",
                      "eventTime": "2026-01-01T00:00:00.000Z", "createdAt": "2026-01-01T00:00:00.000Z",
                      "updatedAt": "2026-01-02T00:00:00.000Z", "recallCount": 2 }
                  ],
                  "promises": [
                    { "id": "lt2", "key": "promise", "value": "meet again", "importance": 3,
                      "subject": "relationship", "status": "resolved", "promisor": "Aria", "promisee": "user" }
                  ]
                }
              },
              "lorebook": [
                { "id": "lb1", "name": "Temple", "keywords": ["temple", "temple of light"],
                  "content": "A hidden temple.", "alwaysActive": true, "origin": "ai", "misses": 2,
                  "lastMentionedAt": "2026-01-03T00:00:00.000Z" }
              ],
              "stickers": [
                { "id": "s1", "dataUrl": "data:image/png;base64,AAAA", "tag": "开心",
                  "createdAt": "2026-01-01T00:00:00.000Z" }
              ]
            }
          },
          "activeCharacterId": "c1",
          "activeTheme": "theme-blue",
          "version": "1.3.6"
        }
    """.trimIndent()

    private class RecordingSink : StickerSink {
        val calls = mutableListOf<String>()
        override fun refFor(dataUri: String): String {
            calls += dataUri
            return "/tmp/stickers/${calls.size}.jpg"
        }
    }

    private fun parse(dtoJson: String): LegacyRoot =
        Json { ignoreUnknownKeys = true; isLenient = true }.decodeFromString(dtoJson)

    @Test
    fun mapsSingleCharacterFields() {
        val sink = RecordingSink()
        val root = parse(singleJson)
        val dto = root.characters!!.getValue("c1")
        val character = mapCharacter(dto, sink)

        assertEquals("c1", character.id)
        assertEquals("Aria", character.name)
        assertEquals("🦊", character.emoji)
        assertFalse(character.isGroup)
        assertEquals("calm", character.description)

        assertEquals("female", character.staticProfile.gender)
        assertEquals("20", character.staticProfile.age)
        assertEquals("born in woods", character.staticProfile.background)
        assertEquals("calm", character.staticProfile.personality)
        assertEquals("小主人", character.staticProfile.userAddress)

        assertEquals("at camp found a map", character.dynamicState.currentSituation)
        assertEquals("calm curious", character.dynamicState.currentMood)
        assertEquals("find temple the map", character.dynamicState.currentGoal)
        assertEquals("forest", character.dynamicState.currentLocation)
        assertEquals("mage", character.dynamicState.currentOccupation)
        assertEquals("Bob", character.dynamicState.currentImportantOthers)

        assertEquals(1, character.instant.size)
        val message = character.instant.first()
        assertEquals("m1", message.id)
        assertEquals(Role.User, message.role)
        assertEquals("hello", message.content)
        assertEquals("2026-01-01T00:00:00.000Z", message.timestamp)
        assertEquals(1, message.attachments.size)
        assertEquals("data:image/png;base64,AAAA", message.attachments.first().uri)

        assertEquals(1, character.shortTerm.size)
        assertEquals("today", character.shortTerm.first().timeRef)
        assertEquals("2026-01-01T00:01:00.000Z", character.shortTerm.first().createdAt)

        assertEquals(2, character.longTerm.size)
        val profile = character.longTerm.first { it.id == "lt1" }
        assertEquals(MemoryCategory.UserProfile, profile.category)
        assertEquals(MemorySubject.User, profile.subject)
        assertEquals(5, profile.importance)
        assertEquals(2, profile.recallCount)
        val promise = character.longTerm.first { it.id == "lt2" }
        assertEquals(MemoryCategory.Promises, promise.category)
        assertEquals(PromiseStatus.Resolved, promise.status)
        assertEquals("Aria", promise.promisor)

        assertEquals(1, character.lorebook.size)
        assertEquals(LorebookOrigin.Model, character.lorebook.first().origin)
        assertTrue(character.lorebook.first().alwaysActive)
        assertEquals(2, character.lorebook.first().keywords.size)
        assertEquals("2026-01-03T00:00:00.000Z", character.lorebook.first().updatedAt)

        assertEquals(1, character.stickers.size)
        assertEquals("/tmp/stickers/1.jpg", character.stickers.first().fileRef)
        assertEquals("开心", character.stickers.first().tag)
        assertEquals(listOf("data:image/png;base64,AAAA"), sink.calls)
    }

    @Test
    fun mapsConfigWithLegacyDefaults() {
        val config = mapConfig(parse(singleJson).config)
        assertEquals("custom", config.apiPlatform)
        assertEquals("https://example.com/v1", config.apiBaseUrl)
        assertEquals("my-model", config.modelName)
        assertEquals(1.3, config.temperature, 0.0001)
        assertFalse(config.stream)
        assertEquals("high", config.reasoningEffort)
        assertFalse(config.proactiveEnabled)
        assertFalse(config.styleCritique)
        assertFalse(config.quickReplyRepair)

        val missing = mapConfig(null)
        assertEquals("deepseek", missing.apiPlatform)
        assertEquals(0.8, missing.temperature, 0.0001)
        assertTrue(missing.stream)
    }

    @Test
    fun handlesNestedFieldsVariant() {
        val nested = """
            {
              "characters": {
                "g1": {
                  "id": "g1",
                  "entityType": "character",
                  "basicInfo": { "name": "Nested", "fields": { "gender": "male", "background": "nested bg" } },
                  "dynamicState": { "fields": { "currentSituation": "nested sit", "currentMood": "happy" } }
                }
              }
            }
        """.trimIndent()
        val character = mapCharacter(parse(nested).characters!!.getValue("g1"), null)
        assertEquals("male", character.staticProfile.gender)
        assertEquals("nested bg", character.staticProfile.background)
        assertEquals("nested sit", character.dynamicState.currentSituation)
        assertEquals("happy", character.dynamicState.currentMood)
    }

    @Test
    fun mapsGroupWithMembers() {
        val group = """
            {
              "characters": {
                "grp": {
                  "id": "grp",
                  "entityType": "group",
                  "basicInfo": { "name": "Group", "avatar": "👥" },
                  "dynamicState": { "currentSituation": "shared sit", "currentLocation": "shared loc" },
                  "groupInfo": {
                    "name": "The Crew", "avatar": "🎭", "description": "a group",
                    "scene": "tavern", "interactionRules": "be nice"
                  },
                  "members": [
                    { "id": "m1", "basicInfo": { "name": "Member A", "avatar": "🐱", "personality": "bold" },
                      "dynamicState": { "currentMood": "happy" }, "roleInGroup": "leader",
                      "lorebook": [ { "id": "ml1", "name": "Base", "content": "secret",
                        "origin": "user", "keywords": ["base"] } ] }
                  ]
                }
              }
            }
        """.trimIndent()
        val character = mapCharacter(parse(group).characters!!.getValue("grp"), null)
        assertTrue(character.isGroup)
        assertEquals("The Crew", character.name)
        assertEquals("🎭", character.emoji)
        assertEquals("a group", character.description)
        assertEquals("tavern", character.dynamicState.currentLocation)
        assertEquals("shared sit", character.groupSharedDynamic.currentSituation)
        assertEquals("tavern", character.groupSharedDynamic.currentLocation)

        assertEquals(1, character.members.size)
        val member = character.members.first()
        assertEquals("Member A", member.name)
        assertEquals("bold", member.staticProfile.personality)
        assertEquals("happy", member.dynamicState.currentMood)
        assertEquals(1, member.lorebook.size)
        assertEquals(LorebookOrigin.User, member.lorebook.first().origin)
    }

    @Test
    fun mapRoleFallsBackToAssistant() {
        assertEquals(Role.User, mapRole("user"))
        assertEquals(Role.Assistant, mapRole("assistant"))
        assertEquals(Role.System, mapRole("system"))
        assertEquals(Role.Tool, mapRole("tool"))
        assertEquals(Role.Assistant, mapRole(null))
        assertEquals(Role.Assistant, mapRole("something-else"))
    }

    @Test
    fun importServiceWritesThroughRepositories() = runTest {
        val characterDao = FakeCharacterDao()
        val messageDao = FakeMessageDao()
        val configDao = FakeConfigDao()
        val sink = RecordingSink()
        val service = LegacyImportService(
            characters = CharacterRepository(characterDao),
            chat = ChatRepository(messageDao),
            config = ConfigRepository(configDao),
            stickerSink = sink,
        )

        val summary = service.importJson(singleJson)

        assertEquals(1, summary.characters)
        assertEquals(1, summary.messages)
        assertEquals(1, summary.stickers)
        assertTrue(summary.configUpdated)

        assertEquals(1, characterDao.rows.size)
        val stored = CharacterRepository(characterDao).get("c1")
        assertNotNull(stored)
        assertEquals("Aria", stored!!.name)

        assertEquals(1, messageDao.rows.size)
        assertEquals("c1", messageDao.rows.values.first().characterId)

        val configEntity = configDao.get()
        assertNotNull(configEntity)
        val storedConfig = AppJson.decodeFromString<com.deeptalking.core.model.AppConfig>(configEntity!!.payload)
        assertEquals("custom", storedConfig.apiPlatform)
    }

    @Test
    fun exportProducesLegacyRootAndCanBeReimported() = runTest {
        val seedDao = FakeCharacterDao()
        val seedMessageDao = FakeMessageDao()
        val seedConfigDao = FakeConfigDao()
        val sink = RecordingSink()
        val characters = CharacterRepository(seedDao)
        val chat = ChatRepository(seedMessageDao)
        val config = ConfigRepository(seedConfigDao)

        LegacyImportService(characters, chat, config, sink).importJson(singleJson)

        val backup = BackupService(characters, chat, config)
        val exported = backup.exportJson()

        val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(exported) as JsonObject
        assertEquals("native", root["version"].toString().trim('"'))
        assertEquals(JsonNull, root["activeCharacterId"])
        assertEquals("\"\"", root["activeTheme"].toString())
        assertTrue(root["characters"] is JsonObject)
        val configJson = root["config"] as JsonObject
        assertNull(configJson["apiKey"])

        // Round-trip through a fresh store set.
        val fresh = LegacyImportService(
            CharacterRepository(FakeCharacterDao()),
            ChatRepository(FakeMessageDao()),
            ConfigRepository(FakeConfigDao()),
            sink,
        )
        val summary = fresh.importJson(exported)
        assertEquals(1, summary.characters)
        assertEquals(1, summary.stickers)
    }

    private class FakeCharacterDao : CharacterDao {
        val rows = LinkedHashMap<String, CharacterEntity>()
        override fun observeAll(): Flow<List<CharacterEntity>> = MutableStateFlow(rows.values.toList())
        override suspend fun get(id: String): CharacterEntity? = rows[id]
        override suspend fun upsert(entity: CharacterEntity) {
            rows[entity.id] = entity
        }

        override suspend fun upsertAll(entities: List<CharacterEntity>) {
            entities.forEach { rows[it.id] = it }
        }

        override suspend fun delete(id: String) {
            rows.remove(id)
        }

        override suspend fun deleteAll() {
            rows.clear()
        }

        override suspend fun count(): Int = rows.size
    }

    private class FakeMessageDao : MessageDao {
        val rows = LinkedHashMap<String, MessageEntity>()
        override fun observeForCharacter(characterId: String): Flow<List<MessageEntity>> =
            MutableStateFlow(rows.values.filter { it.characterId == characterId })

        override suspend fun getForCharacter(characterId: String): List<MessageEntity> =
            rows.values.filter { it.characterId == characterId }

        override suspend fun upsert(entity: MessageEntity) {
            rows[entity.id] = entity
        }

        override suspend fun upsertAll(entities: List<MessageEntity>) {
            entities.forEach { rows[it.id] = it }
        }

        override suspend fun deleteForCharacter(characterId: String) {
            rows.entries.removeAll { it.value.characterId == characterId }
        }

        override suspend fun deleteAll() {
            rows.clear()
        }
    }

    private class FakeConfigDao : ConfigDao {
        private val state = MutableStateFlow<ConfigEntity?>(null)
        override fun observe(): Flow<ConfigEntity?> = state
        override suspend fun get(): ConfigEntity? = state.value
        override suspend fun upsert(entity: ConfigEntity) {
            state.value = entity
        }
    }
}
