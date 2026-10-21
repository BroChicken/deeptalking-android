package com.deeptalking.engine.remote

import com.deeptalking.engine.audio.Pcm16ByteStream
import com.deeptalking.engine.ondevice.AudioChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.net.URLEncoder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Dns
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

/** Aborts an in-flight synthesis (closing the socket is how the server stops generating). */
fun interface RemoteTtsCancellation {
    fun cancel()
}

/**
 * Timing profile for one reachability probe.
 *
 * The LAN sweep tries hundreds of addresses, so it cannot afford the generous
 * timeouts of the interactive "test connection" - but a Bluetooth PAN link is slow
 * enough that the fast sweep timings can expire before the PC answers, which is why
 * there is a third profile rather than just "fast" and "slow".
 */
enum class ProbeProfile {
    /** Settings screen "test connection": generous timeouts. */
    INTERACTIVE,

    /** Wi-Fi / USB / Ethernet sweep: short enough to try a whole /24 quickly. */
    SCAN,

    /** Bluetooth PAN sweep (`bnep*`): ~30-100 ms RTT and 3 Mbps nominal. */
    SLOW_SCAN,
}

/**
 * Source of 16 kHz/24 kHz PCM audio for one utterance. Implemented by
 * [RemoteTtsClient]; faked in tests so the speaker's phase/ordering logic can be
 * verified on the JVM.
 */
interface RemoteTtsAudioSource {

    /**
     * Streams the PCM for [text] with the given [style] (a non-blank style selects
     * the server's instruct mode). [registerCancel] receives a handle that aborts
     * the request, and is called with null once the stream is finished.
     */
    fun speech(
        text: String,
        style: String?,
        config: RemoteTtsConfig,
        registerCancel: (RemoteTtsCancellation?) -> Unit,
    ): Flow<AudioChunk>
}

/** Result of the settings screen's "test connection" probe. */
data class RemoteTtsProbe(
    val ok: Boolean,
    /** `api` for `--api` mode, `webui` for WebUI mode, null when unknown. */
    val mode: String? = null,
    val message: String,
    /** Sample rate from `GET /status` (WebUI mode only); 0 when unavailable. */
    val sampleRate: Int = 0,
    /** Registered speaker names from `GET /speaker` (WebUI mode only). */
    val speakers: List<String> = emptyList(),
)

/** One server-side voice: [id] is the stable speaker name, [name] is the editable display name. */
data class RemoteVoiceInfo(
    val id: String,
    val name: String = id,
    val size: Long = 0L,
    val hash: String = "",
)

/** Outcome of pushing one on-device voice to the server (`POST /v1/voices`). */
data class RemoteVoiceUpload(
    /** Server-side voice name (the on-device file name without `.gguf`). */
    val name: String,
    val ok: Boolean,
    /** Human-readable reason when [ok] is false; empty otherwise. */
    val message: String = "",
)

/**
 * OpenAI Speech-compatible HTTP client for `cosyvoice-server`
 * (`POST /v1/audio/speech`, optional `Authorization: Bearer <--api-key>`).
 *
 * Also probes the WebUI-mode helper endpoints (`/ping`, `/status`, `/speaker`),
 * which are what make LAN discovery and voice-list fetching possible.
 */
