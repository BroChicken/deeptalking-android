package com.deeptalking.core.data.legacy

import com.deeptalking.core.data.CharacterRepository
import com.deeptalking.core.data.ChatRepository
import com.deeptalking.core.data.ConfigRepository
import com.deeptalking.core.data.MetricsRepository
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Result of one legacy import pass. */
data class ImportSummary(
    val characters: Int,
    val messages: Int,
    val stickers: Int,
    val configUpdated: Boolean,
    val activeCharacterId: String? = null,
    /** Characters that could not be decoded/mapped and were skipped (never silent). */
    val skipped: Int = 0,
)

/**
 * One-time migration of legacy JSON (localStorage root or exported backup) into
 * the native Room-backed stores.
 *
 * The root is decoded with a lenient parser, then every character entry is
 * decoded on its own so a single malformed entry is skipped instead of aborting
 * the whole import. Message append failures are likewise swallowed per character.
 */
class LegacyImportService(
    private val characters: CharacterRepository,
    private val chat: ChatRepository,
    private val config: ConfigRepository,
    private val stickerSink: StickerSink? = null,
    /**
     * Independent metrics store. A native backup keeps its rows in a root `requestMetrics`
     * array; an older/WebView backup keeps them in `config.requestMetrics`, which is then
     * adopted **once** via [MetricsRepository.migrateLegacy] (an existing store is never
     * cleared or overwritten by an import).
     */
    private val metrics: MetricsRepository? = null,
) {
    private val codec = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun importJson(json: String): ImportSummary {
        val root = runCatching { codec.parseToJsonElement(json) as? JsonObject }.getOrNull()
            ?: return ImportSummary(0, 0, 0, false)

        val mapped = ArrayList<Character>()
        val archives = LinkedHashMap<String, List<ChatMessage>>()
        var skipped = 0
        val characterEntries = root["characters"] as? JsonObject
        if (characterEntries != null) {
            for ((key, element) in characterEntries) {
                val dto = runCatching {
                    codec.decodeFromJsonElement(LegacyCharacter.serializer(), element)
                }.getOrNull()
                if (dto == null) {
                    skipped++
                    continue
                }
                runCatching { mapCharacter(dto.copy(id = dto.id ?: key), stickerSink) }
                    .onSuccess { character ->
                        mapped.add(character)
                        // `history` is the split-format archive (A). Every older backup
                        // put the whole chat in `memory.instant`, which is then the
                        // archive as well as the window, so old files still restore.
                        val archive = if (dto.history != null) dto.history else dto.memory?.instant
                        archives[character.id] = mapInstantMessages(archive, stickerSink)
                    }
                    .onFailure { skipped++ }
            }
        }

        // Legacy `normalizeAppData` folds the global stickers into the active
        // character (falling back to the first when the id is missing/invalid),
        // and only when that character has no stickers of its own.
        val globalStickerDtos = (root["stickers"] as? JsonArray)
            .orEmpty()
            .mapNotNull { element ->
                runCatching { codec.decodeFromJsonElement(LegacySticker.serializer(), element) }.getOrNull()
            }
        val globalStickers = mapStickers(globalStickerDtos, stickerSink)
        val requestedActiveId = (root["activeCharacterId"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() }
        val activeStickerHostId = requestedActiveId?.takeIf { id -> mapped.any { it.id == id } }
            ?: mapped.firstOrNull()?.id
        val imported = if (globalStickers.isEmpty()) {
            mapped
        } else {
            val host = mapped.firstOrNull { it.id == activeStickerHostId && it.stickers.isEmpty() }
            if (host == null) mapped else mapped.map { if (it === host) it.copy(stickers = globalStickers) else it }
        }

        // Legacy `importData` replaces the whole store, and repairs same-day
        // duplicate event memories before persisting.
        val reconciled = imported.map { reconcileLegacyMemories(it).character }

        val activeCharacterId = requestedActiveId

        var characterCount = 0
        var messageCount = 0
        var stickerCount = 0
        runCatching {
            // Full replace: drop any pre-existing characters/messages first so an
            // import is a faithful restore rather than a merge.
            chat.clearAll()
            characters.replaceAll(reconciled)
            for (character in reconciled) {
                for (message in archives[character.id].orEmpty()) {
                    chat.append(character.id, message)
                }
            }
        }.onSuccess {
            characterCount = reconciled.size
            messageCount = reconciled.sumOf { archives[it.id]?.size ?: 0 }
            stickerCount = reconciled.sumOf { it.stickers.size }
        }

        val configDto = root["config"]?.let { element ->
            runCatching { codec.decodeFromJsonElement(LegacyConfig.serializer(), element) }.getOrNull()
        }
        val exportedTheme = (root["activeTheme"] as? JsonPrimitive)?.contentOrNull
        val configUpdated = configDto != null || exportedTheme != null
        if (configUpdated) {
            runCatching {
                val current = config.current()
                // Older legacy backups have no TTS fields: mapConfig's per-field fallback
                // keeps the current device settings. Native backups carry every field and
                // restore them (including the voice selection and advanced values).
                var mapped = configDto?.let { mapConfig(it, current) } ?: current
                mapped = mapped.copy(
                    dohEnabled = current.dohEnabled,
                    dohProvider = current.dohProvider,
                    lastReplyDebug = current.lastReplyDebug,
                    requestMetrics = current.requestMetrics,
                    platformSettings = mapped.platformSettings.ifEmpty { current.platformSettings },
                )
                if (exportedTheme != null) {
                    // Legacy whitelists the built-in themes; anything else clears.
                    mapped = mapped.copy(activeTheme = exportedTheme.takeIf { it in LEGACY_THEMES } ?: "")
                }
                config.update(mapped)
            }
        }

        // Rolling metrics live outside `AppConfig` now. Restore a native backup's root
        // array as-is; an old backup/WebView file has no root key, so adopt the rows it
        // carried inside `config.requestMetrics` **once** (no-op when the store already has
        // data — an import must never wipe or duplicate what the device recorded).
        val metricStore = metrics
        if (metricStore != null) {
            val exportedMetrics = root["requestMetrics"] as? JsonArray
            if (exportedMetrics != null) {
                runCatching { metricStore.importJson(exportedMetrics.toString(), merge = false) }
            } else {
                val legacyRows = (configDto?.let(::mapConfig) ?: config.current()).requestMetrics
                runCatching { metricStore.migrateLegacy(legacyRows) }
            }
        }

        return ImportSummary(characterCount, messageCount, stickerCount, configUpdated, activeCharacterId, skipped)
    }
}
