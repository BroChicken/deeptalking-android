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
import com.deeptalking.core.model.AppConfig
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.LongTermMemory
import com.deeptalking.core.model.LorebookOrigin
import com.deeptalking.core.model.MemoryCategory
import com.deeptalking.core.model.MemorySubject
import com.deeptalking.core.model.PromiseStatus
import com.deeptalking.core.model.Role
import com.deeptalking.core.model.SceneState
import com.deeptalking.core.model.SceneSummary
import com.deeptalking.core.model.ShortTermMemory
import com.deeptalking.core.model.StaticFillMeta
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

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
        val imageCalls = mutableListOf<String>()
        override fun refFor(dataUri: String): String {
            calls += dataUri
            return "/tmp/stickers/${calls.size}.jpg"
        }

        override fun imageRefFor(dataUri: String): String {
            imageCalls += dataUri
            return "images/${imageCalls.size}.jpg"
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

        assertEquals(1, character.instant.size)
        val message = character.instant.first()
        assertEquals("m1", message.id)
        assertEquals(Role.User, message.role)
        assertEquals("hello", message.content)
        assertEquals("2026-01-01T00:00:00.000Z", message.timestamp)
        assertEquals(1, message.attachments.size)
        assertEquals("images/1.jpg", message.attachments.first().uri)

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
        // The seed carried `activeTheme: theme-blue`; it now round-trips.
        assertEquals("\"theme-blue\"", root["activeTheme"].toString())
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

    @Test
    fun mapConfigCoercesLegacyDeepseekChatAlias() {
        val deepseek = parse("""{ "config": { "apiPlatform": "deepseek", "modelName": "deepseek-chat" } }""").config
        assertEquals("deepseek-flash", mapConfig(deepseek).modelName)

        val custom = parse("""{ "config": { "apiPlatform": "custom", "modelName": "deepseek-chat" } }""").config
        assertEquals("deepseek-chat", mapConfig(custom).modelName)
    }

    @Test
    fun mapStickersDedupesByDataUrlAndNormalizesTag() {
        val json = """
            {
              "characters": {
                "c1": {
                  "id": "c1",
                  "stickers": [
                    { "id": "s1", "dataUrl": "data:image/png;base64,AAAA", "tag": " 开心 开心 " },
                    { "id": "s2", "dataUrl": "data:image/png;base64,AAAA", "tag": "duplicate" },
                    { "id": "s3", "dataUrl": "data:image/png;base64,BBBB", "tag": "一二三四五六七八" },
                    { "id": "s4", "tag": "no payload" }
                  ]
                }
              }
            }
        """.trimIndent()
        val character = mapCharacter(parse(json).characters!!.getValue("c1"), null)
        assertEquals(2, character.stickers.size)
        assertEquals("开心开心", character.stickers[0].tag)
        assertEquals("一二三四五六", character.stickers[1].tag)
    }

    @Test
    fun preservesExtendedMemoryFields() {
        val json = """
            {
              "characters": {
                "c1": {
                  "id": "c1", "basicInfo": { "name": "A" },
                  "memory": {
                    "shortTerm": [
                      { "id": "st1", "content": "x", "timestamp": "2026-01-01T00:00:00.000Z",
                        "analyzedAt": "2026-01-02T00:00:00.000Z",
                        "lorebookScannedAt": "2026-01-03T00:00:00.000Z",
                        "participants": ["Bob", "Aria"], "location": "forest" }
                    ],
                    "longTerm": {
                      "events": [
                        { "id": "lt1", "key": "k", "value": "v", "eventTime": "2026-01-01T00:00:00.000Z",
                          "participants": ["Aria"], "location": "forest", "learnedBonus": 2.5,
                          "usageCount": 4, "lastUsageAt": "2026-01-04T00:00:00.000Z",
                          "arcOf": "arc", "arcStage": "发展", "recordedAt": "2026-01-01T00:00:00.000Z" }
                      ]
                    }
                  }
                }
              }
            }
        """.trimIndent()
        val character = mapCharacter(parse(json).characters!!.getValue("c1"), null)
        val short = character.shortTerm.single()
        assertEquals("2026-01-02T00:00:00.000Z", short.analyzedAt)
        assertEquals("2026-01-03T00:00:00.000Z", short.lorebookScannedAt)
        assertEquals(listOf("Bob", "Aria"), short.participants)
        assertEquals("forest", short.location)
        val long = character.longTerm.single()
        assertEquals(listOf("Aria"), long.participants)
        assertEquals("forest", long.location)
        assertEquals(2, long.learnedBonus)
        assertEquals(4, long.usageCount)
        assertEquals("2026-01-04T00:00:00.000Z", long.lastUsageAt)
        assertEquals("arc", long.arcOf)
        assertEquals("发展", long.arcStage)
        assertEquals("2026-01-01T00:00:00.000Z", long.recordedAt)
    }

    @Test
    fun decodeImportBytesHandlesUtf8AndGbk() {
        val json = """{"name":"中文备份"}"""
        assertEquals(json, decodeImportBytes(json.toByteArray(Charsets.UTF_8)))
        val gbkBytes = "中文备份".toByteArray(Charset.forName("GBK"))
        assertEquals("中文备份", decodeImportBytes(gbkBytes))
    }

    @Test
    fun reconcileLegacyMemoriesMergesMatchingEventIdentity() {
        val early = "2026-01-01T10:00:00Z"
        val late = "2026-06-01T10:00:00Z"
        val character = Character(
            id = "c1",
            shortTerm = listOf(
                ShortTermMemory(id = "a", content = "first", eventTime = early, sourceMessageIds = listOf("m1")),
                ShortTermMemory(id = "b", content = "second", eventTime = early, sourceMessageIds = listOf("m2")),
                ShortTermMemory(id = "c", content = "other", eventTime = late),
            ),
            longTerm = listOf(
                LongTermMemory(id = "e1", category = MemoryCategory.Events, value = "one", eventTime = early, tags = listOf("t1"), participants = listOf("A"), location = "x"),
                LongTermMemory(id = "e2", category = MemoryCategory.Events, value = "two", eventTime = early, tags = listOf("t2"), participants = listOf("B"), location = "y"),
                LongTermMemory(id = "p1", category = MemoryCategory.Promises, value = "keep", eventTime = early),
            ),
        )
        val result = reconcileLegacyMemories(character)
        assertEquals(2, result.repaired)
        assertEquals(2, result.character.shortTerm.size)
        assertEquals(2, result.character.longTerm.size)
        val mergedShort = result.character.shortTerm.first { it.id == "a" }
        assertTrue(mergedShort.content.contains("first"))
        assertTrue(mergedShort.content.contains("second"))
        val mergedEvent = result.character.longTerm.first { it.id == "e1" }
        assertEquals(setOf("t1", "t2"), mergedEvent.tags.toSet())
        assertEquals(setOf("A", "B"), mergedEvent.participants.toSet())
        assertNotNull(result.character.longTerm.firstOrNull { it.id == "p1" })
        assertTrue(eventIdentity(null, emptyList(), "") == "")
    }

    @Test
    fun ensureMessageSequencesNumbersInListOrder() {
        val messages = listOf(
            ChatMessage(id = "1", role = Role.User),
            ChatMessage(id = "2", role = Role.Assistant),
            ChatMessage(id = "3", role = Role.User),
        )
        assertEquals(listOf(1, 2, 3), ensureMessageSequences(messages))
        assertTrue(ensureMessageSequences(emptyList()).isEmpty())
    }

    @Test
    fun exportRoundTripsNativeOnlyFields() = runTest {
        val characterDao = FakeCharacterDao()
        val characters = CharacterRepository(characterDao)
        val chat = ChatRepository(FakeMessageDao())
        val config = ConfigRepository(FakeConfigDao())
        characters.upsert(
            Character(
                id = "c1",
                name = "Aria",
                instant = listOf(
                    ChatMessage(
                        id = "m1",
                        role = Role.User,
                        content = "hi",
                        timestamp = "2026-01-01T00:00:00Z",
                        sequence = 5,
                        extractedAt = "2026-01-02T00:00:00Z",
                    ),
                ),
                scenes = listOf(
                    SceneSummary(id = "sc1", key = "forest", content = "scene one", startedAt = "2026-01-02T00:00:00Z", endedAt = "2026-01-02T00:05:00Z", createdAt = "2026-01-02T00:05:00Z"),
                ),
                sceneState = SceneState(key = "forest", startCount = 3, startSequence = 5, messageCount = 5),
                pendingRecall = listOf(
                    LongTermMemory(id = "pr1", category = MemoryCategory.UserProfile, key = "tea", value = "user likes tea", tags = listOf("tea")),
                ),
                staticFillMeta = StaticFillMeta(attemptedAt = "2026-01-01T00:00:00Z", failures = 2, retryAt = "2026-01-05T00:00:00Z"),
                timeParseVersion = 1,
                lorebookMigratedAt = "2026-01-03T00:00:00Z",
            )
        )
        config.update(AppConfig(modelName = "deepseek-flash"))

        val exported = BackupService(characters, chat, config).exportJson(activeCharacterId = "c1")
        val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(exported) as JsonObject
        assertEquals("\"c1\"", root["activeCharacterId"].toString())
        val characterJson = (root["characters"] as JsonObject)["c1"] as JsonObject
        assertEquals("1", characterJson["timeParseVersion"].toString())
        assertEquals("\"2026-01-03T00:00:00Z\"", characterJson["lorebookMigratedAt"].toString())
        val memory = characterJson["memory"] as JsonObject
        assertEquals(1, (memory["scenes"] as JsonArray).size)
        assertEquals("forest", ((memory["sceneState"] as JsonObject)["key"] as JsonPrimitive).content)
        assertEquals(1, (memory["pendingRecall"] as JsonArray).size)
        val instant = (memory["instant"] as JsonArray)[0] as JsonObject
        assertEquals("\"2026-01-02T00:00:00Z\"", instant["extractedAt"].toString())

        val freshDao = FakeCharacterDao()
        val fresh = LegacyImportService(
            CharacterRepository(freshDao),
            ChatRepository(FakeMessageDao()),
            ConfigRepository(FakeConfigDao()),
            null,
        )
        assertEquals(1, fresh.importJson(exported).characters)
        val restored = CharacterRepository(freshDao).get("c1")!!
        assertEquals(1, restored.timeParseVersion)
        assertEquals("2026-01-03T00:00:00Z", restored.lorebookMigratedAt)
        assertEquals(MemoryCategory.UserProfile, restored.pendingRecall.single().category)
        assertEquals(listOf("tea"), restored.pendingRecall.single().tags)
        assertEquals("forest", restored.sceneState?.key)
        assertEquals(5, restored.sceneState?.startSequence)
        assertEquals(3, restored.sceneState?.startCount)
        assertEquals(1, restored.scenes.size)
        assertEquals("forest", restored.scenes.single().key)
        assertEquals("2026-01-02T00:00:00Z", restored.instant.single().extractedAt)
        assertEquals(2, restored.staticFillMeta?.failures)
    }

    @Test
    fun legacyArrayUserEvidenceAndObjectConflictsImportInsteadOfBeingDropped() = runTest {
        val legacy = """
        {
          "config": { "apiPlatform": "deepseek" },
          "characters": {
            "c1": {
              "id": "c1",
              "entityType": "character",
              "basicInfo": { "name": "Aria" },
              "memory": {
                "shortTerm": [
                  { "id": "st1", "content": "they met", "timestamp": "2026-01-01T00:01:00.000Z",
                    "sourceMessageIds": ["m1"], "sourceRoles": ["user"],
                    "userEvidence": [ { "sourceMessageId": "m1", "text": "we met yesterday" } ] }
                ],
                "longTerm": {
                  "userProfile": [
                    { "id": "lt1", "key": "name", "value": "Aria likes tea", "subject": "user",
                      "sourceMessageIds": ["m1"], "evidence": "I like tea",
                      "userEvidence": [ { "sourceMessageId": "m1", "text": "I like tea" } ],
                      "conflicts": [ { "value": "Aria hates tea", "evidence": "no", "sourceMessageIds": ["m1"], "at": "2026-01-01T00:00:00.000Z" } ] }
                  ]
                }
              }
            }
          }
        }
        """.trimIndent()

        val dao = FakeCharacterDao()
        val service = LegacyImportService(
            CharacterRepository(dao),
            ChatRepository(FakeMessageDao()),
            ConfigRepository(FakeConfigDao()),
            null,
        )
        val summary = service.importJson(legacy)

        assertEquals(0, summary.skipped)
        assertEquals(1, summary.characters)
        val stored = CharacterRepository(dao).get("c1")!!
        assertEquals("we met yesterday", stored.shortTerm.single().userEvidence.single().text)
        assertEquals(listOf("Aria hates tea"), stored.longTerm.single().conflicts.map { it.value })
        assertEquals("no", stored.longTerm.single().conflicts.single().evidence)
        assertEquals("I like tea", stored.longTerm.single().userEvidence.single().text)
    }

    @Test
    fun legacyStringUserEvidenceStillDecodes() {
        val dto = parse(
            """{ "characters": { "c1": { "memory": { "shortTerm": [ { "id": "st1", "userEvidence": "legacy string" } ] } } } }""",
        )
        val st = dto.characters!!.getValue("c1").memory!!.shortTerm!!.single()
        assertEquals("legacy string", st.userEvidence.single().text)
    }

    @Test
    fun importPreservesNativeOnlyConfigFields() = runTest {
        val configDao = FakeConfigDao()
        val config = ConfigRepository(configDao)
        config.update(
            AppConfig(
                ttsEnabled = true,
                ttsVoiceFile = "builtin_1.gguf",
                ttsStyle = "温柔",
                lastReplyDebug = "dbg",
            ),
        )
        val service = LegacyImportService(
            CharacterRepository(FakeCharacterDao()),
            ChatRepository(FakeMessageDao()),
            config,
            null,
        )
        service.importJson("""{ "config": { "apiPlatform": "deepseek", "temperature": 1.1 } }""")

        val stored = config.current()
        assertTrue(stored.ttsEnabled)
        assertEquals("builtin_1.gguf", stored.ttsVoiceFile)
        assertEquals("温柔", stored.ttsStyle)
        assertEquals("dbg", stored.lastReplyDebug)
        assertEquals(1.1, stored.temperature, 0.0001)
    }

    @Test
    fun importReportsSkippedCharactersInsteadOfSilentlyDropping() = runTest {
        val json = """{ "characters": { "ok": { "id": "ok", "basicInfo": { "name": "A" } }, "bad": { "id": "bad", "basicInfo": 5 } } }"""
        val service = LegacyImportService(
            CharacterRepository(FakeCharacterDao()),
            ChatRepository(FakeMessageDao()),
            ConfigRepository(FakeConfigDao()),
            null,
        )
        val summary = service.importJson(json)
        assertEquals(1, summary.characters)
        assertEquals(1, summary.skipped)
    }

    @Test
    fun importsRealLegacyBackupWithArrayUserEvidence() = runTest {
        // A real character captured from save/deeptalking_backup_2026-10-02.json;
        // every short-term item carries the legacy array-shaped `userEvidence`
        // that used to make the whole character fail to decode and be dropped.
        val json = javaClass.classLoader
            .getResourceAsStream("legacy-character-user-evidence.json")
            ?.bufferedReader()?.readText()
            ?: error("missing test resource: legacy-character-user-evidence.json")

        val dao = FakeCharacterDao()
        val summary = LegacyImportService(
            CharacterRepository(dao),
            ChatRepository(FakeMessageDao()),
            ConfigRepository(FakeConfigDao()),
            null,
        ).importJson(json)

        assertEquals(0, summary.skipped)
        assertEquals(1, summary.characters)
        val stored = CharacterRepository(dao).get(summary.activeCharacterId!!)!!
        assertEquals("瑟瑞斯", stored.name)
        assertTrue(stored.shortTerm.isNotEmpty())
        assertTrue(stored.shortTerm.any { it.userEvidence.any { ev -> ev.text.isNotBlank() } })
    }

    @Test
    fun ensureMessageSequencesPreservesIncreasingStoredValues() {
        val messages = listOf(
            ChatMessage(id = "1", role = Role.User, sequence = 3),
            ChatMessage(id = "2", role = Role.User, sequence = 0),
            ChatMessage(id = "3", role = Role.User, sequence = 1),
            ChatMessage(id = "4", role = Role.User, sequence = 9),
        )
        assertEquals(listOf(3, 4, 5, 9), ensureMessageSequences(messages))
        assertEquals(listOf(3, 11, 12, 13), ensureMessageSequences(messages, 10))
    }

    @Test
    fun mapCharacterRepairsSequencesAndResetsSceneMessageCount() {
        val json = """
            {
              "characters": {
                "c": {
                  "id": "c", "basicInfo": { "name": "A" },
                  "memory": {
                    "instant": [
                      { "id": "m1", "role": "user", "content": "a", "sequence": 2 },
                      { "id": "m2", "role": "assistant", "content": "b", "sequence": 1 }
                    ],
                    "counters": { "messageSequence": 7 },
                    "sceneState": { "key": "k", "startCount": 1, "messageCount": 42 }
                  }
                }
              }
            }
        """.trimIndent()
        val character = mapCharacter(parse(json).characters!!.getValue("c"), null)
        assertEquals(listOf(2, 8), character.instant.map { it.sequence })
        assertEquals(8, character.counters.messageSequence)
        assertEquals(2, character.sceneState?.startSequence)
        assertEquals(0, character.sceneState?.messageCount)
    }

    @Test
    fun mapLongTermDedupesNonEventsByKeyKeepingNewest() {
        val json = """
            {
              "characters": {
                "c": {
                  "id": "c", "basicInfo": { "name": "A" },
                  "memory": {
                    "longTerm": {
                      "userProfile": [
                        { "id": "a", "key": "Fav", "value": "tea", "tags": ["drink"], "importance": 3,
                          "updatedAt": "2026-01-01T00:00:00Z", "createdAt": "2026-01-01T00:00:00Z" },
                        { "id": "b", "key": "fav", "value": "coffee", "tags": ["warm"], "importance": 7,
                          "updatedAt": "2026-02-01T00:00:00Z", "createdAt": "2026-02-01T00:00:00Z" }
                      ]
                    }
                  }
                }
              }
            }
        """.trimIndent()
        val character = mapCharacter(parse(json).characters!!.getValue("c"), null)
        assertEquals(1, character.longTerm.size)
        val item = character.longTerm.single()
        assertEquals("a", item.id)
        assertEquals("coffee", item.value)
        assertEquals(7, item.importance)
        assertEquals(setOf("drink", "warm"), item.tags.toSet())
    }

    @Test
    fun mapMembersFiltersBlankNamesAndCapsAtEight() {
        val members = (1..10).joinToString(",") { i ->
            val name = if (i <= 9) "\"M$i\"" else "\"\""
            """{ "id": "m$i", "basicInfo": { "name": $name, "avatar": "🐱" } }"""
        }
        val json = """
            {
              "characters": {
                "g": {
                  "id": "g", "entityType": "group",
                  "basicInfo": { "name": "G", "avatar": "👥" },
                  "members": [$members]
                }
              }
            }
        """.trimIndent()
        val character = mapCharacter(parse(json).characters!!.getValue("g"), null)
        assertEquals(8, character.members.size)
        assertTrue(character.members.all { it.name.isNotBlank() })
    }

    @Test
    fun mapCharacterSanitizesDamagedAvatarAndFlagsRepair() {
        val damaged = mapCharacter(
            parse("""{ "characters": { "c": { "id": "c", "basicInfo": { "name": "A", "avatar": "ascii" } } } }""")
                .characters!!.getValue("c"),
            null,
        )
        assertEquals("👤", damaged.emoji)
        assertTrue(damaged.avatarRepairPending)

        val good = mapCharacter(
            parse("""{ "characters": { "c": { "id": "c", "basicInfo": { "name": "A", "avatar": "🦊" } } } }""")
                .characters!!.getValue("c"),
            null,
        )
        assertEquals("🦊", good.emoji)
        assertFalse(good.avatarRepairPending)
    }

    @Test
    fun mapInstantFiltersLabelsAndNormalizesExtractedAt() {
        val json = """
            {
              "characters": {
                "c": {
                  "id": "c", "basicInfo": { "name": "A" },
                  "memory": {
                    "instant": [
                      { "id": "m1", "role": "assistant", "content": "x", "extractedAt": "not-a-date",
                        "styleViolations": ["metaTalk", "bogus"], "quickReplyIssues": ["missing", "nope"] },
                      { "id": "m2", "role": "assistant", "content": "y", "extractedAt": "2026-01-01T00:00:00Z" }
                    ]
                  }
                }
              }
            }
        """.trimIndent()
        val character = mapCharacter(parse(json).characters!!.getValue("c"), null)
        val m1 = character.instant.first { it.id == "m1" }
        assertNull(m1.extractedAt)
        assertEquals(listOf("metaTalk"), m1.styleViolations)
        assertEquals(listOf("missing"), m1.quickReplyIssues)
        val m2 = character.instant.first { it.id == "m2" }
        assertEquals("2026-01-01T00:00:00Z", m2.extractedAt)
    }

    @Test
    fun mapConfigNormalizesPlatformAndRebuildsAllSlots() {
        val unknown = mapConfig(parse("""{ "config": { "apiPlatform": "weird", "apiBaseUrl": "https://x/v1/" } }""").config)
        assertEquals("custom", unknown.apiPlatform)
        assertEquals("https://x/v1", unknown.apiBaseUrl)
        assertEquals(setOf("deepseek", "opencode", "custom"), unknown.platformSettings.keys)
        assertEquals("https://api.deepseek.com/v1", unknown.platformSettings.getValue("deepseek").baseUrl)
        assertEquals("deepseek-flash", unknown.platformSettings.getValue("deepseek").modelName)
        assertEquals("https://x/v1", unknown.platformSettings.getValue("custom").baseUrl)

        val blank = mapConfig(parse("""{ "config": { "apiPlatform": "" } }""").config)
        assertEquals("custom", blank.apiPlatform)

        val opencode = mapConfig(parse("""{ "config": { "apiPlatform": "opencode" } }""").config)
        assertEquals("https://opencode.ai/zen/go/v1", opencode.apiBaseUrl)
        assertEquals("https://opencode.ai/zen/go/v1", opencode.platformSettings.getValue("opencode").baseUrl)
    }

    @Test
    fun importFoldsGlobalStickersIntoActiveCharacter() = runTest {
        val json = """
            {
              "characters": {
                "a": { "id": "a", "basicInfo": { "name": "A" },
                  "stickers": [ { "id": "s", "dataUrl": "data:image/png;base64,AAAA", "tag": "x" } ] },
                "b": { "id": "b", "basicInfo": { "name": "B" } }
              },
              "activeCharacterId": "b",
              "stickers": [ { "id": "g", "dataUrl": "data:image/png;base64,BBBB", "tag": "y" } ]
            }
        """.trimIndent()
        val dao = FakeCharacterDao()
        LegacyImportService(
            CharacterRepository(dao),
            ChatRepository(FakeMessageDao()),
            ConfigRepository(FakeConfigDao()),
            null,
        ).importJson(json)
        val repo = CharacterRepository(dao)
        assertTrue(repo.get("b")!!.stickers.isNotEmpty())
        assertEquals(1, repo.get("a")!!.stickers.size)
    }

    @Test
    fun importWhitelistsActiveTheme() = runTest {
        val config = ConfigRepository(FakeConfigDao())
        val service = LegacyImportService(
            CharacterRepository(FakeCharacterDao()),
            ChatRepository(FakeMessageDao()),
            config,
            null,
        )
        service.importJson("""{ "activeTheme": "theme-black" }""")
        assertEquals("theme-black", config.current().activeTheme)

        service.importJson("""{ "activeTheme": "theme-neon" }""")
        assertEquals("", config.current().activeTheme)
    }

    @Test
    fun exportWritesInjectedAppVersion() = runTest {
        val backup = BackupService(
            CharacterRepository(FakeCharacterDao()),
            ChatRepository(FakeMessageDao()),
            ConfigRepository(FakeConfigDao()),
            appVersion = "1.3.6",
        )
        val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(backup.exportJson()) as JsonObject
        assertEquals("\"1.3.6\"", root["version"].toString())
    }

    @Test
    fun characterRepositoryReportsCorruptRowsOnce() = runTest {
        val dao = FakeCharacterDao()
        dao.rows["bad"] = CharacterEntity(id = "bad", updatedAt = "2026-01-01T00:00:00Z", payload = "{not json")
        val reported = mutableListOf<Pair<String, String>>()
        val repo = CharacterRepository(dao) { id, payload -> reported += id to payload }

        assertNull(repo.get("bad"))
        assertNull(repo.get("bad"))
        assertEquals(1, reported.size)
        assertEquals("bad", reported.single().first)
        assertEquals("{not json", reported.single().second)
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
