package com.deeptalking.domain.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class TimeUtilsTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val base: ZonedDateTime =
        ZonedDateTime.parse("2026-10-01T18:12:01.439Z").withZoneSameInstant(zone)

    @Test
    fun explicitTimestampKeepsItsOffsetAndPrecision() {
        val resolved = resolveTimeRef(TimeRef(explicit = "2026-10-02T02:12:01+08:00"), base, zone)
        assertEquals(Instant.parse("2026-10-01T18:12:01Z").toString(), resolved?.iso)
    }

    @Test
    fun dateOnlyReferenceInventsNoNoon() {
        val resolved = resolveTimeRef(TimeRef(explicit = "2026-10-02"), base, zone)
        assertNull(resolved?.iso)
        assertEquals("2026-10-02", resolved?.day)
    }

    @Test
    fun anchorTodayResolvesToLogicalDayWithNoInstant() {
        val resolved = resolveTimeRef(TimeRef(anchor = "today"), base, zone)
        assertNull(resolved?.iso)
        assertEquals("2026-10-02", resolved?.day)
    }

    @Test
    fun emptyTimeRefResolvesToNull() {
        assertNull(resolveTimeRef(TimeRef(), base, zone))
    }

    @Test
    fun anchorWithSlotKeepsSlotAndProducesInstant() {
        val resolved = resolveTimeRef(TimeRef(anchor = "tomorrow", slot = "下午"), base, zone)
        assertEquals("下午", resolved?.slot)
        assertEquals("2026-10-03 下午", resolved?.text)
    }

    @Test
    fun logicalDayStartsAtTwoAm() {
        assertEquals("2026-10-02", formatAbsoluteDate(logicalDay(base)))
        val oneAm = base.withHour(1).withMinute(0)
        assertEquals("2026-10-01", formatAbsoluteDate(logicalDay(oneAm)))
    }

    @Test
    fun parseRelativeTextRewritesDayWordsToAbsoluteDate() {
        val tomorrow = parseRelativeText("明天下午见面", base, zone)
        assertTrue(tomorrow.startsWith("2026-10-03 下午"))
        assertFalse(tomorrow.contains("明天"))
        assertEquals("2026-09-30发生的事", parseRelativeText("前天发生的事", base, zone))
    }

    // ---- 地点不带时间（stripTimeExpressions） ----------------------------------

    @Test
    fun stripTimeExpressionsDropsDatesSlotsAndClock() {
        // parseRelativeText 先把"今天"写成绝对日期，位置里再剥掉——位置只该是地点。
        val withTime = parseRelativeText("今天下午3点 在家里的客厅", base, zone)
        assertTrue(withTime.contains("2026-10-02"))
        assertEquals("在家里的客厅", stripTimeExpressions(withTime))
        assertEquals("学校天台", stripTimeExpressions("2026-10-05 傍晚 学校天台"))
        assertEquals("咖啡馆靠窗的位置", stripTimeExpressions("昨天 咖啡馆靠窗的位置"))
    }

    @Test
    fun stripTimeExpressionsKeepsAPlainPlaceUntouched() {
        assertEquals("客厅", stripTimeExpressions("客厅"))
        assertEquals("在客厅的沙发上", stripTimeExpressions("在客厅的沙发上"))
        assertEquals("", stripTimeExpressions("2026-10-02 下午"))
        assertEquals("", stripTimeExpressions(""))
    }
}
