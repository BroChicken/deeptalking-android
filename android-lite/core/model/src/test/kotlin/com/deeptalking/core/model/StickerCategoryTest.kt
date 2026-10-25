package com.deeptalking.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerCategoryTest {

    @Test
    fun `legacy tags are inferred into the coarse categories`() {
        assertEquals("happy", StickerCategory.infer("大笑"))
        assertEquals("happy", StickerCategory.infer("干杯"))
        assertEquals("speechless", StickerCategory.infer("拒绝"))
        assertEquals("tired", StickerCategory.infer("睡觉"))
        assertEquals(StickerCategory.OTHER, StickerCategory.infer("吓死我了"))
    }

    @Test
    fun `explicit category wins over inference`() {
        assertEquals("animal", StickerCategory.of(Sticker(keyword = "笑死", category = "animal")))
        // 没有 category 的旧数据：按老标签推断，推断不出来的落「其他」。
        assertEquals("happy", StickerCategory.of(Sticker(keyword = "开心")))
        assertEquals(StickerCategory.OTHER, StickerCategory.of(Sticker(keyword = "无语凝噎")))
    }

    @Test
    fun `unknown category id falls back to other`() {
        assertEquals(StickerCategory.OTHER, StickerCategory.of(Sticker(keyword = "x", category = "bogus")))
        assertEquals("其他", StickerCategory.labelOf("bogus"))
        assertTrue(StickerCategory.isValid("affection"))
    }

    @Test
    fun `group keeps the fixed order and drops empty buckets`() {
        val groups = StickerCategory.group(
            listOf(
                Sticker(id = "1", keyword = "无语凝噎", category = "speechless"),
                Sticker(id = "2", keyword = "笑死我了", category = "happy"),
            ),
        )
        assertEquals(listOf("happy", "speechless"), groups.map { it.first.id })
        assertEquals(listOf("2"), groups[0].second.map { it.id })
    }
}
