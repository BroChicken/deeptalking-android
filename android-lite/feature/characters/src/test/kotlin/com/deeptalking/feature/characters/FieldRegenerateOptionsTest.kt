package com.deeptalking.feature.characters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「重新生成字段」下拉的选项形状：**显示中文标签、回传英文键**。
 *
 * 这个测试是为了锁住一次真实的线上 bug：选项被写成 `(键, 标签)` 塞进
 * `LegacyDropdown`（它按 `(标签, 值)` 取值），结果是菜单里全是 `gender/age/…` 这样的
 * 英文、点一下选中值也不变，「重新生成」再把中文标签当字段名传给模型、静默失败。
 */
class FieldRegenerateOptionsTest {

    private val options = regenerateFieldOptions()

    @Test
    fun `first option is the avatar emoji`() {
        assertEquals("头像 emoji", options.first().first)
        assertEquals("emoji", options.first().second)
    }

    @Test
    fun `labels are chinese and values are the english field keys`() {
        assertEquals(11, options.size)
        val keys = options.map { it.second }.toSet()
        options.forEach { (label, key) ->
            assertTrue("标签要有中文：$label", label.any { it in '\u4e00'..'\u9fff' })
            assertFalse("标签不能就是英文键（顺序写反的表现）：$label", label in keys)
            assertTrue("值必须是英文键：$key", key.all { it in 'a'..'z' || it in 'A'..'Z' })
        }
    }

    @Test
    fun `values match the fields the model and the editor know`() {
        assertEquals(
            listOf(
                "gender", "age", "race", "appearance", "personality",
                "fears", "background", "speakingStyle", "language", "userAddress",
            ),
            options.drop(1).map { it.second },
        )
        assertEquals(STATIC_FIELDS.map { it.first }, options.drop(1).map { it.second })
    }

    @Test
    fun `every key maps back to its own label`() {
        assertEquals("头像 emoji", regenerateFieldLabel(options, "emoji"))
        assertEquals("性别", regenerateFieldLabel(options, "gender"))
        assertEquals("对用户的称呼", regenerateFieldLabel(options, "userAddress"))
    }

    @Test
    fun `a label is not a key so the old reversed mapping cannot pass`() {
        // 顺序写反时 regenField 会变成中文标签：这里必须回落成第一项，而不是原样显示。
        assertEquals("头像 emoji", regenerateFieldLabel(options, "性别"))
        assertEquals("头像 emoji", regenerateFieldLabel(options, ""))
    }
}
