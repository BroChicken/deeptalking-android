package com.deeptalking.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupSpeechTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun chatMessageWithSegmentsRoundTrips() {
        val message = ChatMessage(
            id = "m1",
            role = Role.Assistant,
            content = "张三：早\n李四：早啊",
            segments = listOf(
                SpeechSegment(speaker = "张三", text = "早"),
                SpeechSegment(speaker = "李四", text = "早啊"),
            ),
        )

        val encoded = json.encodeToString(ChatMessage.serializer(), message)
        assertTrue(encoded.contains("\"segments\""))

        val decoded = json.decodeFromString(ChatMessage.serializer(), encoded)
        assertEquals(message, decoded)
        assertEquals(2, decoded.segments.size)
        assertEquals("张三", decoded.segments[0].speaker)
    }

    @Test
    fun messageWithoutSegmentsEncodesAsEmptyListAndDecodes() {
        val message = ChatMessage(id = "m2", role = Role.User, content = "在吗")
        val encoded = json.encodeToString(ChatMessage.serializer(), message)
        assertTrue(encoded.contains("\"segments\":[]"))

        // A payload written before segments existed still decodes to an empty list.
        val legacyPayload = """{"id":"m3","role":"assistant","content":"旧消息"}"""
        val decoded = json.decodeFromString(ChatMessage.serializer(), legacyPayload)
        assertEquals(emptyList<SpeechSegment>(), decoded.segments)
        assertEquals("旧消息", decoded.content)
    }

    @Test
    fun parsesMembersWithFullWidthAndHalfWidthColons() {
        val reply = "张三：今天去哪？\n李四:我随便，听你的。"
        val segments = parseGroupSpeechSegments(reply, listOf("张三", "李四"))

        assertEquals(2, segments.size)
        assertEquals(SpeechSegment("张三", "今天去哪？"), segments[0])
        assertEquals(SpeechSegment("李四", "我随便，听你的。"), segments[1])
    }

    @Test
    fun keepsMultiLineTurnUnderOneSpeaker() {
        val reply = "张三：今天去哪？\n随便走走也行。\n李四：好。"
        val segments = parseGroupSpeechSegments(reply, listOf("张三", "李四"))

        assertEquals(2, segments.size)
        assertEquals("今天去哪？\n随便走走也行。", segments[0].text)
        assertEquals("李四", segments[1].speaker)
    }

    @Test
    fun keepsQuotesAndStageDirectionsForDisplay() {
        val reply = "张三：「（笑）今天去哪？」\n李四：**好**。"
        val segments = parseGroupSpeechSegments(reply, listOf("张三", "李四"))

        // Display text is verbatim: unlike extractSpeechSegments, nothing is stripped.
        assertEquals("「（笑）今天去哪？」", segments[0].text)
        assertEquals("**好**。", segments[1].text)
    }

    @Test
    fun unknownSpeakerIsNotGuessed() {
        // 王五 is not a member: the line stays inside the current speaker's turn.
        val reply = "张三：早\n王五：嗯"
        val segments = parseGroupSpeechSegments(reply, listOf("张三", "李四"))

        assertEquals(1, segments.size)
        assertEquals("张三", segments[0].speaker)
        assertEquals("早\n王五：嗯", segments[0].text)
    }

    @Test
    fun noKnownMemberLineFallsBackToSingleUnattributedSegment() {
        val reply = "大家安静一下，我有话要说。"
        val segments = parseGroupSpeechSegments(reply, listOf("张三", "李四"))

        assertEquals(1, segments.size)
        assertFalse(segments[0].isAttributed)
        assertEquals(reply, segments[0].text)
    }

    @Test
    fun noColonIsNotParsedAsSpeaker() {
        val reply = "张三 今天去哪\n李四 随便"
        val segments = parseGroupSpeechSegments(reply, listOf("张三", "李四"))

        assertEquals(1, segments.size)
        assertEquals("", segments[0].speaker)
        assertEquals(reply, segments[0].text)
    }

    @Test
    fun longestMemberNameWins() {
        val segments = parseGroupSpeechSegments("张三丰：在。", listOf("张三", "张三丰"))
        assertEquals(SpeechSegment("张三丰", "在。"), segments.single())
    }

    @Test
    fun emptyMemberListKeepsWholeReplyAsOneSegment() {
        val segments = parseGroupSpeechSegments("张三：早", emptyList())
        assertEquals(listOf(SpeechSegment(text = "张三：早")), segments)
    }

    @Test
    fun blankInputIsEmpty() {
        assertTrue(parseGroupSpeechSegments("   \n ", listOf("张三")).isEmpty())
    }

    @Test
    fun speechExtractionStillReturnsEmptySpeakerForUnattributedTurns() {
        val segments = extractSpeechSegments("（笑）你好。", listOf("张三"))
        assertEquals("", segments.single().speaker)
        assertEquals("你好。", segments.single().text)
    }

    @Test
    fun displaySegmentsFallBackToRawTextForLegacyGroupMessages() {
        val group = Character(
            id = "g1",
            isGroup = true,
            members = listOf(GroupMember(id = "m1", name = "张三"), GroupMember(id = "m2", name = "李四")),
        )
        val message = ChatMessage(
            id = "a1",
            role = Role.Assistant,
            content = "张三：早\n李四：早啊",
        )

        assertEquals(
            listOf(SpeechSegment("张三", "早"), SpeechSegment("李四", "早啊")),
            displaySpeechSegments(message, group),
        )
    }

    @Test
    fun displaySegmentsDoesNotSplitSingletonsOrUnattributedGroups() {
        val single = Character(id = "c1", name = "Alice")
        val group = Character(
            id = "g1",
            isGroup = true,
            members = listOf(GroupMember(id = "m1", name = "张三")),
        )
        val text = ChatMessage(id = "a1", role = Role.Assistant, content = "张三：早")

        assertTrue(displaySpeechSegments(text, single).isEmpty())
        assertTrue(displaySpeechSegments(text.copy(content = "没有成员名"), group).isEmpty())
    }

    @Test
    fun displaySegmentsPreferPersistedSegments() {
        val group = Character(
            id = "g1",
            isGroup = true,
            members = listOf(GroupMember(id = "m1", name = "张三")),
        )
        val persisted = listOf(SpeechSegment("张三", "存下来的"))
        val message = ChatMessage(
            id = "a1",
            role = Role.Assistant,
            content = "张三：原文",
            segments = persisted,
        )

        assertEquals(persisted, displaySpeechSegments(message, group))
    }
}
