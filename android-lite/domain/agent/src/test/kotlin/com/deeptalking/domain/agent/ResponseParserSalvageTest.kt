package com.deeptalking.domain.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the output-salvage fallbacks ported from `src/js/api/responses.js`:
 * `extractResponsesText` / `extractReplyJsonFromAnyOutput` / `extractAnyResponseText`.
 */
class ResponseParserSalvageTest {

    private fun obj(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    @Test
    fun extractResponsesTextUsesTopLevelOutputText() {
        assertEquals("兜底正文", ResponseParser.extractResponsesText(obj("""{"output_text":"兜底正文"}""")))
    }

    @Test
    fun extractResponsesTextJoinsMessageParts() {
        val raw = """{"output":[{"type":"message","content":[{"type":"output_text","text":"a"},{"type":"output_text","text":"b"}]}]}"""
        assertEquals("ab", ResponseParser.extractResponsesText(obj(raw)))
    }

    @Test
    fun extractReplyJsonFromReasoningContent() {
        val raw = """{"output":[{"type":"reasoning","content":[{"type":"reasoning_text","text":"thought {\"reply\":\"从思考捞出的\"}"}]}]}"""
        val candidate = ResponseParser.extractReplyJsonFromAnyOutput(obj(raw))
        assertEquals("从思考捞出的", (ResponseParser.parseJsonLenient(candidate) as JsonObject)["reply"]!!.jsonPrimitive.content)
    }

    @Test
    fun extractReplyJsonFromReasoningSummary() {
        val raw = """{"output":[{"type":"reasoning","summary":[{"type":"reasoning_summary","text":"s {\"reply\":\"summary 捞的\"}"}]}]}"""
        assertTrue(ResponseParser.extractReplyJsonFromAnyOutput(obj(raw)).contains("summary 捞的"))
    }

    @Test
    fun extractReplyJsonFromExtraReasoningItems() {
        val extra = """{"type":"reasoning","content":[{"type":"reasoning_text","text":"{\"reply\":\"extra\"}"}]}"""
        assertTrue(ResponseParser.extractReplyJsonFromAnyOutput(null, listOf(extra)).contains("extra"))
    }

    @Test
    fun extractAnyResponseTextCollectsNonMessageOutputText() {
        val raw = """{"output":[{"type":"custom","content":[{"type":"output_text","text":"anywhere"}]}]}"""
        assertEquals("", ResponseParser.extractResponsesText(obj(raw)))
        assertEquals("anywhere", ResponseParser.extractAnyResponseText(obj(raw)))
    }

    @Test
    fun extractAnyResponseTextSkipsReasoningItems() {
        val raw = """{"output":[{"type":"reasoning","content":[{"type":"output_text","text":"secret"}]},{"type":"custom","content":[{"type":"output_text","text":"visible"}]}]}"""
        assertEquals("visible", ResponseParser.extractAnyResponseText(obj(raw)))
    }

    @Test
    fun parseJsonLenientEscapesRawControlCharacters() {
        val parsed = ResponseParser.parseJsonLenient("{\"reply\":\"line1\nline2\"}") as JsonObject
        assertEquals("line1\nline2", parsed["reply"]!!.jsonPrimitive.content)
    }

    @Test
    fun parseJsonLenientRetriesArrayShapeWithControlCharacters() {
        val parsed = ResponseParser.parseJsonLenient("[\"line1\nline2\"]") as JsonArray
        assertEquals("line1\nline2", parsed[0].jsonPrimitive.contentOrNull)
    }

    @Test
    fun parseJsonLenientRepairsUnescapedQuotesInsideReply() {
        val raw = "{\"reply\":\"她说 \"你好\" 然后笑了\",\"quickReplies\":[\"a\",\"b\"]}"
        val parsed = ResponseParser.parseJsonLenient(raw) as JsonObject
        assertEquals("她说 \"你好\" 然后笑了", parsed["reply"]!!.jsonPrimitive.content)
    }
}
