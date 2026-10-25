package com.deeptalking.lite

import org.junit.Assert.assertEquals
import org.junit.Test

class StickerKeywordTest {

    @Test
    fun `strips whitespace and punctuation`() {
        assertEquals("吓死我了", sanitizeKeyword(" 吓死 我了！ "))
        assertEquals("无语凝噎", sanitizeKeyword("「无语凝噎」"))
    }

    @Test
    fun `caps at seven chars`() {
        assertEquals(7, sanitizeKeyword("一二三四五六七八九十").length)
        assertEquals("一二三四五六七", sanitizeKeyword("一二三四五六七八九十"))
    }

    @Test
    fun `blank or punctuation-only input collapses to empty`() {
        assertEquals("", sanitizeKeyword("   "))
        assertEquals("", sanitizeKeyword("，。！"))
    }
}
