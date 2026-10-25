package com.deeptalking.core.model

/**
 * 表情库的**大类**：固定的 12 个桶，只做导航和提示词分组，不参与发送匹配。
 *
 * 具体关键词才是发送时的选择键（由模型在入库时看图写下来）。旧数据没有
 * `category` 字段，读的时候用 [of] 按老标签推断，不做一次性迁移。
 */
object StickerCategory {

    const val OTHER = "other"

    data class Def(val id: String, val label: String, val hint: String)

    val ALL: List<Def> = listOf(
        Def("happy", "开心", "笑、乐、庆祝、调皮"),
        Def("speechless", "无语", "翻白眼、摊手、沉默、嫌弃"),
        Def("angry", "生气", "怒、炸毛、威胁"),
        Def("sad", "难过", "哭、委屈、丧、心碎"),
        Def("shy", "害羞", "脸红、扭捏、遮脸"),
        Def("affection", "亲密", "贴贴、抱抱、亲亲、比心、撒娇"),
        Def("smug", "得意", "炫耀、欠揍、坏笑"),
        Def("surprise", "惊讶", "震惊、问号、傻眼"),
        Def("cheer", "鼓励", "加油、点赞、拍肩"),
        Def("tired", "疲惫", "困、摸鱼、躺平、下班"),
        Def("animal", "动物", "猫狗等具体形象的梗图"),
        Def(OTHER, "其他", "放不进去的都归这里"),
    )

    fun isValid(id: String): Boolean = ALL.any { it.id == id }

    fun labelOf(id: String): String = ALL.firstOrNull { it.id == id }?.label ?: "其他"

    /** 这个表情属于哪个大类：显式 `category` 优先，否则按老标签推断。 */
    fun of(sticker: Sticker): String =
        sticker.category.takeIf(::isValid) ?: infer(sticker.keyword)

    /** 老 18 标签 → 大类（旧数据不落库，读时推断）。 */
    fun infer(legacyTag: String): String = when (legacyTag.trim()) {
        "开心", "大笑", "干杯" -> "happy"
        "无语", "拒绝" -> "speechless"
        "生气" -> "angry"
        "难过", "委屈" -> "sad"
        "害羞" -> "shy"
        "爱意", "亲亲", "抱抱" -> "affection"
        "得意" -> "smug"
        "惊讶", "疑惑" -> "surprise"
        "加油", "点赞" -> "cheer"
        "睡觉" -> "tired"
        else -> OTHER
    }

    /** 按大类分组（顺序固定 = [ALL] 的顺序），空组不返回。 */
    fun group(stickers: List<Sticker>): List<Pair<Def, List<Sticker>>> {
        if (stickers.isEmpty()) return emptyList()
        val buckets = LinkedHashMap<String, MutableList<Sticker>>()
        stickers.forEach { buckets.getOrPut(of(it)) { ArrayList() } += it }
        return ALL.mapNotNull { def -> buckets[def.id]?.let { def to it } }
    }
}
