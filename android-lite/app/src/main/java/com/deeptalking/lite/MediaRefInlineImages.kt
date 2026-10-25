package com.deeptalking.lite

import android.content.Context
import com.deeptalking.core.data.legacy.MediaRef
import com.deeptalking.domain.agent.video.InlineImages

/**
 * 把本地截图引用变成 `data:` URI，供工具结果里的 `input_image` 使用（无字幕时把画面递给主模型）。
 * 复用 `MediaRef`：它已经处理了绝对路径 / content:// / 相对 key 这几种引用形态。
 */
class MediaRefInlineImages(private val context: Context) : InlineImages {
    override fun dataUri(ref: String): String? = MediaRef.toDataUri(context, ref)
}
