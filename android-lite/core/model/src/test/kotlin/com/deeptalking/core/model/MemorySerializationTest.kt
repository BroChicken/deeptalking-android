package com.deeptalking.core.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
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
}
