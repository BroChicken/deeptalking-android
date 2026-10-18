package com.deeptalking.core.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `staticFieldMeta` 的「人工手写」标记：旧数据（缺失该键 / 只有 `updatedAt`）一律视为未锁定，
 * 设置 / 清除都不能破坏账本里的其它键（相对时间迁移要读 `updatedAt`）。
 */
class StaticFieldMetaTest {

    @Test
    fun missingOrLegacyMetaIsNotManual() {
        assertFalse(isStaticFieldManual(emptyMap(), "personality"))
        val legacy = mapOf("personality" to JsonObject(mapOf("updatedAt" to JsonPrimitive("2026-01-01T00:00:00Z"))))
        assertFalse(isStaticFieldManual(legacy, "personality"))
        assertFalse(isStaticFieldManual(legacy, "gender"))
    }

    @Test
    fun settingAndClearingManualPreservesOtherKeys() {
        val start = mapOf("personality" to JsonObject(mapOf("updatedAt" to JsonPrimitive("t"))))

        val locked = withStaticFieldManual(start, "personality", true)
        assertTrue(isStaticFieldManual(locked, "personality"))
        assertEquals(JsonPrimitive("t"), (locked["personality"] as JsonObject)["updatedAt"])

        val unlocked = withStaticFieldManual(locked, "personality", false)
        assertFalse(isStaticFieldManual(unlocked, "personality"))
        assertEquals(JsonPrimitive("t"), (unlocked["personality"] as JsonObject)["updatedAt"])
    }

    @Test
    fun clearingAFreshFieldLeavesTheLedgerUntouched() {
        assertTrue(withStaticFieldManual(emptyMap(), "gender", false).isEmpty())
    }

    @Test
    fun theFlagSurvivesAnExportImportShapedMapCopy() {
        // BackupService / LegacyMapper 用 JsonObject(meta) 往返，键与布尔值都要原样保留。
        val locked = withStaticFieldManual(emptyMap(), "background", true)
        assertTrue(isStaticFieldManual(JsonObject(locked), "background"))
    }
}