class RemoteTtsClient(
    private val userAgent: String = "DeepTalking-Lite",
    dns: Dns = Dns.SYSTEM,
) : RemoteTtsAudioSource {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Interactive probe (settings "test connection"). */
    private val probeClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(PROBE_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(PROBE_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(PROBE_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .dns(dns)
        .build()

    /** LAN sweep probe: short enough to try every host in a /24 quickly. */
    private val scanClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(SCAN_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(SCAN_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(SCAN_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        // The sweep probes many distinct hosts at once; OkHttp's default global
        // limit is 64 but the per-host limit of 5 would serialize nothing here, so
        // both are raised/decorated explicitly to keep the sweep fast.
        .dispatcher(
            Dispatcher().apply {
                maxRequests = SCAN_CONCURRENCY
                maxRequestsPerHost = SCAN_CONCURRENCY
            },
        )
        .dns(dns)
        .build()

    /**
     * Synthesis client. No call timeout (a long reply is legitimate) but a bounded
     * idle read timeout, so a dead server surfaces as an error instead of hanging.
     */
    private val streamClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(PROBE_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(STREAM_IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .dns(dns)
        .build()

    /**
     * Bluetooth PAN sweep probe: same shape as [scanClient], but the link's round-trip
     * (tens of milliseconds, 3 Mbps nominal) needs more room than a Wi-Fi sweep.
     */
    private val slowScanClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(SLOW_SCAN_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(SLOW_SCAN_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(SLOW_SCAN_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .dispatcher(
            Dispatcher().apply {
                maxRequests = SCAN_CONCURRENCY
                maxRequestsPerHost = SCAN_CONCURRENCY
            },
        )
        .dns(dns)
        .build()

    override fun speech(
        text: String,
        style: String?,
        config: RemoteTtsConfig,
        registerCancel: (RemoteTtsCancellation?) -> Unit,
    ): Flow<AudioChunk> = flow {
        val base = config.normalizedBaseUrl
        if (base.isEmpty()) throw RemoteTtsException("未配置远程语音服务器地址")
        val request = Request.Builder()
            .url(speechUrl(base))
            .post(buildSpeechBody(text, config, style).toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "audio/L16")
            .header("User-Agent", userAgent)
            .apply { bearerToken(config.token)?.let { header("Authorization", it) } }
            .build()
        val call = streamClient.newCall(request)
        // Explicit SAM construction: the parameter's type is a fun interface, so a
        // bare lambda argument would read as a SAM conversion but is easy to
        // misread as "invoke with a Function0".
        registerCancel.invoke(RemoteTtsCancellation { call.cancel() })
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw failureFor(response)
                val rate = config.resolvedSampleRate
                val source = response.body?.source() ?: throw RemoteTtsException("服务器未返回音频数据")
                val decoder = Pcm16ByteStream()
                val buffer = ByteArray(READ_BUFFER_BYTES)
                while (true) {
                    // Cooperative cancellation: the socket read below is blocking, so
                    // the speaker also cancels the call from stop()/cancel().
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer)
                    if (read <= 0) break
                    val samples = decoder.push(buffer, read)
                    if (samples.isNotEmpty()) emit(AudioChunk(samples, rate, isLast = false))
                }
                decoder.flush()
                emit(AudioChunk(ShortArray(0), rate, isLast = true))
            }
        } finally {
            registerCancel.invoke(null)
        }
    }.flowOn(Dispatchers.IO)

    /** Interactive probe used by the settings screen; never throws. */
    fun probe(config: RemoteTtsConfig): RemoteTtsProbe {
        val base = config.normalizedBaseUrl
        if (base.isEmpty()) return RemoteTtsProbe(ok = false, message = "未填写服务器地址")

        val models = get(probeClient, "$base/v1/models", config)
        if (models.first == 200) return apiProbe(base, config)
        val healthz = get(probeClient, "$base/healthz", config)
        if (healthz.first == 200) return apiProbe(base, config)
        val ping = get(probeClient, "$base/ping", config)
        if (ping.first == 200) return webuiProbe(base, config)

        val codes = listOf(models, healthz, ping).mapNotNull { it.first }
        val error = listOf(models, healthz, ping).firstNotNullOfOrNull { it.second }
        return when {
            codes.any { it == 401 || it == 403 } ->
                RemoteTtsProbe(ok = false, message = "服务器要求访问口令（HTTP ${codes.first { it == 401 || it == 403 }}）")
            codes.isNotEmpty() ->
                RemoteTtsProbe(ok = false, message = "服务器已响应但端点不匹配（HTTP ${codes.first()}）。地址应形如 http://<电脑地址>:8080")
            else -> RemoteTtsProbe(ok = false, message = "无法连接：${error ?: "未知错误"}")
        }
    }

    /** Reachability check used by the LAN sweep; never throws. */
    fun reachable(
        baseUrl: String,
        token: String?,
        profile: ProbeProfile = ProbeProfile.SCAN,
    ): Boolean {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return false
        val config = RemoteTtsConfig(baseUrl = base, token = token)
        val client = when (profile) {
            ProbeProfile.INTERACTIVE -> probeClient
            ProbeProfile.SCAN -> scanClient
            ProbeProfile.SLOW_SCAN -> slowScanClient
        }
        if (get(client, "$base/v1/models", config).first == 200) return true
        if (get(client, "$base/healthz", config).first == 200) return true
        return get(client, "$base/ping", config).first == 200
    }

    /** Registered speaker names (`GET /speaker`, WebUI mode only). Empty on failure. */
    fun speakers(config: RemoteTtsConfig): List<String> {
        val base = config.normalizedBaseUrl
        if (base.isEmpty()) return emptyList()
        return parseSpeakers(getBody(probeClient, "$base/speaker", config) ?: return emptyList())
    }

    /**
     * Pushes one prompt-speech gguf to the server, which stores it as
     * `<--voice-dir>/<name>.gguf` and registers it under [name] for the running
     * process. This is how the PC ends up speaking with the phone's own voices:
     * same bytes, same name, so both engines share one voice set (the on-device
     * clone already did the audio selection/encoding - see docs/REMOTE_TTS.md §8).
     *
     * Never throws; a server that predates the endpoint reports HTTP 404.
     */
    fun uploadVoice(
        config: RemoteTtsConfig,
        name: String,
        bytes: ByteArray,
        displayName: String? = null,
    ): RemoteVoiceUpload {
        val base = config.normalizedBaseUrl
        if (base.isEmpty()) return RemoteVoiceUpload(name, ok = false, message = "未配置服务器地址")
        if (name.isBlank()) return RemoteVoiceUpload(name, ok = false, message = "音色名为空")
        val url = if (displayName.isNullOrBlank()) {
            voiceUploadUrl(base, name)
        } else {
            voiceUploadUrl(base, name) + "&display_name=" +
                URLEncoder.encode(displayName, Charsets.UTF_8.name()).replace("+", "%20")
        }
        val request = Request.Builder()
            .url(url)
            .post(bytes.toRequestBody(OCTET_STREAM_MEDIA_TYPE))
            .header("User-Agent", userAgent)
            .apply { bearerToken(config.token)?.let { header("Authorization", it) } }
            .build()
        return runCatching {
            streamClient.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> RemoteVoiceUpload(name, ok = true)
                    response.code == 404 -> RemoteVoiceUpload(
                        name,
                        ok = false,
                        message = "电脑端不支持音色上传（需更新 PC 服务端到带 /v1/voices 的版本）",
                    )
                    response.code == 401 || response.code == 403 -> RemoteVoiceUpload(name, ok = false, message = "访问口令缺失或错误")
                    else -> RemoteVoiceUpload(
                        name,
                        ok = false,
                        message = "HTTP ${response.code}${errorDetail(response)}",
                    )
                }
            }
        }.getOrElse { t -> RemoteVoiceUpload(name, ok = false, message = t.message ?: "上传失败") }
    }

    /**
     * Full remote voice manifest (`GET /v1/voices`): stable id, editable display name,
     * size and a content fingerprint. Empty when the server predates the endpoint.
     */
    fun listVoices(config: RemoteTtsConfig): List<RemoteVoiceInfo> {
        val base = config.normalizedBaseUrl
        if (base.isEmpty()) return emptyList()
        val body = getBody(probeClient, "$base/v1/voices", config) ?: return emptyList()
        return runCatching {
            json.parseToJsonElement(body).jsonObject["voices"]?.jsonArray.orEmpty().mapNotNull { element ->
                val obj = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (id.isBlank()) return@mapNotNull null
                RemoteVoiceInfo(
                    id = id,
                    name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { id },
                    size = obj["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                    hash = obj["hash"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Downloads one prompt-speech gguf (`GET /v1/voices/<id>`); null on any failure. */
    fun downloadVoice(config: RemoteTtsConfig, id: String): ByteArray? {
        val base = config.normalizedBaseUrl
        if (base.isEmpty() || id.isBlank()) return null
        val url = "$base/v1/voices/${URLEncoder.encode(id, Charsets.UTF_8.name())}"
        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", userAgent)
            .apply { bearerToken(config.token)?.let { header("Authorization", it) } }
            .build()
        return runCatching {
            streamClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val bytes = response.body?.bytes() ?: return@use null
                if (bytes.isEmpty() || bytes.size > MAX_VOICE_BYTES) null else bytes
            }
        }.getOrNull()
    }

    /** Renames only the display name (`PUT /v1/voices/<id>`); null on success. */
    fun renameVoice(config: RemoteTtsConfig, id: String, displayName: String): String? {
        val base = config.normalizedBaseUrl
        if (base.isEmpty() || id.isBlank() || displayName.isBlank()) return "音色名不能为空"
        val body = buildJsonObject { put("displayName", displayName) }.toString()
        val url = "$base/v1/voices/${URLEncoder.encode(id, Charsets.UTF_8.name())}"
        val request = Request.Builder()
            .url(url)
            .put(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("User-Agent", userAgent)
            .apply { bearerToken(config.token)?.let { header("Authorization", it) } }
            .build()
        return runCatching {
            streamClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) null
                else "HTTP ${response.code}${errorDetail(response)}"
            }
        }.getOrElse { it.message ?: "重命名失败" }
    }

    private fun apiProbe(base: String, config: RemoteTtsConfig): RemoteTtsProbe {
        val status = getBody(probeClient, "$base/status", config)
        val rate = status?.let(::parseSampleRate) ?: 0
        // `--api` mode has no /speaker, but its /healthz reports the registered voices, so
        // the phone can still show which of its own voices the PC actually has.
        val voices = parseVoices(getBody(probeClient, "$base/healthz", config).orEmpty())
        val detail = buildList {
            if (rate > 0) add("采样率 ${rate}Hz")
            if (voices.isNotEmpty()) add("音色 ${voices.joinToString("/")}")
        }.joinToString("，")
        val suffix = if (detail.isEmpty()) "）" else "，$detail）"
        return RemoteTtsProbe(
            ok = true,
            mode = "api",
            message = "连接正常（API 模式$suffix",
            sampleRate = rate,
            speakers = voices,
        )
    }

    private fun webuiProbe(base: String, config: RemoteTtsConfig): RemoteTtsProbe {
        val status = getBody(probeClient, "$base/status", config)
        val rate = status?.let(::parseSampleRate) ?: 0
        val speakers = parseSpeakers(getBody(probeClient, "$base/speaker", config).orEmpty())
        val detail = buildList {
            if (rate > 0) add("采样率 ${rate}Hz")
            if (speakers.isNotEmpty()) add("音色 ${speakers.joinToString("/")}")
        }.joinToString("，")
        val suffix = if (detail.isEmpty()) "）" else "，$detail）"
        return RemoteTtsProbe(
            ok = true,
            mode = "webui",
            message = "连接正常（WebUI 模式$suffix",
            sampleRate = rate,
            speakers = speakers,
        )
    }

    private fun failureFor(response: Response): RemoteTtsException {
        val code = response.code
        val detail = runCatching { response.body?.string() }.getOrNull().orEmpty().take(MAX_ERROR_CHARS)
        val hint = when (code) {
            401, 403 -> "访问口令缺失或错误"
            404 -> "端点不存在（该服务可能不是 cosyvoice-server）"
            else -> "TTS HTTP $code"
        }
        val extra = detail.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()
        return RemoteTtsException("$hint$extra")
    }

    /** `：<server message>` suffix for an error response, or empty. */
    private fun errorDetail(response: Response): String =
        runCatching { response.body?.string() }.getOrNull().orEmpty().trim().take(MAX_ERROR_CHARS)
            .takeIf { it.isNotEmpty() }
            ?.let { "：$it" }
            .orEmpty()

    /** GET returning (status code or null, error message or null); never throws. */
    private fun get(client: OkHttpClient, url: String, config: RemoteTtsConfig): Pair<Int?, String?> {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", userAgent)
            .apply { bearerToken(config.token)?.let { header("Authorization", it) } }
            .build()
        return runCatching {
            client.newCall(request).execute().use { it.code to null }
        }.getOrElse { t -> null to (t.message ?: t.toString()) }
    }

    /** GET returning the body on HTTP 200, else null; never throws. */
    private fun getBody(client: OkHttpClient, url: String, config: RemoteTtsConfig): String? {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", userAgent)
            .apply { bearerToken(config.token)?.let { header("Authorization", it) } }
            .build()
        return runCatching {
            client.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull()
    }

    private fun parseSampleRate(statusBody: String): Int =
        runCatching {
            json.parseToJsonElement(statusBody).jsonObject["sample_rate"]?.jsonPrimitive?.intOrNull ?: 0
        }.getOrDefault(0)

    private fun parseSpeakers(body: String): List<String> =
        runCatching {
            json.parseToJsonElement(body).jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
        }.getOrDefault(emptyList())

    /** `{"model":…,"status":"ok","voices":["a","b"]}` (API mode's `/healthz`). */
    private fun parseVoices(body: String): List<String> =
        runCatching {
            json.parseToJsonElement(body).jsonObject["voices"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                .orEmpty()
        }.getOrDefault(emptyList())

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val OCTET_STREAM_MEDIA_TYPE = "application/octet-stream".toMediaType()
        const val READ_BUFFER_BYTES = 8192
        const val MAX_VOICE_BYTES = 16 * 1024 * 1024
        const val MAX_ERROR_CHARS = 300

        const val PROBE_CONNECT_TIMEOUT_MS = 4_000L
        const val PROBE_READ_TIMEOUT_MS = 4_000L
        const val PROBE_CALL_TIMEOUT_MS = 6_000L

        const val SCAN_CONNECT_TIMEOUT_MS = 400L
        const val SCAN_READ_TIMEOUT_MS = 700L
        const val SCAN_CALL_TIMEOUT_MS = 900L

        /** Bluetooth PAN sweep: the link's own round-trip is tens of milliseconds. */
        const val SLOW_SCAN_CONNECT_TIMEOUT_MS = 1_500L
        const val SLOW_SCAN_READ_TIMEOUT_MS = 1_500L
        const val SLOW_SCAN_CALL_TIMEOUT_MS = 2_500L

        /** Parallel probes during a LAN sweep. */
        const val SCAN_CONCURRENCY = 48

        /** Longest silence tolerated mid-utterance before the request is failed. */
        const val STREAM_IDLE_TIMEOUT_MS = 60_000L
    }
}
