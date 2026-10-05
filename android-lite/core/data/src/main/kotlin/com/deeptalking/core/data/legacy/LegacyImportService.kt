package com.deeptalking.core.data.legacy

import com.deeptalking.core.data.CharacterRepository
import com.deeptalking.core.data.ChatRepository
import com.deeptalking.core.data.ConfigRepository
import com.deeptalking.core.model.Character
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull

/** Result of one legacy import pass. */
data class ImportSummary(
    val characters: Int,
    val messages: Int,
    val stickers: Int,
    val configUpdated: Boolean,
    val activeCharacterId: String? = null,
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
) {
    private val codec = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun importJson(json: String): ImportSummary {
        val root = runCatching { codec.parseToJsonElement(json) as? JsonObject }.getOrNull()
            ?: return ImportSummary(0, 0, 0, false)

        val mapped = ArrayList<Character>()
        val characterEntries = root["characters"] as? JsonObject
        if (characterEntries != null) {
            for ((key, element) in characterEntries) {
                val dto = runCatching {
                    codec.decodeFromJsonElement(LegacyCharacter.serializer(), element)
                }.getOrNull() ?: continue
                runCatching { mapCharacter(dto.copy(id = dto.id ?: key), stickerSink) }
                    .onSuccess { mapped.add(it) }
            }
        }

        // Legacy global stickers were folded into the active (first) character.
        val globalStickerDtos = (root["stickers"] as? JsonArray)
            .orEmpty()
            .mapNotNull { element ->
                runCatching { codec.decodeFromJsonElement(LegacySticker.serializer(), element) }.getOrNull()
            }
        val globalStickers = mapStickers(globalStickerDtos, stickerSink)
        val imported = if (globalStickers.isEmpty()) {
            mapped
        } else {
            val host = mapped.firstOrNull { it.stickers.isEmpty() }
            if (host == null) mapped else mapped.map { if (it === host) it.copy(stickers = globalStickers) else it }
        }

        // Legacy `importData` replaces the whole store, and repairs same-day
        // duplicate event memories before persisting.
        val reconciled = imported.map { reconcileLegacyMemories(it).character }

        val activeCharacterId = (root["activeCharacterId"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() }

        var characterCount = 0
        var messageCount = 0
        var stickerCount = 0
        runCatching {
            // Full replace: drop any pre-existing characters/messages first so an
            // import is a faithful restore rather than a merge.
            chat.clearAll()
            characters.replaceAll(reconciled)
            for (character in reconciled) {
                for (message in character.instant) {
                    chat.append(character.id, message)
                }
            }
        }.onSuccess {
            characterCount = reconciled.size
            messageCount = reconciled.sumOf { it.instant.size }
            stickerCount = reconciled.sumOf { it.stickers.size }
        }

        val configDto = root["config"]?.let { element ->
            runCatching { codec.decodeFromJsonElement(LegacyConfig.serializer(), element) }.getOrNull()
        }
        val exportedTheme = (root["activeTheme"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        val configUpdated = configDto != null || exportedTheme != null
        if (configUpdated) {
            runCatching {
                var mapped = configDto?.let(::mapConfig) ?: config.current()
                if (exportedTheme != null) mapped = mapped.copy(activeTheme = exportedTheme)
                config.update(mapped)
            }
        }

        return ImportSummary(characterCount, messageCount, stickerCount, configUpdated, activeCharacterId)
    }
}
