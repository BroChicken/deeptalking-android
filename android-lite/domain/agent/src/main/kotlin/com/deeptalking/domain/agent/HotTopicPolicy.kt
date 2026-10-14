package com.deeptalking.domain.agent

import com.deeptalking.domain.memory.formatAbsoluteDate
import com.deeptalking.domain.memory.getWeekStart
import com.deeptalking.domain.memory.logicalDay
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

/**
 * 「热榜开场」小概率事件的每角色计数（存进 DataStore 的一个字符串键）。
 *
 * 只在逻辑日换日（凌晨 2 点）和自然周（周一起算）上记账：每天第一次回合掷一次
 * 骰子，命中一次就记一次。**不进 Room，也不进备份导出**，所以不牵动
 * `LegacyMapper` / `BackupService` 那条迁移链。
 */
@Serializable
data class HotTopicCounters(
    /** 已掷过骰子的逻辑日（`yyyy-MM-dd`）；空 = 从没掷过。 */
    val rolledDay: String = "",
    /** 当前统计周的周一（`yyyy-MM-dd`）；与今天所在的周不同即视为新的一周。 */
    val weekStart: String = "",
    /** 本周已触发次数。 */
    val weekCount: Int = 0,
    /** 累计触发次数。 */
    val total: Int = 0,
)

/** [HotTopicPolicy.roll] 的结果。 */
data class HotTopicRoll(
    /** 本轮是否走「先查热榜再开场」。 */
    val triggered: Boolean,
    /** 是否真的掷了骰子：false = 今天已经掷过（只是问了一下，不要再掷）。 */
    val rolled: Boolean,
    /** 记账后的计数，`rolled == true` 时由调用方写回存储。 */
    val counters: HotTopicCounters,
)

/**
 * 纯函数策略：给定时间、已记计数与掷出的随机数，判断本轮要不要用热榜开场。
 *
 * 规则：每个角色**每个逻辑日只掷一次**（当天第一次回合），命中率
 * [PROBABILITY]，冲上限是**每天 1 次、每周 [WEEKLY_LIMIT] 次**（每角色独立）。
 * 没命中也算"今天掷过了"，当天不再掷。
 */
object HotTopicPolicy {

    /** 每次机会的命中率（每天只有第一次机会真的掷）。 */
    const val PROBABILITY = 0.10

    /** 每个角色每周最多触发次数。 */
    const val WEEKLY_LIMIT = 2

    private val json = Json { ignoreUnknownKeys = true }
    private val mapSerializer = MapSerializer(String.serializer(), HotTopicCounters.serializer())

    fun roll(
        now: Instant = Instant.now(),
        counters: HotTopicCounters = HotTopicCounters(),
        random: Double = Random.nextDouble(),
        probability: Double = PROBABILITY,
        weeklyLimit: Int = WEEKLY_LIMIT,
        zone: ZoneId = ZoneId.systemDefault(),
    ): HotTopicRoll {
        val today = dayKey(now, zone)
        if (counters.rolledDay == today) {
            return HotTopicRoll(triggered = false, rolled = false, counters = counters)
        }
        val week = weekKey(now, zone)
        val usedThisWeek = if (counters.weekStart == week) counters.weekCount else 0
        val triggered = random < probability && usedThisWeek < weeklyLimit
        val updated = counters.copy(
            rolledDay = today,
            weekStart = week,
            weekCount = if (triggered) usedThisWeek + 1 else usedThisWeek,
            total = if (triggered) counters.total + 1 else counters.total,
        )
        return HotTopicRoll(triggered = triggered, rolled = true, counters = updated)
    }

    /** 逻辑日的键（凌晨 2 点换日，与提示词里的"逻辑日"一致）。 */
    fun dayKey(now: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        formatAbsoluteDate(logicalDay(now.atZone(zone)))

    /** 所在自然周的周一，作为"本周"的键。 */
    fun weekKey(now: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        formatAbsoluteDate(getWeekStart(logicalDay(now.atZone(zone))))

    fun encode(store: Map<String, HotTopicCounters>): String =
        json.encodeToString(mapSerializer, store)

    /** 解析失败/为空一律当成"没记过"，不会因为一次坏数据让功能崩掉。 */
    fun decode(raw: String?): Map<String, HotTopicCounters> =
        if (raw.isNullOrBlank()) {
            emptyMap()
        } else {
            runCatching { json.decodeFromString(mapSerializer, raw) }.getOrDefault(emptyMap())
        }
}
