package com.deeptalking.lite

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 视频缓存的 7 天 TTL 与截图清理：用可注入的时钟与临时目录，不碰 Android 环境。
 * 这几条对应"角色随看随取"的前提——命中要快、过期要真的失效、图片不能越攒越多。
 */
class BiliVideoCacheTest {

    private lateinit var root: File
    /** 从真实时间起步：文件的 lastModified 也是真实时间，注入的时钟必须与它同一基准。 */
    private var now = System.currentTimeMillis()

    @Before
    fun setUp() {
        root = Files.createTempDirectory("bili-video-cache").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun cache() = BiliVideoCache(root = root, clock = { now })

    @Test
    fun aFreshSummaryIsServedWithinTheWeek() = runBlocking {
        val cache = cache()
        cache.write("BV1ZKn267EY3", """{"ok":true,"summary":"旧结果"}""")
        assertEquals("""{"ok":true,"summary":"旧结果"}""", cache.read("BV1ZKn267EY3"))
    }

    @Test
    fun anEntryOlderThanSevenDaysIsDroppedOnRead() = runBlocking {
        val cache = cache()
        cache.write("BV1ZKn267EY3", """{"ok":true}""")
        now += BiliVideoCache.DEFAULT_TTL_MS - 5000
        assertEquals("{\"ok\":true}", cache.read("BV1ZKn267EY3"))
        now += 10_000
        assertNull(cache.read("BV1ZKn267EY3"))
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun expiredEntriesTakeTheirFramesWithThem() = runBlocking {
        val cache = cache()
        cache.write("BV1ZKn267EY3", """{"ok":true}""")
        val frame = File(root, "BV1ZKn267EY3-f12.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertTrue(frame.exists())

        now += BiliVideoCache.DEFAULT_TTL_MS + 10_000
        assertNull(cache.read("BV1ZKn267EY3"))
        assertTrue("过期条目的截图应当一起删掉", !frame.exists())
    }

    @Test
    fun orphanedFramesArePrunedWhenAnotherEntryIsWritten() = runBlocking {
        val cache = cache()
        File(root, "BV1MISSING000-f0.jpg").apply { writeBytes(byteArrayOf(9)) }
        cache.write("BV1ZKn267EY3", """{"ok":true}""")
        val leftovers = root.listFiles().orEmpty().map { it.name }
        assertEquals(listOf("BV1ZKn267EY3.json"), leftovers)
    }

    @Test
    fun aBvNumberIsTheOnlyAcceptedKey() = runBlocking {
        val cache = cache()
        assertNull(BiliVideoCache.safeKey("../etc/passwd"))
        assertNull(BiliVideoCache.safeKey(""))
        assertEquals("BV1ZKn267EY3", BiliVideoCache.safeKey(" BV1ZKn267EY3 "))
        cache.write("../etc/passwd", "nope")
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun clearRemovesEverything() = runBlocking {
        val cache = cache()
        cache.write("BV1ZKn267EY3", """{"ok":true}""")
        assertTrue(cache.sizeBytes() > 0)
        cache.clear()
        assertEquals(0L, cache.sizeBytes())
    }
}
