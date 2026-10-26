package com.deeptalking.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 看图页缩放的纯数学：钉住手指、边界夹紧、还原。
 * 这些都不需要真机/Compose，所以直接锁在单测里——手感问题最怕“改一处坏一处”。
 */
class MediaZoomTest {

    private val center = ZoomPoint(500f, 1000f)

    private fun screenOf(contentPoint: ZoomPoint, offset: ZoomPoint, scale: Float) =
        center + offset + contentPoint * scale

    @Test
    fun `pinch keeps the point under the fingers fixed`() {
        val centroid = ZoomPoint(700f, 1200f)
        val content = (centroid - center) * (1f / 1f) // 1x 时手指下那个内容点
        val next = MediaZoom.zoomAround(
            offset = ZoomPoint(0f, 0f),
            centroid = centroid,
            viewportCenter = center,
            oldScale = 1f,
            newScale = 2f,
        )
        val screen = screenOf(content, next, 2f)
        assertEquals(centroid.x, screen.x, 0.01f)
        assertEquals(centroid.y, screen.y, 0.01f)
    }

    @Test
    fun `pan is added on top of the anchored offset`() {
        val centroid = ZoomPoint(600f, 1100f)
        val anchored = MediaZoom.zoomAround(ZoomPoint(0f, 0f), centroid, center, 1f, 2f)
        val panned = MediaZoom.zoomAround(ZoomPoint(0f, 0f), centroid, center, 1f, 2f, ZoomPoint(12f, -8f))
        assertEquals(anchored.x + 12f, panned.x, 0.01f)
        assertEquals(anchored.y - 8f, panned.y, 0.01f)
    }

    @Test
    fun `zooming while already zoomed keeps the anchor too`() {
        // 放大到 2x 并拖过之后再捏：锚点仍必须钉在手指上。
        val centroid = ZoomPoint(900f, 700f)
        val startOffset = ZoomPoint(-200f, 150f)
        val startScale = 2f
        val content = (centroid - center - startOffset) * (1f / startScale)
        val next = MediaZoom.zoomAround(startOffset, centroid, center, startScale, 3.5f)
        val screen = screenOf(content, next, 3.5f)
        assertEquals(centroid.x, screen.x, 0.01f)
        assertEquals(centroid.y, screen.y, 0.01f)
    }

    @Test
    fun `offset is clamped so the image cannot be dragged off screen`() {
        val clamped = MediaZoom.clampOffset(
            offset = ZoomPoint(900f, -900f),
            contentWidth = 2000f,
            contentHeight = 400f,
            viewportWidth = 1000f,
            viewportHeight = 2000f,
        )
        assertEquals(500f, clamped.x, 0.01f)
        // 4x 宽度都不够高：竖向没有可拖动余量，必须归零，否则会拖出空白。
        assertEquals(0f, clamped.y, 0.01f)
    }

    @Test
    fun `at min scale the offset is forced back to center`() {
        val clamped = MediaZoom.clampOffset(
            offset = ZoomPoint(120f, -80f),
            contentWidth = 900f,
            contentHeight = 1600f,
            viewportWidth = 1000f,
            viewportHeight = 2000f,
        )
        assertEquals(0f, clamped.x, 0.01f)
        assertEquals(0f, clamped.y, 0.01f)
    }

    @Test
    fun `scale is clamped to the supported range`() {
        assertEquals(MediaZoom.MIN_SCALE, MediaZoom.clampScale(0.3f), 0.001f)
        assertEquals(MediaZoom.MAX_SCALE, MediaZoom.clampScale(12f), 0.001f)
        assertEquals(2f, MediaZoom.clampScale(2f), 0.001f)
    }

    @Test
    fun `fit keeps aspect ratio and never upscales past the viewport`() {
        val wide = MediaZoom.fit(imageWidth = 1000, imageHeight = 500, viewportWidth = 400f, viewportHeight = 1000f)
        assertEquals(400f, wide.width, 0.01f)
        assertEquals(200f, wide.height, 0.01f)

        val tall = MediaZoom.fit(imageWidth = 500, imageHeight = 2000, viewportWidth = 400f, viewportHeight = 1000f)
        assertEquals(250f, tall.width, 0.01f)
        assertEquals(1000f, tall.height, 0.01f)
    }

    @Test
    fun `fit tolerates a missing image size`() {
        val none = MediaZoom.fit(0, 0, 400f, 1000f)
        assertEquals(0f, none.width, 0.01f)
        assertEquals(0f, none.height, 0.01f)
    }
}
