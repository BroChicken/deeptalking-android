package com.deeptalking.engine.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteVoiceDeleteTest {

    private val config = RemoteTtsConfig(baseUrl = "http://pc:8080/", token = "sk-1")

    /** Client whose HTTP layer is the fake [response]; [onRequest] records what was sent. */
    private fun client(
        response: RemoteHttpResponse?,
        onRequest: (method: String, url: String, token: String?, body: String?) -> Unit = { _, _, _, _ -> },
    ): RemoteTtsClient = RemoteTtsClient(
        userAgent = "test",
        transport = RemoteHttpTransport { method, url, token, body ->
            onRequest(method, url, token, body)
            response
        },
    )

    @Test
    fun `delete calls the item endpoint without a body and succeeds on 2xx`() {
        var seen: List<String?> = emptyList()
        val client = client(RemoteHttpResponse(204)) { method, url, token, body ->
            seen = listOf(method, url, token, body)
        }

        assertNull(client.deleteVoice(config, "builtin_1_v2"))
        // The token goes out raw: the transport adds the `Bearer` header, exactly like the
        // rest of the client (a pasted "Bearer ..." is normalized there too).
        assertEquals(listOf<String?>("DELETE", "http://pc:8080/v1/voices/builtin_1_v2", "sk-1", null), seen)
    }

    @Test
    fun `a missing endpoint means the PC server is too old`() {
        // DELETE is part of the same local server patch as the manifest/rename endpoints.
        val message = client(RemoteHttpResponse(404, "not found")).deleteVoice(config, "a")
        assertEquals("电脑端不支持删除（需更新 PC 端服务）", message)
    }

    @Test
    fun `401 and 403 report a bad token`() {
        assertEquals("访问口令缺失或错误", client(RemoteHttpResponse(401)).deleteVoice(config, "a"))
        assertEquals("访问口令缺失或错误", client(RemoteHttpResponse(403)).deleteVoice(config, "a"))
    }

    @Test
    fun `other statuses keep the code and the server message`() {
        val message = client(RemoteHttpResponse(500, """{"error":"voice dir locked"}"""))
            .deleteVoice(config, "a")
        assertTrue(message!!.startsWith("HTTP 500"))
        assertTrue(message.contains("voice dir locked"))
    }

    @Test
    fun `a transport failure is reported instead of thrown`() {
        val message = client(null).deleteVoice(config, "a")
        assertTrue(message!!.contains("网络不可达"))
    }

    @Test
    fun `an unconfigured server or a blank id never sends a request`() {
        var calls = 0
        val client = client(RemoteHttpResponse(200)) { _, _, _, _ -> calls++ }

        assertEquals("未配置服务器地址", client.deleteVoice(RemoteTtsConfig(), "a"))
        assertEquals("音色名为空", client.deleteVoice(config, "   "))
        assertEquals(0, calls)
    }

    @Test
    fun `rename shares the same item endpoint and transport`() {
        var seen: List<String?> = emptyList()
        val client = client(RemoteHttpResponse(204)) { method, url, token, body ->
            seen = listOf(method, url, token, body)
        }

        assertNull(client.renameVoice(config, "内置 1", "新名字"))
        assertEquals("PUT", seen[0])
        assertEquals("http://pc:8080/v1/voices/%E5%86%85%E7%BD%AE%201", seen[1])
        assertTrue(seen[3]!!.contains("\"displayName\":\"新名字\""))
    }

    @Test
    fun `rename failure keeps the HTTP code and body`() {
        val message = client(RemoteHttpResponse(400, "bad name")).renameVoice(config, "a", "b")
        assertEquals("HTTP 400：bad name", message)
        assertFalse(message!!.contains("不支持删除"))
    }
}
