package com.deeptalking.feature.characters

/**
 * 基础设定字段：英文键（`StaticProfile` / 模型约定的字段名）→ 中文标签。
 *
 * 键必须保持英文：`NativeCore.regenerateCharacterField`、`FieldCleaning`、`withStatic`
 * 全按这套键分派；标签只给界面看。
 */
internal val STATIC_FIELDS = listOf(
    "gender" to "性别",
    "age" to "年龄",
    "race" to "种族",
    "appearance" to "外貌特征",
    "personality" to "性格特征",
    "fears" to "恐惧/弱点",
    "background" to "个人背景",
    "speakingStyle" to "声音底色",
    "language" to "语言/方言",
    "userAddress" to "对用户的称呼",
)

/**
 * 「重新生成字段」下拉的选项，形状是 `(要显示的标签, 点击后回调的值)`。
 *
 * 顺序不能反：`LegacyDropdown` 用 `first` 显示、把 `second` 回调给调用方，而调用方要的是
 * **英文键**（拿去调模型）。写反了的表现是菜单里出现 `gender/age/…` 这样的英文，点一下
 * 选中值也不变，然后「重新生成」把中文标签当字段名传下去、静默失败。
 */
internal fun regenerateFieldOptions(): List<Pair<String, String>> =
    listOf("头像 emoji" to "emoji") + STATIC_FIELDS.map { (key, label) -> label to key }

/** 当前选中的字段键要显示的文本；未知键回落到第一项（头像 emoji）。 */
internal fun regenerateFieldLabel(options: List<Pair<String, String>>, key: String): String =
    options.firstOrNull { it.second == key }?.first ?: options.first().first
