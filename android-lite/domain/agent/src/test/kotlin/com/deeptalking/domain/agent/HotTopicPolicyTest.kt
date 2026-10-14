package com.deeptalking.domain.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * 「热榜开场」的判定与记账（[HotTopicPolicy]）：每天只掷一次、逻辑日 2 点换日、
 * 每角色每周最多 2 次、没命中也要吃掉当天的机会。
 */
class HotTopicPolicyTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    // 2026-10-12 是周一：本周 = 10-12~10-18，下周 = 10-19~10-25。
    private val weekADay1 = Instant.parse("2026-10-14T04:00:00Z") // 10-14 12:00 本地
    private val weekADay2 = Instant.parse("2026-10-15T04:00:00Z")
    private val weekADay3 = Instant.parse("2026-10-16T04:00:00Z")
    private val weekBDay1 = Instant.parse("2026-10-19T04:00:00Z")

    private fun roll(
        now: Instant,
        counters: HotTopicCounters = HotTopicCounters(),
        random: Double,
        probability: Double = HotTopicPolicy.PROBABILITY,
        weeklyLimit: Int = HotTopicPolicy.WEEKLY_LIMIT,
    ) = HotTopicPolicy.roll(
        now = now,
        counters = counters,
        random = random,
        probability = probability,
        weeklyLimit = weeklyLimit,
        zone = zone,
    )

    @Test
    fun `命中后当天不再掷，计数只加一次`() {
        val first = roll(weekADay1, random = 0.05)
        assertTrue(first.rolled)
        assertTrue(first.triggered)
        assertEquals("2026-10-14", first.counters.rolledDay)
        assertEquals("2026-10-12", first.counters.weekStart)
        assertEquals(1, first.counters.weekCount)
        assertEquals(1, first.counters.total)

        // 同一天稍后再来一轮：不再掷，计数原样。
        val second = roll(Instant.parse("2026-10-14T09:00:00Z"), first.counters, random = 0.05)
        assertFalse(second.rolled)
        assertFalse(second.triggered)
        assertEquals(first.counters, second.counters)
    }

    @Test
    fun `没命中也吃掉当天的机会`() {
        val miss = roll(weekADay1, random = 0.5)
        assertTrue(miss.rolled)
        assertFalse(miss.triggered)
        assertEquals("2026-10-14", miss.counters.rolledDay)
        assertEquals(0, miss.counters.weekCount)
        assertEquals(0, miss.counters.total)

        // 当天即使掷出必中的随机数也不再掷。
        val again = roll(weekADay1, miss.counters, random = 0.0)
        assertFalse(again.rolled)
        assertFalse(again.triggered)
    }

    @Test
    fun `概率是严格小于`() {
        // random == probability 不算命中。
        val boundary = roll(weekADay1, random = HotTopicPolicy.PROBABILITY)
        assertFalse(boundary.triggered)

        val below = roll(weekADay1, random = 0.0999999)
        assertTrue(below.triggered)
    }

    @Test
    fun `概率两端边界`() {
        assertFalse(roll(weekADay1, random = 0.0, probability = 0.0).triggered)
        assertTrue(roll(weekADay1, random = 0.9999999, probability = 1.0).triggered)
    }

    @Test
    fun `逻辑日从凌晨两点换日`() {
        // 本地 10-15 01:00 → 仍算 10-14。
        val lateNight = roll(Instant.parse("2026-10-14T17:00:00Z"), random = 0.05)
        assertEquals("2026-10-14", lateNight.counters.rolledDay)

        // 本地 10-15 01:30 → 同一个逻辑日，不再掷。
        val sameLogicalDay = roll(Instant.parse("2026-10-14T17:30:00Z"), lateNight.counters, random = 0.05)
        assertFalse(sameLogicalDay.rolled)

        // 本地 10-15 02:30 → 新逻辑日，可以再掷。
        val nextLogicalDay = roll(Instant.parse("2026-10-14T18:30:00Z"), lateNight.counters, random = 0.05)
        assertTrue(nextLogicalDay.rolled)
        assertEquals("2026-10-15", nextLogicalDay.counters.rolledDay)
    }

    @Test
    fun `每角色每周最多两次`() {
        val day1 = roll(weekADay1, random = 0.0)
        assertTrue(day1.triggered)
        assertEquals(1, day1.counters.weekCount)

        val day2 = roll(weekADay2, day1.counters, random = 0.0)
        assertTrue(day2.triggered)
        assertEquals(2, day2.counters.weekCount)
        assertEquals(2, day2.counters.total)

        // 第三次：当天照掷，但冲上上限，不触发。
        val day3 = roll(weekADay3, day2.counters, random = 0.0)
        assertTrue(day3.rolled)
        assertFalse(day3.triggered)
        assertEquals("2026-10-16", day3.counters.rolledDay)
        assertEquals(2, day3.counters.weekCount)
        assertEquals(2, day3.counters.total)
    }

    @Test
    fun `新的一周重置额度`() {
        val capped = HotTopicCounters(rolledDay = "2026-10-16", weekStart = "2026-10-12", weekCount = 2, total = 2)
        val nextWeek = roll(weekBDay1, capped, random = 0.0)
        assertTrue(nextWeek.triggered)
        assertEquals("2026-10-19", nextWeek.counters.weekStart)
        assertEquals(1, nextWeek.counters.weekCount)
        assertEquals(3, nextWeek.counters.total)
    }

    @Test
    fun `额度按角色独立`() {
        val c1 = roll(weekADay1, random = 0.0)
        assertTrue(c1.triggered)
        // c2 自己的计数是空的，同一天照样能触发（app 层按角色 id 分开存）。
        val c2 = roll(weekADay1, random = 0.0)
        assertTrue(c2.triggered)
        assertEquals(1, c2.counters.total)
    }

    @Test
    fun `日键与周键取逻辑日与周一`() {
        assertEquals("2026-10-14", HotTopicPolicy.dayKey(weekADay1, zone))
        assertEquals("2026-10-12", HotTopicPolicy.weekKey(weekADay1, zone))
        // 周日仍属于本周，次日（周一）换周。
        assertEquals("2026-10-12", HotTopicPolicy.weekKey(Instant.parse("2026-10-18T04:00:00Z"), zone))
        assertEquals("2026-10-19", HotTopicPolicy.weekKey(Instant.parse("2026-10-18T18:00:00Z"), zone))
    }

    @Test
    fun `存储能来回，坏数据当成没记过`() {
        val store = mapOf(
            "c1" to HotTopicCounters(rolledDay = "2026-10-14", weekStart = "2026-10-12", weekCount = 1, total = 3),
            "c2" to HotTopicCounters(rolledDay = "2026-10-15", weekStart = "2026-10-12", weekCount = 2, total = 5),
        )
        assertEquals(store, HotTopicPolicy.decode(HotTopicPolicy.encode(store)))

        assertEquals(emptyMap<String, HotTopicCounters>(), HotTopicPolicy.decode(null))
        assertEquals(emptyMap<String, HotTopicCounters>(), HotTopicPolicy.decode(""))
        assertEquals(emptyMap<String, HotTopicCounters>(), HotTopicPolicy.decode("不是 JSON"))
        assertEquals(emptyMap<String, HotTopicCounters>(), HotTopicPolicy.decode("[1,2,3]"))

        // 多出来的字段忽略，缺的字段用默认值。
        val tolerant = HotTopicPolicy.decode("""{"c1":{"rolledDay":"2026-10-14","future":true}}""")
        assertEquals("2026-10-14", tolerant.getValue("c1").rolledDay)
        assertEquals(0, tolerant.getValue("c1").weekCount)
    }
}
