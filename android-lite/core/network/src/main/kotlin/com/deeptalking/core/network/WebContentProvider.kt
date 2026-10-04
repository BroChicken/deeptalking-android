package com.deeptalking.core.network

/**
 * Web access used by the agent's web_search / web_fetch tools. Every method
 * returns the same JSON-string shape the legacy tools returned so the model
 * sees a stable contract.
 */
interface WebContentProvider {

    /** Bing RSS search; returns `{"ok":true,"query":...,"results":[...]}`. */
    suspend fun searchWeb(query: String): String

    /** Fetches a URL (article/image/bilibili); returns JSON or an image tool result. */
    suspend fun fetch(url: String): String

    /** Bilibili keyword search; returns `{"ok":true,"platform":"bilibili",...}`. */
    suspend fun searchBilibili(keyword: String): String
}
