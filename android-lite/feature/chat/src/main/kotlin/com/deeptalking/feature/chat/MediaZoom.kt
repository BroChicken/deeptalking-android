package com.deeptalking.feature.chat

/** 视口坐标系里的一个点/尺寸（像素），刻意不依赖 Compose，纯函数才好单测。 */
internal data class ZoomPoint(val x: Float, val y: Float) {
    operator fun plus(other: ZoomPoint) = ZoomPoint(x + other.x, y + other.y)
    operator fun minus(other: ZoomPoint) = ZoomPoint(x - other.x, y - other.y)
    operator fun times(factor: Float) = ZoomPoint(x * factor, y * factor)
}

internal data class ZoomSize(val width: Float, val height: Float)

/**
 * 看图页的缩放/平移纯计算。
 *
 * 约定：坐标原点在视口左上角；[ZoomPoint.offset] 是「内容中心相对视口中心的位移」。
 * 屏幕位置 = 视口中心 + offset + 缩放后内容上的局部坐标，所以：
 *
 * - 缩放要**钉住手指**（[zoomAround]），否则捏合时画面会朝视口中心跑；
 * - 放大后平移不能把图拖出屏幕（[clampOffset]）；
 * - 缩回 1x 时 offset 必须归零（同一条 clamp 自然满足，因为内容不大于视口）。
 */
internal object MediaZoom {

    const val MIN_SCALE = 1f
    const val MAX_SCALE = 5f

    /** 双击从 1x 直接放到这里；再双击回 1x。和主流看图一致。 */
    const val DOUBLE_TAP_SCALE = 2.5f

    fun clampScale(scale: Float): Float = scale.coerceIn(MIN_SCALE, MAX_SCALE)

    /** 图片按 `ContentScale.Fit` 放进视口后的显示尺寸（保持宽高比、不放大）。 */
    fun fit(imageWidth: Int, imageHeight: Int, viewportWidth: Float, viewportHeight: Float): ZoomSize {
        if (imageWidth <= 0 || imageHeight <= 0 || viewportWidth <= 0f || viewportHeight <= 0f) {
            return ZoomSize(0f, 0f)
        }
        val ratio = minOf(viewportWidth / imageWidth, viewportHeight / imageHeight)
        return ZoomSize(imageWidth * ratio, imageHeight * ratio)
    }

    /**
     * 以 [centroid]（手指中点，视口坐标）为锚点把缩放从 [oldScale] 变到 [newScale]，
     * 再叠加这次的 [pan]。返回新的 offset——手指下的那个内容点在屏幕上保持不动。
     */
    fun zoomAround(
        offset: ZoomPoint,
        centroid: ZoomPoint,
        viewportCenter: ZoomPoint,
        oldScale: Float,
        newScale: Float,
        pan: ZoomPoint = ZoomPoint(0f, 0f),
    ): ZoomPoint {
        if (oldScale <= 0f) return offset + pan
        val pivot = centroid - viewportCenter
        // 手指下那个内容点（以内容自身中心为原点）的坐标
        val content = (pivot - offset) * (1f / oldScale)
        return (pivot - content * newScale) + pan
    }

    /**
     * 把 offset 夹到「放大后的内容边缘不越过视口边缘」的范围内。
     * 内容比视口小时（1x）可移动范围是 0，所以缩回原尺寸会自然回到居中。
     */
    fun clampOffset(
        offset: ZoomPoint,
        contentWidth: Float,
        contentHeight: Float,
        viewportWidth: Float,
        viewportHeight: Float,
    ): ZoomPoint {
        val halfW = ((contentWidth - viewportWidth) / 2f).coerceAtLeast(0f)
        val halfH = ((contentHeight - viewportHeight) / 2f).coerceAtLeast(0f)
        return ZoomPoint(offset.x.coerceIn(-halfW, halfW), offset.y.coerceIn(-halfH, halfH))
    }
}
