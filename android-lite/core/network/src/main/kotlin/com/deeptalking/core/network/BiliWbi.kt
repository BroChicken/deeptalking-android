package com.deeptalking.core.network

import java.security.MessageDigest

/**
 * B 站 WBI 签名（`w_rid` + `wts`）。
 *
 * 签名 key 由 `x/web-interface/nav` 返回的 `wbi_img` 两张图名拼成：拼接成 64 位原始串，
 * 按固定置换表重排后**截前 32 位**得到 mixin key，再把「按键名排序、逐项百分号编码后的
 * 查询串 + mixin key」做 MD5。key 每天轮换一次，所以调用方必须现取现用，不能跨天缓存。
 *
 * 只在带登录态的接口（`x/player/wbi/v2`、`view/conclusion/get`）上被服务端严格校验；
 * 未登录时服务端接受任意 `w_rid`，这也意味着签名写错只会在登录后暴露，必须有单测锁住。
 */
internal object BiliWbi {

    /** 置换表（64 项）；只取映射结果的前 32 项作 mixin key。 */
    private val MIXIN_ENCODE_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
    )

    /** `imgKey`/`subKey` 是两张图的文件名（去掉目录与扩展名）。 */
    fun mixinKey(imgKey: String, subKey: String): String {
        val raw = imgKey + subKey
        if (raw.length < 64) return ""
        val permuted = StringBuilder(64)
        MIXIN_ENCODE_TAB.forEach { permuted.append(raw[it]) }
        return permuted.substring(0, 32)
    }

    /** 追加 `wts`、排序、编码、签名，返回可直接拼到 URL 后面的查询串。 */
    fun signedQuery(
        params: Map<String, String>,
        imgKey: String,
        subKey: String,
        wts: Long,
    ): String {
        val all = params + ("wts" to wts.toString())
        val query = all.toSortedMap().entries.joinToString("&") { (name, value) ->
            name + "=" + encodeQueryValue(value)
        }
        return query + "&w_rid=" + md5(query + mixinKey(imgKey, subKey))
    }

    /**
     * 等价于 JS 的 `encodeURIComponent(value.replace(/[!'()*]/g, ''))`：先剔除四个会被
     * JS 判为保留字符的符号，再对除 `A-Za-z0-9-._~` 之外的字节做大写百分号编码。
     */
    fun encodeQueryValue(value: String): String {
        val stripped = value.filterNot { it == '!' || it == '\'' || it == '(' || it == ')' || it == '*' }
        val out = StringBuilder(stripped.length + 8)
        stripped.toByteArray(Charsets.UTF_8).forEach { byte ->
            val ch = byte.toInt() and 0xFF
            val keep = (ch in 'a'.code..'z'.code) || (ch in 'A'.code..'Z'.code) ||
                (ch in '0'.code..'9'.code) || ch == '-'.code || ch == '.'.code ||
                ch == '_'.code || ch == '~'.code
            if (keep) {
                out.append(ch.toChar())
            } else {
                // encodeURIComponent 输出大写十六进制；服务端按字面量比对，大小写不能随实现走。
                out.append('%').append(UPPER_HEX[ch shr 4]).append(UPPER_HEX[ch and 0x0F])
            }
        }
        return out.toString()
    }

    private fun md5(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(32)
        digest.forEach { out.append(HEX[(it.toInt() and 0xFF) shr 4]).append(HEX[it.toInt() and 0x0F]) }
        return out.toString()
    }

    private const val HEX = "0123456789abcdef"
    private const val UPPER_HEX = "0123456789ABCDEF"
}
