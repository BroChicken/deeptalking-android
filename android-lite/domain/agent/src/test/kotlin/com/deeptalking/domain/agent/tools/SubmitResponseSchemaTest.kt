package com.deeptalking.domain.agent.tools

import com.deeptalking.core.model.Character
import com.deeptalking.domain.agent.AgentContext
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `submit_response` ships two schemas: the group one keeps `memberDynamicState` and every
 * `timeRef` site, the single-character one drops the group-only blocks (a solo character
 * can never use them). Both must stay valid JSON.
 */
class SubmitResponseSchemaTest {

    private val solo = Character(id = "c1", name = "小雨")
    private val group = Character(id = "g1", name = "三人组", isGroup = true)

    private fun schema(character: Character): String =
        SubmitResponseTool().definitionFor(AgentContext(character, character.id)).parametersJson

    @Test
    fun `single character schema drops the group-only blocks`() {
        val soloJson = schema(solo)
        val groupJson = schema(group)

        assertTrue(groupJson.contains("\"memberDynamicState\""))
        assertTrue(groupJson.contains("\"memberName\""))
        assertFalse(soloJson.contains("\"memberDynamicState\""))
        assertFalse(soloJson.contains("\"memberName\""))
        assertTrue(soloJson.length < groupJson.length)
    }

    @Test
    fun `timeRef keeps one full definition and points at it from everywhere else`() {
        val soloJson = schema(solo)
        val groupJson = schema(group)

        // 5 dynamicState + 10 staticFields (+5 member copies in the group schema) + longTerm
        assertEquals(16, Regex("\"timeRef\"").findAll(soloJson).count())
        assertEquals(21, Regex("\"timeRef\"").findAll(groupJson).count())
        // Only longTerm carries the full contract (anchor enum / slot enum / offsetDays range).
        assertEquals(1, Regex("\"day_after_tomorrow\"").findAll(soloJson).count())
        assertEquals(1, Regex("\"day_after_tomorrow\"").findAll(groupJson).count())
        assertEquals(15, soloJson.split("timeRef（本请求内已有完整定义）").size - 1)
        assertEquals(20, groupJson.split("timeRef（本请求内已有完整定义）").size - 1)
    }

    @Test
    fun `both schemas stay valid json`() {
        Json.parseToJsonElement(schema(solo))
        Json.parseToJsonElement(schema(group))
    }
}
