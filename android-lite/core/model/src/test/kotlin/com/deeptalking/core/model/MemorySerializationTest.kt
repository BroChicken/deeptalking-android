package com.deeptalking.core.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorySerializationTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    @Test
    fun `legacy long-term json without optional fields decodes to defaults`() {
        val legacy = """{"id":"m1","category":"events","key":"一起看展","value":"用户与角色看展"}"""
        val item = json.decodeFromString(LongTermMemory.serializer(), legacy)
        assertEquals(MemoryCategory.Events, item.category)
        assertEquals(MemorySubject.Legacy, item.subject)
        assertTrue(item.participants.isEmpty())
        assertEquals("", item.location)
        assertEquals(null, item.arcOf)
        assertEquals(null, item.arcStage)
        assertEquals(PromiseStatus.Active, item.status)
    }

    @Test
    fun `short-term json keeps identity fields`() {
        val payload = """{"id":"s1","content":"用户2026-10-11去看展","sourceMessageIds":["m1"],
            "eventTime":"2026-10-11T02:00:00Z","participants":["用户"],"location":"美术馆"}"""
        val item = json.decodeFromString(ShortTermMemory.serializer(), payload)
        assertEquals(listOf("用户"), item.participants)
        assertEquals("美术馆", item.location)
        assertEquals("2026-10-11T02:00:00Z", item.eventTime)
    }

    @Test
    fun `tolerant userEvidence accepts legacy bare string`() {
        val payload = """{"id":"s1","content":"用户喜欢咖啡","userEvidence":"用户喜欢咖啡"}"""
        val item = json.decodeFromString(ShortTermMemory.serializer(), payload)
        assertEquals(1, item.userEvidence.size)
        assertEquals("用户喜欢咖啡", item.userEvidence.first().text)
    }

    @Test
    fun `legacy object-shaped correction before parses to the old value text`() {
        // The WebView wrote `before` as the whole pre-correction field object; reading it as a
        // primitive used to throw and drop the character on import.
        val payload = """
            {"id":"m1","category":"userProfile","key":"k","value":"v",
             "corrections":[{"at":"2026-01-01T00:00:00Z","evidence":"用户纠正",
               "before":{"value":"旧内容","subject":"user","key":"k","promisor":"user",
                         "promisee":"character","eventTime":null,"dueAt":null}}]}
        """.trimIndent()
        val item = json.decodeFromString(LongTermMemory.serializer(), payload)
        assertEquals(1, item.corrections.size)
        assertEquals("旧内容", item.corrections.single().before)
        assertEquals("用户纠正", item.corrections.single().evidence)
    }

    @Test
    fun `bare string correction before still parses and round-trips`() {
        val payload = """{"id":"m1","category":"userProfile","key":"k","value":"v","corrections":[{"before":"旧文本"}]}"""
        val item = json.decodeFromString(LongTermMemory.serializer(), payload)
        assertEquals("旧文本", item.corrections.single().before)

        val encoded = Json.encodeToString(LongTermMemory.serializer(), item)
        val back = json.decodeFromString(LongTermMemory.serializer(), encoded)
        assertEquals("旧文本", back.corrections.single().before)
    }

    @Test
    fun `object-shaped before without a value keeps the raw object text`() {
        val payload = """{"id":"m1","corrections":[{"before":{"subject":"user"}}]}"""
        val item = json.decodeFromString(LongTermMemory.serializer(), payload)
        assertTrue(item.corrections.single().before!!.contains("subject"))
    }

    @Test
    fun `corrections serialize before as a plain string`() {
        val item = LongTermMemory(
            id = "m1",
            corrections = listOf(MemoryCorrection(at = "t", evidence = "e", before = "旧值")),
        )
        val encoded = Json.encodeToString(LongTermMemory.serializer(), item)
        assertTrue(encoded.contains("\"before\":\"旧值\""))
    }

    @Test
    fun `missing before decodes to null and legacy without the field keeps working`() {
        val payload = """{"id":"m1","corrections":[{"at":"t","evidence":"e"}]}"""
        val item = json.decodeFromString(LongTermMemory.serializer(), payload)
        assertNull(item.corrections.single().before)
    }
}
