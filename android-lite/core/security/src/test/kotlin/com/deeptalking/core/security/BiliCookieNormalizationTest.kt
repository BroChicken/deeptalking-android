package com.deeptalking.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `SESSDATA` 的粘贴容错：用户可能粘整段 Cookie、只粘值、带引号或换行。
 * 归一化只做字符串处理，真正存哪、能不能进备份由 `SecretStore` 决定。
 */
class BiliCookieNormalizationTest {

    private val sessdata = "6c2f8a1e%2C1790000000%2C1c2d%2A11"

    @Test
    fun aBareSessdataGetsThePrefix() {
        assertEquals("SESSDATA=$sessdata", normalizeBiliCookie(sessdata))
    }

    @Test
    fun aFullCookieIsKeptAsIs() {
        // Cookie 值里不会出现空白，所以换行/空格一律去掉（换行多半是复制时折行），
        // `;` 后面少一个空格对服务端没有影响。
        assertEquals("buvid3=abc123;SESSDATA=$sessdata;bili_jct=deadbeef", normalizeBiliCookie("buvid3=abc123; SESSDATA=$sessdata; bili_jct=deadbeef"))
    }

    @Test
    fun quotesAndLineBreaksFromCopiedTextAreDropped() {
        assertEquals("SESSDATA=$sessdata", normalizeBiliCookie("  \"$sessdata\"\n"))
        assertEquals(
            "SESSDATA=$sessdata",
            normalizeBiliCookie("SESSDATA=" + sessdata.substring(0, 8) + "\n" + sessdata.substring(8)),
        )
    }

    @Test
    fun blankInputClearsTheSlot() {
        assertNull(normalizeBiliCookie(null))
        assertNull(normalizeBiliCookie("   "))
        assertNull(normalizeBiliCookie("\"\""))
    }
}
