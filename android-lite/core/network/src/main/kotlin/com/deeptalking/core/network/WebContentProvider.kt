package com.deeptalking.core.network

/**
 * Web access used by the agent's web_search / web_fetch / hot_topics /
 * social_search tools. Every method returns the same JSON-string shape the
 * legacy tools returned so the model sees a stable contract.
 *
 * Every source here is reachable **without an account** — no login cookie, no
 * API key. Platforms that require a signed-in session (weibo, xiaohongshu,
 * douyin search, tieba, wechat) are deliberately absent; a source that changes
 * shape or starts demanding auth must degrade to `{"ok":false,"reason":...}`
 * instead of throwing, so one dead source can never break the tool loop.
 */
interface WebContentProvider {

    /** Bing RSS search; returns `{"ok":true,"query":...,"results":[...]}`. */
    suspend fun searchWeb(query: String, limit: Int = DEFAULT_SEARCH_LIMIT): String

    /**
     * Fetches a URL (article/image/bilibili video/hupu thread/netease song);
     * returns JSON or an image tool result.
     */
    suspend fun fetch(url: String): String

    /** Bilibili keyword search; returns `{"ok":true,"platform":"bilibili",...}`. */
    suspend fun searchBilibili(keyword: String, limit: Int = DEFAULT_VIDEO_LIMIT): String

    /**
     * Hot board of a CN platform — `baidu` / `toutiao` / `douyin` / `zhihu`;
     * returns `{"ok":true,"platform":..,"items":[{"title","url","hot"}]}`.
     */
    suspend fun hotBoard(platform: String, limit: Int = DEFAULT_HOT_LIMIT): String

    /**
     * Public content search of a CN platform — `hupu` (posts) / `netease`
     * (songs) / `bilibili` (videos); returns
     * `{"ok":true,"platform":..,"results":[...]}`.
     */
    suspend fun searchSocial(platform: String, query: String, limit: Int = DEFAULT_SOCIAL_LIMIT): String

    companion object {
        const val DEFAULT_SEARCH_LIMIT = 6
        const val DEFAULT_VIDEO_LIMIT = 8
        const val DEFAULT_HOT_LIMIT = 10
        const val DEFAULT_SOCIAL_LIMIT = 6
    }
}
