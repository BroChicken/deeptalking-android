package com.deeptalking.domain.agent.background

import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.model.AppConfig
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.GroupMember
import com.deeptalking.core.model.LongTermMemory
import com.deeptalking.core.model.LorebookEntry
import com.deeptalking.core.model.LorebookOrigin
import com.deeptalking.core.model.MemoryCategory
import com.deeptalking.core.model.MemorySubject
import com.deeptalking.core.model.PromiseStatus
import com.deeptalking.core.model.Role
import com.deeptalking.core.model.SceneSummary
import com.deeptalking.core.model.ShortTermMemory
import com.deeptalking.core.model.SourceEvidence
import com.deeptalking.core.model.StaticFillMeta
import com.deeptalking.domain.agent.ApiRetry
import com.deeptalking.domain.agent.ResponseParser
import com.deeptalking.domain.agent.isTransientApiError
import com.deeptalking.domain.agent.tools.MemoryToolSupport
import com.deeptalking.domain.agent.prompts.DYNAMIC_STATE_FIELDS
import com.deeptalking.domain.agent.prompts.GROUP_SHARED_DYNAMIC_FIELDS
import com.deeptalking.domain.agent.prompts.STATIC_PROFILE_FIELDS
import com.deeptalking.domain.agent.prompts.buildRoleContext
import com.deeptalking.domain.agent.prompts.buildVolatileContext
import com.deeptalking.domain.agent.prompts.normalizeTemperature
import com.deeptalking.domain.agent.prompts.normalizeUserAddress
import com.deeptalking.domain.agent.prompts.trimText
import com.deeptalking.domain.agent.prompts.withDynamicField
import com.deeptalking.domain.agent.prompts.withStaticField
import com.deeptalking.domain.agent.sessionIdFor
import com.deeptalking.domain.memory.LorebookLimits
import com.deeptalking.domain.memory.LorebookProposal
import com.deeptalking.domain.memory.MemoryService
import com.deeptalking.domain.memory.ShortTermDraft
import com.deeptalking.domain.memory.addShortTermMemory
import com.deeptalking.domain.memory.applyMemoryDecay
import com.deeptalking.domain.memory.dedupeLorebook
import com.deeptalking.domain.memory.dedupeShortTerm
import com.deeptalking.domain.memory.ensureMessageSequences
import com.deeptalking.domain.memory.evictStaleLorebookEntries
import com.deeptalking.domain.memory.evidenceMatchesSource
import com.deeptalking.domain.memory.getTimeSlot
import com.deeptalking.domain.memory.isValidAutomaticMemory
import com.deeptalking.domain.memory.knownSources
import com.deeptalking.domain.memory.logicalDay
import com.deeptalking.domain.memory.needsLorebookRewrite
import com.deeptalking.domain.memory.parseZoned
import com.deeptalking.domain.memory.resolveDynamicStateSources
import com.deeptalking.domain.memory.selfLearnMemoryImportance
import com.deeptalking.domain.memory.parseRelativeText
import com.deeptalking.domain.memory.resolveTimeRef
import com.deeptalking.domain.memory.TimeRef
import com.deeptalking.domain.memory.upsertLongTermMemory
import com.deeptalking.domain.memory.upsertLorebookEntry
import com.deeptalking.domain.memory.upsertLorebookEntryForCharacter
import com.deeptalking.engine.ondevice.LlmBackend
import com.deeptalking.engine.ondevice.LlmRequest
import com.deeptalking.engine.ondevice.TokenUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

@Serializable
internal data class ExtractSource(
    val id: String,
    val role: String,
    val content: String,
    val timestamp: String? = null,
)

@Serializable
internal data class RecentEventIdentity(
    val eventTime: String? = null,
    val participants: List<String> = emptyList(),
    val location: String = "",
    val content: String = "",
)

@Serializable
internal data class AnalysisInput(
    val id: String,
    val content: String,
    val sourceMessageIds: List<String> = emptyList(),
    val eventTime: String? = null,
    val participants: List<String> = emptyList(),
    val location: String = "",
    val userEvidence: List<SourceEvidence> = emptyList(),
    /**
     * True when this entry was already folded into long-term memory in an earlier
     * pass. It still rides along as context (continuity), but the model must not
     * produce a new long-term entry for it.
     */
    val analyzed: Boolean = false,
)

private data class PreparedPoint(
    val content: String,
    val eventTime: String?,
    val sourceMessageIds: List<String>,
    val firstIndex: Int,
    val participants: List<String>,
    val location: String,
)

private val META_TALK_REGEX = Regex(
    "(好的|收到|明白|知道了)[，,。、]?\\s*(我)?(会|改成|改用|调整|照做|注意|这样(说话|说))|以后我(就)?(用|按)这(种|个)(方式|语气)|我(会)?照你说的(来|说|做)",
)

fun detectStyleViolations(reply: String, character: Character, previousReplies: List<String>): List<String> {
    val text = reply
    val hits = mutableListOf<String>()
    if (text.trim().isEmpty()) return hits
    val address = com.deeptalking.domain.agent.prompts.normalizeUserAddress(character.staticProfile.userAddress)

    if (META_TALK_REGEX.containsMatchIn(text)) hits += "metaTalk"

    val safeAddress = address.replace(Regex("[^0-9A-Za-z\\u4e00-\\u9fff]"), "")
    val quotedLine = if (safeAddress.isNotEmpty()) {
        Regex("(^|\\n)\\s*(用户|$safeAddress|你)\\s*[：:]\\s*[「『“\"]")
    } else {
        Regex("(^|\\n)\\s*(用户|你)\\s*[：:]\\s*[「『“\"]")
    }
    if (quotedLine.containsMatchIn(text)) hits += "speaksForUser"

    if (STYLE_CLICHES.any { text.contains(it) }) hits += "cliche"

    val priors = previousReplies.filter { it.isNotEmpty() }
    if (priors.isNotEmpty()) {
        val currentEnding = extractReplyEnding(text)
        if (currentEnding.isNotEmpty() && priors.any { extractReplyEnding(it) == currentEnding }) {
            hits += "reusedImagery"
        } else {
            val names = listOf(address, character.name).filter { it.isNotEmpty() }
            val strippedCurrent = names.fold(text) { value, name -> value.replace(name, " ") }
            val strippedPriors = priors.map { prior -> names.fold(prior) { value, name -> value.replace(name, " ") } }
            if (sharesDistinctivePhrase(strippedCurrent, strippedPriors)) hits += "reusedImagery"
        }
    }

    if (isSentenceLengthUniform(text)) hits += "toneDrift"

    val recitedLore = character.lorebook.any { entry ->
        val name = entry.name.trim()
        if (name.length < 2 || !text.contains(name)) {
            false
        } else {
            val content = entry.content.trim()
            content.length >= 12 && text.contains(content.take(12))
        }
    }
    if (recitedLore) hits += "recitedLore"

    return hits.distinct()
}

fun detectQuickReplyIssues(replies: List<String>, character: Character, replyText: String): List<String> {
    val list = replies.map { it.trim() }.filter { it.isNotEmpty() }
    val hits = mutableListOf<String>()
    if (list.size < 2) hits += "missing"
    val spoken = replyText.replace(Regex("\\s+"), "")
    val charName = character.name.trim()
    list.forEach { line ->
        val compact = line.replace(Regex("\\s+"), "")
        when {
            QUICK_REPLY_PLACEHOLDERS.contains(line) || Regex("^短句[一二1-9]$").matches(line) ->
                hits += "placeholder"

            Regex("^[（(]").containsMatchIn(line) || Regex("[（(][^）)]{1,20}[）)]").containsMatchIn(line) ->
                hits += "action"

            line.length > QUICK_REPLY_GUARD.maxChars ->
                hits += "tooLong"

            charName.length >= 2 && line.contains(charName) ->
                hits += "characterName"

            compact.length >= 4 && spoken.contains(compact) ->
                hits += "mirrored"

            else -> {
                if (compact.length >= QUICK_REPLY_GUARD.mirrorChars) {
                    var i = 0
                    var found = false
                    while (i + QUICK_REPLY_GUARD.mirrorChars <= compact.length && !found) {
                        val chunk = compact.substring(i, i + QUICK_REPLY_GUARD.mirrorChars)
                        if (chunk.all { it in '\u4e00'..'\u9fff' } && spoken.contains(chunk)) found = true
                        i += 2
                    }
                    if (found) hits += "mirrored"
                }
            }
        }
    }
    return hits.distinct()
}

/** Background memory sub-tasks with independent retry bookkeeping (legacy `memoryTaskKeys`). */
enum class MemoryTaskKind { Extraction, Analysis, Scene, Lorebook }

/**
 * Retry counters for the background memory sub-tasks (legacy
 * `memory.counters.<task>Failures` / `<task>RetryAt`). Exposed so a caller that
 * can persist them keeps the backoff across turns.
 *
 * GAP: [Character] has no `counters` field, so these cannot be stored on the
 * character yet; [BackgroundTasks] keeps them for the lifetime of one instance.
 */
data class MemoryTaskCounters(
    val extractionFailures: Int = 0,
    val extractionRetryAt: String? = null,
    val analysisFailures: Int = 0,
    val analysisRetryAt: String? = null,
    val sceneFailures: Int = 0,
    val sceneRetryAt: String? = null,
    val lorebookFailures: Int = 0,
    val lorebookRetryAt: String? = null,
    /** Diagnostic for the last analysis failure (see `MemoryCounters.analysisLastError`). */
    val analysisLastError: String = "",
) {
    fun failuresOf(task: MemoryTaskKind): Int = when (task) {
        MemoryTaskKind.Extraction -> extractionFailures
        MemoryTaskKind.Analysis -> analysisFailures
        MemoryTaskKind.Scene -> sceneFailures
        MemoryTaskKind.Lorebook -> lorebookFailures
    }

    fun retryAtOf(task: MemoryTaskKind): String? = when (task) {
        MemoryTaskKind.Extraction -> extractionRetryAt
        MemoryTaskKind.Analysis -> analysisRetryAt
        MemoryTaskKind.Scene -> sceneRetryAt
        MemoryTaskKind.Lorebook -> lorebookRetryAt
    }

    fun with(task: MemoryTaskKind, failures: Int, retryAt: String?): MemoryTaskCounters = when (task) {
        MemoryTaskKind.Extraction -> copy(extractionFailures = failures, extractionRetryAt = retryAt)
        MemoryTaskKind.Analysis -> copy(analysisFailures = failures, analysisRetryAt = retryAt)
        MemoryTaskKind.Scene -> copy(sceneFailures = failures, sceneRetryAt = retryAt)
        MemoryTaskKind.Lorebook -> copy(lorebookFailures = failures, lorebookRetryAt = retryAt)
    }
}

/** Records a failure and sets an exponential-backoff retry time (legacy `scheduleMemoryRetry`). */
fun scheduleMemoryRetry(
    counters: MemoryTaskCounters,
    task: MemoryTaskKind,
    nowMillis: Long = System.currentTimeMillis(),
): MemoryTaskCounters {
    val failures = (counters.failuresOf(task) + 1).coerceAtMost(10)
    val delayMinutes = minOf(30L, 5L * (1L shl (failures - 1).coerceIn(0, 4)))
    val retryAt = Instant.ofEpochMilli(nowMillis + delayMinutes * 60_000L).toString()
    return counters.with(task, failures, retryAt)
}

/** Clears a task's failure state after a success (legacy `resetMemoryRetry`). */
fun resetMemoryRetry(counters: MemoryTaskCounters, task: MemoryTaskKind): MemoryTaskCounters =
    counters.with(task, 0, null)

/** True when [task] is past its backoff window (legacy `canRunMemoryTask`). */
fun canRunMemoryTask(
    counters: MemoryTaskCounters,
    task: MemoryTaskKind,
    nowMillis: Long = System.currentTimeMillis(),
): Boolean {
    val retryAt = counters.retryAtOf(task) ?: return true
    val at = parseZoned(retryAt)?.toInstant()?.toEpochMilli() ?: return true
    return at <= nowMillis
}

/** Snapshot used to abort a background memory task that a newer turn has superseded (legacy `captureMemoryTask`). */
data class MemoryTaskGuard(
    val characterId: String,
    val revision: Int,
    val lastMessageId: String?,
)

/** Outcome of one backlog-drain pass: the updated character plus whether it consumed a batch. */
data class DrainOutcome(val character: Character, val progressed: Boolean)

class BackgroundTasks(
    private val llm: LlmBackend,
    private val memory: MemoryService,
    private val model: String = "deepseek-flash",
    private val apiPlatform: String? = null,
    /** Shared app-lifetime queue; defaults to a private one (tests). */
    val queue: BackgroundTaskQueue = BackgroundTaskQueue(),
    private val config: AppConfig = AppConfig(),
    /**
     * Live character lookup for the stale-task guard (legacy `state.characters[id]`).
     * Returns null when the caller has no registry; a null result is treated as
     * "still current" so behaviour is unchanged until the app wires this in.
     */
    private val liveCharacter: suspend (String) -> Character? = { null },
    /** Status text emitted by the app while a background task runs (legacy `setActivity`). */
    private val onStatus: (String) -> Unit = {},
    /** Token usage of auxiliary calls, for the app's usage meter (legacy `recordCacheUsage`). */
    private val onAuxiliaryUsage: (String, TokenUsage) -> Unit = { _, _ -> },
    /**
     * Reason of an auxiliary call that ultimately failed, for the app's request log.
     * Background failures used to leave no trace at all, which made "整理没成功" impossible
     * to diagnose from a save.
     */
    private val onAuxiliaryFailure: suspend (String, String) -> Unit = { _, _ -> },
    /**
     * A non-failure remark about an auxiliary call (e.g. world-book entries retired by the
     * miss counter). Recorded in the app's request log with a `note` status, so a silent
     * drop stops being silent without being reported as a failure.
     */
    private val onAuxiliaryNote: suspend (String, String) -> Unit = { _, _ -> },
    /**
     * Failed world-book batches, held in memory so the next maintenance opportunity can
     * re-feed them (the batch would otherwise be dropped together with the short-term
     * entries the analysis consumed). See [LorebookRetryStore].
     */
    private val lorebookRetries: LorebookRetryStore? = null,
    /**
     * Raised once a world-book batch has failed [LorebookRetryStore.DEFAULT_MAX_ATTEMPTS]
     * times: the app asks the user whether to retry it once more or drop it. Not called
     * again for the same batch until it is cleared or dropped.
     */
    private val onLorebookRetryNeeded: (String) -> Unit = {},
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
    }

    private fun countersOf(character: Character): MemoryTaskCounters = MemoryTaskCounters(
        extractionFailures = character.counters.extractionFailures,
        extractionRetryAt = character.counters.extractionRetryAt,
        analysisFailures = character.counters.analysisFailures,
        analysisRetryAt = character.counters.analysisRetryAt,
        sceneFailures = character.counters.sceneFailures,
        sceneRetryAt = character.counters.sceneRetryAt,
        lorebookFailures = character.counters.lorebookFailures,
        lorebookRetryAt = character.counters.lorebookRetryAt,
        analysisLastError = character.counters.analysisLastError,
    )

    private fun Character.withCounters(counters: MemoryTaskCounters): Character = copy(
        counters = this.counters.copy(
            extractionFailures = counters.extractionFailures,
            extractionRetryAt = counters.extractionRetryAt,
            analysisFailures = counters.analysisFailures,
            analysisRetryAt = counters.analysisRetryAt,
            sceneFailures = counters.sceneFailures,
            sceneRetryAt = counters.sceneRetryAt,
            lorebookFailures = counters.lorebookFailures,
            lorebookRetryAt = counters.lorebookRetryAt,
            analysisLastError = counters.analysisLastError,
        ),
    )

    /** Attaches a diagnostic code (plus the head of the raw reply) to a failed analysis pass. */
    private fun Character.withAnalysisError(code: String, raw: String = ""): Character {
        val head = raw.replace(Regex("\\s+"), " ").trim().take(160)
        val text = if (head.isEmpty()) code else "$code | $head"
        return withCounters(countersOf(this).copy(analysisLastError = text))
    }

    /** Ids from the reply's acknowledgement list that really belong to [analyzableIds]. */
    private fun acknowledgedOf(root: JsonObject, analyzableIds: Set<String>): Set<String> =
        stringList(root["analyzedShortTermIds"]).map { it.trim() }.filter { it in analyzableIds }.toSet()

    fun detectStyleViolations(
        reply: String,
        character: Character,
        previousReplies: List<String>,
    ): List<String> =
        com.deeptalking.domain.agent.background.detectStyleViolations(reply, character, previousReplies)

    fun detectQuickReplyIssues(
        replies: List<String>,
        character: Character,
        replyText: String,
    ): List<String> =
        com.deeptalking.domain.agent.background.detectQuickReplyIssues(replies, character, replyText)

    /**
     * Extracts new short-term events from the live instant window (legacy
     * `extractInstantToShortTerm`). Returns a changed character on success and the
     * unchanged character on failure, so callers can schedule a retry.
     */
    suspend fun extractMemory(
        character: Character,
        messages: List<ChatMessage>,
        migration: Boolean = false,
    ): Character {
        val (status, updated) = extractInstantToShortTermTask(character, migration)
        return if (status == TaskStatus.Success) updated else character
    }

    /** Extraction with an explicit status (legacy `extractInstantToShortTerm`), including `Stale`. */
    suspend fun extractMemoryStatus(
        character: Character,
        messages: List<ChatMessage>,
        migration: Boolean = false,
    ): Pair<TaskStatus, Character> = extractInstantToShortTermTask(character, migration)

    /**
     * Normal fold (legacy `extractInstantToShortTerm`): the window *is* the unit.
     *
     * It grows append-only from the low watermark to the high watermark, is summarized
     * in one pass over **the whole window as it stands** (60, 61, 62 … — never cut to a
     * fixed 60), and afterwards keeps only the newest
     * [AppLimits.Memory.INSTANT_TRIM_FLOOR] messages as verbatim context for the next
     * fold. The low-water leftovers ride along and are read again (continuity);
     * duplication is prevented by showing the model the entries that already exist,
     * not by per-message bookkeeping.
     *
     * This path never batches. A window far above the watermark is a legacy/anomalous
     * backlog owned by [migrateOversizedWindow].
     */
    private suspend fun extractInstantToShortTermTask(
        character: Character,
        migration: Boolean = false,
    ): Pair<TaskStatus, Character> =
        foldWindow(character, if (migration) FoldMode.Migration else FoldMode.Normal)

    /** Which slice of the window one pass consumes. */
    private enum class FoldMode { Normal, Migration }

    /**
     * One fold pass. `Normal` sends the whole window and keeps the newest
     * [AppLimits.Memory.INSTANT_TRIM_FLOOR] messages as context; `Migration` sends only
     * the oldest [AppLimits.Memory.INSTANT] messages (a huge legacy window cannot be
     * sent at once) and drops exactly those, so the backlog is walked oldest-first.
     *
     * The result is merged into the **live** character, not into the captured snapshot:
     * a turn that arrives while the model works keeps its new messages and the fold
     * still lands, instead of the whole pass being thrown away.
     */
    private suspend fun foldWindow(character: Character, mode: FoldMode): Pair<TaskStatus, Character> {
        val window = character.instant.filter { !it.isLoading }
        if (window.size < AppLimits.Memory.INSTANT) return TaskStatus.Failure to character
        // Normal sends the window as it stands (never cut to a fixed 60); migration takes
        // only the oldest batch so a legacy backlog is walked without sending it all.
        val batch = if (mode == FoldMode.Migration) window.take(AppLimits.Memory.INSTANT) else window
        val sourceMessages = batch

        val existingEvents = character.shortTerm
            .sortedByDescending { parseZoned(it.eventTime)?.toInstant()?.toEpochMilli() ?: 0L }
            .take(AppLimits.Memory.SHORT_TERM_TRIM_FLOOR)
            .map {
                RecentEventIdentity(
                    eventTime = it.eventTime,
                    participants = it.participants,
                    location = it.location,
                    content = trimText(it.content, 240),
                )
            }
        val payload = sourceMessages.map { message ->
            ExtractSource(
                id = message.id,
                role = message.role.name.lowercase(),
                content = trimText(message.content, 600),
                timestamp = message.timestamp,
            )
        }
        val countClause = if (mode == FoldMode.Migration) EXTRACTION_COUNT_UNLIMITED else EXTRACTION_COUNT_LIMITED
        val prompt = EXTRACTION_PROMPT_HEAD + countClause + EXTRACTION_PROMPT_TAIL +
            json.encodeToString(existingEvents) + "\n对话内容:\n" + json.encodeToString(payload)

        onStatus("正在提取短期记忆…")
        val reply = completeDetailed(EXTRACTION_SYSTEM, prompt, sessionId = sessionIdFor(character), taskType = "extraction")
        val raw = reply.text
        if (raw.isBlank()) {
            if (reply.error.isNotBlank()) onAuxiliaryFailure("extraction", reply.error)
            return TaskStatus.Failure to character
        }

        val root = ResponseParser.parseJsonLenient(raw)
        var points = (root as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        if (mode == FoldMode.Normal) points = points.take(15)
        if (points.isEmpty()) return TaskStatus.Failure to character

        // The evidence anchor is collected client-side: an event owns the user lines
        // that fall in the same logical day + time slot. No message ids are demanded
        // from the model, so one bad citation can no longer discard the whole fold, and
        // a beat the character narrated alone still gets an entry (with no user quote).
        val userMessages = sourceMessages.filter { it.role == Role.User && it.content.isNotBlank() }
        val prepared = mutableListOf<PreparedPoint>()
        for (point in points) {
            val content = point.string("content")
            val eventTime = point.string("eventTime")
            if (content.isBlank()) continue
            val identity = eventIdentity(eventTime)
            if (identity.isEmpty()) continue
            val ids = userMessages.filter { eventIdentity(it.timestamp) == identity }
                .map { it.id }
                .take(AppLimits.Memory.SUMMARY_SOURCES)
            prepared += PreparedPoint(
                content = content,
                eventTime = eventTime,
                sourceMessageIds = ids,
                firstIndex = 0,
                participants = normalizeParticipants(stringList(point["participants"])),
                location = point.string("location").trim(),
            )
        }
        if (prepared.isEmpty()) return TaskStatus.Failure to character

        // Merge-write. The drafts land on the *live* character, never on the captured
        // snapshot, so a turn that arrives during the request keeps its own new messages
        // and the fold still advances (the old all-or-nothing stale guard discarded the
        // whole pass whenever the user typed again during those seconds).
        // Stale only when the conversation moved on without us: the batch is gone.
        val live = liveCharacter(character.id) ?: character
        val liveIds = live.instant.mapTo(HashSet()) { it.id }
        if (sourceMessages.none { it.id in liveIds }) return TaskStatus.Stale to character

        val nowIso = Instant.now().toString()
        var working = live
        var accepted = 0
        for (point in prepared) {
            var pointTime = normalizeTimestamp(point.eventTime, "")
            if (pointTime.isEmpty()) {
                pointTime = point.sourceMessageIds
                    .mapNotNull { id -> sourceMessages.firstOrNull { it.id == id }?.timestamp }
                    .maxOrNull()
                    .orEmpty()
            }
            if (pointTime.isEmpty()) pointTime = nowIso
            val result = addShortTermMemory(
                list = working.shortTerm,
                draft = ShortTermDraft(
                    content = point.content,
                    sourceMessageIds = point.sourceMessageIds,
                    eventTime = pointTime,
                    participants = point.participants,
                    location = point.location,
                ),
                timestamp = pointTime,
                sources = knownSources(working),
            )
            if (result.accepted) {
                working = working.copy(shortTerm = result.list)
                accepted++
            }
        }
        if (accepted == 0) return TaskStatus.Failure to character

        // Consume exactly what this pass folded — never "keep the newest 20 by position",
        // which on a legacy backlog would throw away messages no pass had ever read.
        // Normal keeps the newest window slice as verbatim context; migration drops the
        // whole batch (there is more backlog behind it). Anything that arrived during
        // the request is untouched.
        val consumed = sourceMessages.mapTo(HashSet()) { it.id }
        val keep: Set<String> = if (mode == FoldMode.Normal) {
            sourceMessages.takeLast(AppLimits.Memory.INSTANT_TRIM_FLOOR).mapTo(HashSet()) { it.id }
        } else {
            emptySet()
        }
        working = working.copy(instant = working.instant.filterNot { it.id in consumed && it.id !in keep })
        return TaskStatus.Success to settleShortTerm(working)
    }

    /**
     * Folds the consumable part of the short-term window into long-term memory (legacy
     * `analyzeShortToLongTerm`). Every entry is re-validated against its declared
     * short-term sources; on failure the character is otherwise returned unchanged, so a
     * failed pass consumes nothing and the same batch is retried under the retry gate.
     */
    suspend fun analyzeShortToLongTerm(character: Character): Character {
        val outcome = analyzeShortToLongTermTask(character)
        // On failure only the counters change (nothing is ever consumed), but the
        // diagnostic is carried through so the caller can persist and show it.
        return if (outcome.status == TaskStatus.Stale) character else outcome.character
    }

    /** Analysis with an explicit status (legacy `analyzeShortToLongTerm`), including `Stale`. */
    suspend fun analyzeShortToLongTermStatus(character: Character): AnalysisOutcome =
        analyzeShortToLongTermTask(character)

    /**
     * One backlog-drain pass for the cleanup: force an analysis batch (ignoring the size
     * trigger and the retry backoff), then run the rest of the maintenance on that same
     * batch. The window is consumed by position, so a pass that reads a batch drops it —
     * this is what lets a piled-up backlog shrink *gradually* instead of being truncated.
     */
    suspend fun drainMemoryBacklog(character: Character): DrainOutcome {
        val outcome = analyzeShortToLongTermStatus(character)
        if (outcome.status == TaskStatus.Stale) return DrainOutcome(character, progressed = false)
        // A successful pass also clears the analysis failure bookkeeping. Only the per-turn
        // path used to do that, so after a cleanup-only recovery a save kept carrying an
        // `analysisFailures` count and a `analysisRetryAt` from a failure long resolved.
        val base = if (outcome.status == TaskStatus.Success) {
            outcome.character.withCounters(
                resetMemoryRetry(countersOf(outcome.character), MemoryTaskKind.Analysis),
            )
        } else {
            outcome.character
        }
        // The world book / scene steps run even after a failed analysis: each has its own
        // retry gate, and the old "analysis failed → skip everything" short-circuit is how
        // a stuck analysis silently stopped the world book from ever being updated.
        //
        // This path consumes a batch without an extraction, so it is the world book's own
        // trigger here: feed it the batch this pass is about to drop (this is also what a
        // user-invoked "整理记忆" relies on). A failure is held in memory for the next pass.
        var working = runMemoryMaintenance(base)
        if (outcome.consumed.isNotEmpty()) {
            val held = lorebookRetries?.batch(base.id).orEmpty()
            val batch = (outcome.consumed + held).distinctBy { it.id }
            if (lorebookRetries?.awaitingUser(base.id) != true &&
                canRunMemoryTask(countersOf(working), MemoryTaskKind.Lorebook)
            ) {
                val (lorebookStatus, afterLorebook) = consolidateLorebook(working, batch)
                when (lorebookStatus) {
                    TaskStatus.Success -> {
                        working = afterLorebook
                        lorebookRetries?.clear(base.id)
                        working = working.withCounters(
                            resetMemoryRetry(countersOf(working), MemoryTaskKind.Lorebook),
                        )
                    }
                    TaskStatus.Failure -> {
                        val state = lorebookRetries?.recordFailure(base.id, batch)
                        if (state?.awaitingUser == true) onLorebookRetryNeeded(base.id)
                        working = working.withCounters(
                            scheduleMemoryRetry(countersOf(working), MemoryTaskKind.Lorebook),
                        )
                    }
                    TaskStatus.Stale -> Unit
                }
            }
        }
        return DrainOutcome(
            character = working,
            progressed = outcome.status == TaskStatus.Success && outcome.consumed.isNotEmpty(),
        )
    }

    private suspend fun analyzeShortToLongTermTask(character: Character): AnalysisOutcome =
        analyzeShortTermBatch(character)

    /**
     * One analysis pass over the *consumable* part of the short-term window.
     *
     * The window is a high/low watermark pair (see [AppLimits.Memory.SHORT_TERM]) and it is
     * consumed **by position**: everything older than the newest
     * [AppLimits.Memory.SHORT_TERM_TRIM_FLOOR] entries is this pass's batch (at most
     * [AppLimits.Memory.ANALYSIS_BATCH] entries), the newer leftovers ride along as context
     * (continuity) without producing entries, and on success exactly the acknowledged batch
     * is dropped. "Already consumed" is therefore simply "no longer in the window" — there
     * is no per-entry marker, so a failed pass loses nothing: the batch is still there and
     * the retry gate re-runs it.
     */
    private suspend fun analyzeShortTermBatch(character: Character): AnalysisOutcome {
        val consumable = (character.shortTerm.size - AppLimits.Memory.SHORT_TERM_TRIM_FLOOR).coerceAtLeast(0)
        // Nothing to do is *not* a failure: the window is at (or below) its low-water mark.
        if (consumable <= 0) return AnalysisOutcome(TaskStatus.Success, character)
        val batch = character.shortTerm.take(minOf(consumable, AppLimits.Memory.ANALYSIS_BATCH))
        val batchIds = batch.mapTo(HashSet()) { it.id }
        val context = character.shortTerm.takeLast(AppLimits.Memory.SHORT_TERM_TRIM_FLOOR)
            .filter { it.id !in batchIds }

        val inputs = (batch.map { it to false } + context.map { it to true }).map { (item, isContext) ->
            AnalysisInput(
                id = item.id,
                content = item.content,
                sourceMessageIds = item.sourceMessageIds,
                eventTime = item.eventTime,
                participants = item.participants,
                location = item.location,
                userEvidence = item.userEvidence,
                analyzed = isContext,
            )
        }
        val prompt = ANALYSIS_PROMPT_HEAD + json.encodeToString(inputs) + ANALYSIS_PROMPT_TAIL
        val guard = captureGuard(character)
        onStatus("正在分析长期记忆…")
        val reply = completeDetailed(ANALYSIS_SYSTEM, prompt, sessionId = sessionIdFor(character), taskType = "analysis")
        val raw = reply.text
        if (!isGuardCurrent(guard)) return AnalysisOutcome(TaskStatus.Stale, character)
        if (raw.isBlank()) {
            val code = if (reply.error.isNotBlank()) "call: " + reply.error else "empty-reply"
            return AnalysisOutcome(TaskStatus.Failure, character.withAnalysisError(code))
        }

        val parsed = ResponseParser.parseJsonLenient(raw) as? JsonObject
        if (parsed != null && parsed["status"] != null && !parsed.string("status").equals("ok", ignoreCase = true)) {
            return AnalysisOutcome(TaskStatus.Failure, character.withAnalysisError("status=" + parsed.string("status"), raw))
        }
        val analyzableIds = batchIds
        fun hasElements(candidate: JsonObject): Boolean =
            (candidate["longTerm"] as? JsonArray)?.isNotEmpty() == true
        fun hasAck(candidate: JsonObject): Boolean =
            acknowledgedOf(candidate, analyzableIds).isNotEmpty()
        // The lenient parser silently drops a truncated array, so salvage is consulted
        // whenever the parse produced no usable elements (or no acknowledgement at all).
        val salvaged = salvageAnalysisReply(raw)
        val root = parsed?.takeIf { hasAck(it) && hasElements(it) }
            ?: salvaged?.takeIf { hasAck(it) }
            ?: parsed?.takeIf { hasAck(it) }
            ?: return AnalysisOutcome(TaskStatus.Failure, character.withAnalysisError("unparsable", raw))
        val longTermArray = root["longTerm"] as? JsonArray ?: JsonArray(emptyList())
        // Tolerant acknowledgement: the model only has to echo *some* of this batch's ids
        // correctly. Missing ids simply stay in the window (read again by the next pass)
        // and foreign ids are ignored — demanding an exact set was another way a single
        // imperfect reply dead-locked the queue forever.
        val acknowledged = acknowledgedOf(root, analyzableIds)
        if (acknowledged.isEmpty()) {
            return AnalysisOutcome(TaskStatus.Failure, character.withAnalysisError("no-acknowledged-ids", raw))
        }

        data class Prepared(val item: LongTermMemory, val shortTermIds: List<String>)
        val sources = knownSources(character)
        val prepared = mutableListOf<Prepared>()
        // Per-item tolerance: one malformed entry must never fail the whole batch. The
        // batch stays in the window until it is acknowledged, so a single entry that
        // cannot pass validation used to "poison" the queue and permanently dead-lock
        // short-term memory (nothing ever gets consumed, so the window never shrinks).
        // Structural problems (unparseable JSON, status != ok, no acknowledged ids) still
        // fail the batch.
        for (element in longTermArray) {
            val obj = element as? JsonObject ?: continue
            var item = parseLongTerm(obj)
            val sourceShortTermIds = stringList(obj["sourceShortTermIds"]).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (sourceShortTermIds.isEmpty() || sourceShortTermIds.any { it !in acknowledged }) continue
            val claimed = batch.filter { it.id in sourceShortTermIds }
            // The chain is anchored on the short-term entries, not on raw message ids:
            // the evidence must be one of the verbatim user lines those entries carry
            // (collected at fold time, so it survives the source message leaving the
            // window). The item is then re-anchored on that exact line, so the validated
            // write below cannot fail on a message id the model invented.
            val evidence = claimed.flatMap { it.userEvidence }
                .firstOrNull { evidenceMatchesSource(it.text, item.evidence) } ?: continue
            item = item.copy(
                evidence = evidence.text.take(300),
                sourceMessageIds = listOf(evidence.sourceMessageId),
            )
            if (item.key.isBlank() || item.value.isBlank() ||
                !isValidAutomaticMemory(character, item, sources, null)
            ) continue
            prepared += Prepared(item, sourceShortTermIds)
        }

        var working = character
        for (entry in prepared) {
            val (newList, ok) = upsertLongTermMemory(
                character = working,
                item = entry.item,
                store = working.longTerm,
                sources = sources,
                assistantMessage = null,
            )
            if (!ok) continue
            working = working.copy(longTerm = newList)
        }

        // Consume the batch: drop exactly what this pass read. The live row is the base for
        // the resulting list, so an entry a correction rewrote while the request was in
        // flight keeps its NEW text (and is held back rather than dropped unread), and any
        // entry added meanwhile survives. The newest entries are never part of the batch,
        // so the window keeps its low-water context.
        val live = liveCharacter(character.id)
        val snapshotRevision = batch.associate { it.id to it.revision }
        val liveRevision = live?.shortTerm?.associate { it.id to it.revision }.orEmpty()
        val drop = acknowledged
            .filter { snapshotRevision[it] == (liveRevision[it] ?: snapshotRevision[it]) }
            .toSet()
        working = working.copy(shortTerm = (live ?: character).shortTerm.filterNot { it.id in drop })
        val consumed = character.shortTerm.filter { it.id in drop }
        // Success clears the diagnostic, so a stale reason never lingers in the save.
        return AnalysisOutcome(TaskStatus.Success, settleShortTerm(working.withAnalysisError("")), consumed)
    }

    /**
     * Outcome of one analysis pass: the status, the updated character, and the entries the
     * pass consumed (the world-book pass reads exactly this batch, one step later).
     */
    data class AnalysisOutcome(
        val status: TaskStatus,
        val character: Character,
        val consumed: List<ShortTermMemory> = emptyList(),
    )

    /** Outcome of a memory sub-task: Success updates state, Failure retries, Stale aborts. */
    enum class TaskStatus { Success, Failure, Stale }

    /**
     * Applies the emergency short-term ceiling.
     *
     * The instant window and the ordinary short-term retention are deliberately NOT touched
     * here: both windows are consumed by the pass that read them (the fold drops the
     * messages it folded, the analysis drops the batch it analyzed), so a positional trim
     * here could only ever drop entries no pass had read yet — which is exactly how the old
     * backlog used to vanish. The ceiling is only reached when that keeps failing, and
     * hitting it is reported through `counters.analysisLastError`, so dropped content is
     * never silent.
     */
    private fun trimMemory(character: Character): Character {
        val shortOverflow = (character.shortTerm.size - AppLimits.Memory.SHORT_TERM_HARD_CAP).coerceAtLeast(0)
        if (shortOverflow <= 0) return character
        return character
            .copy(shortTerm = character.shortTerm.drop(shortOverflow))
            .withAnalysisError("window-overflow: dropped $shortOverflow short-term (analysis failing)")
    }

    /**
     * Window hygiene at the only moments the injected short-term block is allowed to change:
     * right after a pass folded or consumed part of it. That consumption already invalidates
     * the cached request prefix, so merging near-duplicates here is free; between passes the
     * block stays byte-stable, because a merge in the middle would rewrite a prefix the next
     * request cannot predict (a cache miss every turn).
     *
     * The write path only merges into the newest few entries (append-only), so a beat the
     * fold re-read as low-water context and re-described lands as a second summary of the
     * same event. This pass collapses those.
     */
    private fun settleShortTerm(character: Character): Character =
        trimMemory(character.copy(shortTerm = dedupeShortTerm(character.shortTerm)))

    /** Captures the revision/last-message snapshot for the stale guard (legacy `captureMemoryTask`). */
    private fun captureGuard(character: Character): MemoryTaskGuard = MemoryTaskGuard(
        characterId = character.id,
        revision = character.revision,
        lastMessageId = character.instant.lastOrNull()?.id,
    )

    /**
     * True when [guard] has not been superseded by a newer turn (legacy `isMemoryTaskCurrent`).
     * When no live registry is wired ([liveCharacter] returns null) the task is assumed current.
     */
    private suspend fun isGuardCurrent(guard: MemoryTaskGuard): Boolean {
        val live = liveCharacter(guard.characterId) ?: return true
        return live.revision == guard.revision && live.instant.lastOrNull()?.id == guard.lastMessageId
    }

    /** Current scene key = trimmed current location, or "未说明" (legacy `getSceneKey`). */
    fun sceneKey(character: Character): String =
        trimText(character.dynamicState.currentLocation.trim().ifEmpty { "未说明" }, 80)

    /**
     * Scene-summary trigger + execution (legacy `checkMemoryTriggers` scene branch).
     * Summarizes when the location changed or the scene grew past the span, once
     * at least [AppLimits.Scene.MIN_MESSAGES] messages have accumulated.
     */
    suspend fun checkScene(character: Character): Pair<TaskStatus, Character> {
        val currentKey = sceneKey(character)
        val state = character.sceneState
        val startSequence = state?.startSequence ?: 0
        val messages = character.instant.filter { !it.isLoading }
        val messageCount = messages.count { it.sequence > startSequence }
        val changed = state != null && state.key.isNotEmpty() && state.key != currentKey
        val longEnough = messageCount >= AppLimits.Scene.SPAN
        val ready = (changed || longEnough) && messageCount >= AppLimits.Scene.MIN_MESSAGES
        if (ready) {
            val (status, updated) = summarizeScene(character, state?.key?.ifEmpty { currentKey } ?: currentKey)
            return when (status) {
                TaskStatus.Success -> TaskStatus.Success to updated.copy(
                    sceneState = com.deeptalking.core.model.SceneState(
                        key = currentKey,
                        startCount = 0,
                        startSequence = updated.counters.messageSequence,
                        messageCount = 0,
                    ),
                )
                else -> status to character
            }
        }
        return TaskStatus.Success to character.copy(
            sceneState = com.deeptalking.core.model.SceneState(
                key = currentKey,
                startCount = state?.startCount ?: 0,
                startSequence = state?.startSequence,
                messageCount = messageCount,
            ),
        )
    }

    /** Compresses the messages after the scene cursor into one `scenes` summary. */
    suspend fun summarizeScene(character: Character, sceneKey: String): Pair<TaskStatus, Character> {
        val startSequence = character.sceneState?.startSequence ?: 0
        val slice = character.instant
            .filter { !it.isLoading && it.sequence > startSequence }
            .takeLast(AppLimits.Memory.INSTANT_TRIM_FLOOR)
        if (slice.size < AppLimits.Scene.MIN_MESSAGES) return TaskStatus.Failure to character
        val speakerName = character.name.ifBlank { "角色" }
        val transcript = slice.mapIndexed { index, message ->
            "[" + (index + 1) + "] " + (if (message.role == Role.User) "用户" else speakerName) +
                ": " + trimText(message.content, 500)
        }.joinToString("\n")
        val guard = captureGuard(character)
        onStatus("正在整理场景概要…")
        val raw = complete(SCENE_SYSTEM, SCENE_PROMPT + transcript, sessionId = sessionIdFor(character), taskType = "scene")
        if (!isGuardCurrent(guard)) return TaskStatus.Stale to character
        val summary = raw.trim()
        if (summary.length < 8) return TaskStatus.Failure to character
        val scene = SceneSummary(
            id = newId("scene"),
            key = trimText(sceneKey, 80).ifEmpty { sceneKey(character) },
            content = trimText(summary, 2000),
            startedAt = slice.firstOrNull()?.timestamp,
            endedAt = slice.lastOrNull()?.timestamp,
            createdAt = Instant.now().toString(),
        )
        return TaskStatus.Success to character.copy(
            scenes = (character.scenes + scene).takeLast(AppLimits.Scene.RETAIN),
        )
    }

    /**
     * Consolidates settled facts into the world book and retires stale AI entries (legacy
     * `consolidateLorebook`). [fresh] is the batch the analysis pass just consumed: the
     * world book reads exactly those events, one step later, which is why there is no
     * per-entry "已扫过" marker any more. A structurally malformed reply returns `Failure`
     * (retried by the gate); a single unusable entry is skipped and the rest of the batch
     * still lands, with the reason reported — the text is never truncated.
     */
    suspend fun consolidateLorebook(character: Character, fresh: List<ShortTermMemory>): Pair<TaskStatus, Character> {
        if (fresh.isEmpty()) return TaskStatus.Success to character

        val books = mutableListOf(character.lorebook)
        character.members.forEach { books += it.lorebook }
        val flat = books.flatten()
        // The model can only merge/rewrite an entry it has actually read, so the most
        // recently updated entries carry their FULL content; the rest are name +
        // keywords only, and the prompt forbids updating those this round. Showing just
        // the first 160 characters is exactly what made the model "update" entries by
        // appending what it could not see — growing them into a mush of unrelated facts.
        var detailBudget = LOREBOOK_EXISTING_FULL_CHARS
        val detailed = mutableSetOf<String>()
        for (entry in flat.sortedByDescending { it.updatedAt.orEmpty() }.take(LOREBOOK_EXISTING_FULL_ENTRIES)) {
            if (entry.content.length > detailBudget) continue
            detailBudget -= entry.content.length
            detailed += entry.id
        }
        val existingLines = flat.map { entry ->
            "- " + entry.name + "｜关键词: " + entry.keywords.joinToString("、") +
                (if (entry.alwaysActive) "｜常驻" else "") +
                if (entry.id in detailed) "｜内容: " + entry.content else "｜内容（略，本轮不要更新此条）"
        }
        val sourceLines = fresh.map { "[" + it.id + "] " + trimText(it.content, 600) }
        val speakerName = character.name.ifBlank { "角色" }
        val prompt = LOREBOOK_PROMPT_HEAD + (existingLines.joinToString("\n").ifEmpty { "（空）" }) +
            LOREBOOK_PROMPT_TAIL + "（角色名：" + speakerName + "）：\n" + sourceLines.joinToString("\n")

        onStatus("正在整理世界书…")
        val reply = completeDetailed(LOREBOOK_SYSTEM, prompt, sessionId = sessionIdFor(character), taskType = "lorebook")
        val raw = reply.text
        if (raw.isBlank()) {
            if (reply.error.isNotBlank()) onAuxiliaryFailure("lorebook", reply.error)
            return TaskStatus.Failure to character
        }
        val root = ResponseParser.parseJsonLenient(raw) as? JsonObject ?: return TaskStatus.Failure to character
        val entries = root["entries"] as? JsonArray ?: return TaskStatus.Failure to character

        val allowed = fresh.map { it.id }.toSet()
        // Per-entry validation: the model is asked for <=300 characters per entry, 400 is the
        // hard line. An entry over the line is **sent back once** for a shorter rewrite — it is
        // never truncated and never silently dropped. An entry citing nothing / inventing a
        // source cannot be anchored and is reported instead (`invalid`).
        val items = mutableListOf<JsonObject>()
        val overLong = mutableListOf<JsonObject>()
        var invalid = 0
        for (entry in entries.take(AppLimits.Lorebook.AUTO_ENTRIES_PER_PASS)) {
            val obj = entry as? JsonObject
            val name = obj?.string("name").orEmpty()
            val content = obj?.string("content").orEmpty()
            val ids = obj?.let { candidate ->
                stringList(candidate["sourceShortTermIds"]).map { it.trim() }.filter { it.isNotEmpty() }
            }.orEmpty()
            if (obj == null || name.isBlank() || content.isBlank() || ids.isEmpty() ||
                ids.any { it !in allowed }
            ) {
                invalid++
                continue
            }
            if (content.trim().length > AppLimits.Lorebook.CONTENT_CHARS) {
                overLong += obj
                continue
            }
            items += obj
        }

        // One re-ask round for the over-long entries (legacy "打回重写"): the model gets the
        // same names/keywords/sources plus its own text back, and is told to compress it. A
        // rewrite that still exceeds the cap is given up on — and reported, never cut.
        var dropped = 0
        if (overLong.isNotEmpty()) {
            val overLongNames = overLong.map { it.string("name") }.toSet()
            val fixPrompt = LOREBOOK_FIX_PROMPT + overLong.joinToString("\n") { obj ->
                "- name: " + obj.string("name") +
                    "｜keywords: " + stringList(obj["keywords"]).joinToString("、") +
                    "｜sourceShortTermIds: " + stringList(obj["sourceShortTermIds"]).joinToString("、") +
                    "｜content: " + obj.string("content")
            }
            onStatus("正在压缩世界书条目…")
            val fixReply = completeDetailed(LOREBOOK_SYSTEM, fixPrompt, sessionId = sessionIdFor(character), taskType = "lorebook")
            val fixEntries = (ResponseParser.parseJsonLenient(fixReply.text) as? JsonObject)?.get("entries") as? JsonArray
            val fixed = mutableSetOf<String>()
            for (element in fixEntries ?: JsonArray(emptyList())) {
                val obj = element as? JsonObject ?: continue
                val name = obj.string("name")
                val content = obj.string("content")
                val ids = stringList(obj["sourceShortTermIds"]).map { it.trim() }.filter { it.isNotEmpty() }
                if (name !in overLongNames || name in fixed) continue
                if (content.isBlank() || ids.isEmpty() || ids.any { it !in allowed }) continue
                if (content.trim().length > AppLimits.Lorebook.CONTENT_CHARS) continue
                items += obj
                fixed += name
            }
            dropped = overLong.size - fixed.size
        }

        if (dropped > 0 || invalid > 0) {
            onAuxiliaryFailure(
                "lorebook",
                "dropped $dropped still-over-${AppLimits.Lorebook.CONTENT_CHARS}-char (rewrite failed) / $invalid invalid entries",
            )
        }

        // Eviction only runs after the batch parsed cleanly (legacy order). Retired entries
        // are real content: they are reported (as a note, not a failure) so "这条设定怎么
        // 没了" has an answer in the request log instead of being silent.
        val rootEviction = evictStaleLorebookEntries(character.lorebook)
        val memberEvictions = character.members.map { it to evictStaleLorebookEntries(it.lorebook) }
        val retired = rootEviction.removed + memberEvictions.flatMap { it.second.removed }
        if (retired.isNotEmpty()) {
            onAuxiliaryNote(
                "lorebook",
                "淘汰了 ${retired.size} 条世界书条目（连续 ${LorebookLimits().evictionMisses} 次整理未被提及）：" +
                    retired.joinToString("、") { it.name },
            )
        }
        var working = character.copy(
            lorebook = rootEviction.kept,
            members = character.members.map { member ->
                val kept = memberEvictions.firstOrNull { it.first.id == member.id }?.second?.kept
                member.copy(lorebook = kept ?: member.lorebook)
            },
        )
        var written = 0
        val refused = mutableListOf<String>()
        items.forEach { obj ->
            val proposal = LorebookProposal(
                name = obj.string("name"),
                content = obj.string("content"),
                keywords = stringList(obj["keywords"]),
                // null = the model omitted the field, which must not switch a stored
                // 常驻 entry off (only an explicit false may).
                alwaysActive = optionalBoolean(obj, "alwaysActive"),
            )
            val ids = stringList(obj["sourceShortTermIds"]).map { it.trim() }.filter { it.isNotEmpty() }
            val (next, result) = upsertLorebookEntryForCharacter(working, proposal, ids)
            working = next
            when {
                !result.ok -> refused += "${proposal.name}（${result.reason}）"
                result.mergedIntoUser -> refused += "${proposal.name}（已有同设定的用户手写条目）"
                else -> written++
            }
        }
        // "写了几条 / 为什么没写" must be answerable from the device: a refused write used
        // to be dropped on the floor, so a world book that never grew looked like a model
        // that never tried.
        onAuxiliaryNote(
            "lorebook",
            "本次整理写入 $written 条（来源 ${fresh.size} 条短期记忆）" +
                (if (refused.isNotEmpty()) "，跳过 ${refused.size} 条：${refused.joinToString("；").take(300)}" else ""),
        )
        working = working.copy(
            lorebook = dedupeLorebook(working.lorebook),
            members = working.members.map { it.copy(lorebook = dedupeLorebook(it.lorebook)) },
            counters = working.counters.copy(
                lorebookScannedCount = working.counters.lorebookScannedCount + fresh.size,
            ),
        )
        return TaskStatus.Success to working
    }

    /** AI entries over the per-entry cap that still need the one-time rewrite (root + members). */
    fun oversizedLorebookCount(character: Character): Int =
        character.lorebook.count { needsLorebookRewrite(it) } +
            character.members.sumOf { member -> member.lorebook.count { needsLorebookRewrite(it) } }

    /**
     * One-time rewrite of the oversized AI lorebook entries older versions accumulated.
     *
     * Each oversized entry is handed to the model **in full** and rewritten into 1..3
     * concise entries (one concept each, within `AppLimits.Lorebook.CONTENT_CHARS`),
     * replacing the original — the first result keeps the original id so injection and
     * mention history stay attached. User-written entries are never sent or touched.
     *
     * Runs to completion in one call (the caller shows progress). `Failure` keeps
     * whatever was already rewritten, so a retry resumes rather than repeating work.
     */
    suspend fun rebuildOversizedLorebook(
        character: Character,
        onProgress: (done: Int, remaining: Int) -> Unit = { _, _ -> },
    ): Pair<TaskStatus, Character> {
        val targets = mutableListOf<Pair<String, String>>()
        character.lorebook.filter { needsLorebookRewrite(it) }.forEach { targets += "" to it.id }
        character.members.forEach { member ->
            member.lorebook.filter { needsLorebookRewrite(it) }.forEach { targets += member.name to it.id }
        }
        if (targets.isEmpty()) return TaskStatus.Success to character
        var working = character
        var done = 0
        for ((memberName, entryId) in targets) {
            val (status, updated) = rewriteLorebookEntry(working, memberName, entryId)
            if (status != TaskStatus.Success) return status to working
            working = updated
            done++
            onProgress(done, targets.size - done)
        }
        return TaskStatus.Success to working
    }

    /** Rewrites one oversized entry into 1..3 concise ones, replacing it in place. */
    private suspend fun rewriteLorebookEntry(
        character: Character,
        memberName: String,
        entryId: String,
    ): Pair<TaskStatus, Character> {
        val memberIndex = if (memberName.isBlank()) -1 else character.members.indexOfFirst { it.name == memberName }
        val list = if (memberIndex < 0) character.lorebook else character.members[memberIndex].lorebook
        val entry = list.firstOrNull { it.id == entryId } ?: return TaskStatus.Success to character
        if (!needsLorebookRewrite(entry)) return TaskStatus.Success to character

        val speakerName = character.name.ifBlank { "角色" }
        val prompt = LOREBOOK_REBUILD_PROMPT +
            "\n\n（角色名：" + speakerName + "）\n原条目：\n名称：" + entry.name +
            "\n关键词：" + entry.keywords.joinToString("、").ifEmpty { "（无）" } +
            "\n常驻：" + if (entry.alwaysActive) "true" else "false" +
            "\n内容：\n" + entry.content
        onStatus("正在精简世界书…")
        val raw = complete(LOREBOOK_REBUILD_SYSTEM, prompt, sessionId = sessionIdFor(character), taskType = "lorebook")
        if (raw.isBlank()) return TaskStatus.Failure to character
        val root = ResponseParser.parseJsonLenient(raw) as? JsonObject ?: return TaskStatus.Failure to character
        val items = root["entries"] as? JsonArray ?: return TaskStatus.Failure to character

        val now = Instant.now().toString()
        var activeCount = list.count { it.origin == LorebookOrigin.Model && it.alwaysActive }
        val rewritten = mutableListOf<LorebookEntry>()
        for ((index, item) in items.take(LOREBOOK_REBUILD_MAX_ENTRIES).withIndex()) {
            val obj = item as? JsonObject ?: continue
            val name = obj.string("name").trim().take(AppLimits.Lorebook.NAME_CHARS)
            val content = obj.string("content").trim()
            if (name.isEmpty() || content.isEmpty()) continue
            // Keep the original entry instead of storing a half-entry: the prompt demands
            // ≤ CONTENT_CHARS, and a retry re-asks against the same source text.
            if (content.length > AppLimits.Lorebook.CONTENT_CHARS) return TaskStatus.Failure to character
            // The prompt echoes the entry's 常驻 state; an omitted field keeps the source
            // entry's value instead of silently demoting it.
            val wantsActive = optionalBoolean(obj, "alwaysActive") ?: entry.alwaysActive
            val alwaysActive = wantsActive && activeCount < AppLimits.Lorebook.MAX_ALWAYS_ACTIVE
            if (alwaysActive) activeCount++
            rewritten += LorebookEntry(
                id = if (index == 0) entry.id else newId("lore"),
                name = name,
                content = content,
                keywords = stringList(obj["keywords"]).map { it.trim() }.filter { it.isNotEmpty() }
                    .take(AppLimits.Lorebook.KEYWORDS_PER_ENTRY),
                alwaysActive = alwaysActive,
                origin = LorebookOrigin.Model,
                memberName = entry.memberName,
                createdAt = entry.createdAt ?: now,
                updatedAt = now,
                order = entry.order,
            )
        }
        if (rewritten.isEmpty()) return TaskStatus.Failure to character
        val replaced = list.flatMap { if (it.id == entry.id) rewritten else listOf(it) }
            .take(AppLimits.Lorebook.ENTRIES)
        return TaskStatus.Success to if (memberIndex < 0) {
            character.copy(lorebook = replaced)
        } else {
            character.copy(
                members = character.members.mapIndexed { index, member ->
                    if (index == memberIndex) member.copy(lorebook = replaced) else member
                },
            )
        }
    }

    /**
     * Scene summaries + adaptive importance.
     *
     * The world-book pass is **not** here any more: it runs from the short-term extraction
     * step (see [scheduleTurn]) on the batch the fold just produced, which is both earlier
     * and safer — the batch is still in the window, so a failed pass can be re-fed instead
     * of vanishing with the entries the analysis consumes.
     */
    suspend fun runMemoryMaintenance(
        character: Character,
    ): Character {
        var working = ensureMessageSequences(character)
        working = applyMemoryDecay(working)
        working = selfLearnMemoryImportance(working)
        var counters = countersOf(working)
        val nowMillis = System.currentTimeMillis()

        if (canRunMemoryTask(counters, MemoryTaskKind.Scene, nowMillis)) {
            val (sceneStatus, afterScene) = checkScene(working)
            working = afterScene
            counters = when (sceneStatus) {
                TaskStatus.Success -> resetMemoryRetry(counters, MemoryTaskKind.Scene)
                TaskStatus.Failure -> scheduleMemoryRetry(counters, MemoryTaskKind.Scene, nowMillis)
                TaskStatus.Stale -> counters
            }
        }

        working = working.withCounters(counters)
        return working
    }

    /**
     * Applies one parsed memory-update object (legacy `applyMemoryUpdate`):
     * long-term writes go through [upsertLongTermMemory] and `dynamicState` through
     * the source-evidence gate. Short-term writes are deliberately *not* handled here:
     * the window is only appended to by the single whole-window extraction pass (see
     * `scheduleTurn`), so `shortTerm` in a model reply update is ignored.
     * `staticFields` / `memberDynamicState` / `promiseUpdates` are handled by the
     * orchestrator, not here.
     */
    fun applyMemoryUpdate(
        character: Character,
        update: JsonObject,
        assistantMessage: ChatMessage?,
        allowedSourceIds: Set<String>? = null,
    ): Character {
        var working = character
        val nowBase = ZonedDateTime.now()
        (update["longTerm"] as? JsonArray)?.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            var item = resolveLongTermTimeRef(parseLongTerm(obj), ResponseParser.parseTimeRef(obj), nowBase)
            if (allowedSourceIds != null) {
                val ids = item.sourceMessageIds.filter { it in allowedSourceIds }
                if (ids.isEmpty()) return@forEach
                item = item.copy(sourceMessageIds = ids)
            }
            val (list, ok) = upsertLongTermMemory(working, item, working.longTerm, knownSources(working), assistantMessage)
            if (ok) working = working.copy(longTerm = list)
        }
        (update["dynamicState"] as? JsonObject)?.let { dynamic ->
            working = applyDynamicStateUpdate(working, dynamic, assistantMessage)
        }
        return working
    }

    private fun applyDynamicStateUpdate(
        character: Character,
        dynamic: JsonObject,
        assistantMessage: ChatMessage?,
    ): Character {
        var state = character.dynamicState
        val sources = knownSources(character)
        val nowBase = ZonedDateTime.now()
        DYNAMIC_STATE_FIELDS.forEach { (key, _) ->
            val obj = dynamic[key] as? JsonObject ?: return@forEach
            val raw = obj.string("value").trim()
            if (raw.isEmpty()) return@forEach
            val value = MemoryToolSupport.cleanFieldValue(key, parseRelativeText(raw, resolveFieldBase(obj, nowBase)))
            if (value.isEmpty()) return@forEach
            // `currentTone` is the read-aloud delivery instruction (self-referential),
            // accepted without the user-evidence gate.
            if (key != "currentTone") {
                val ids = stringList(obj["sourceMessageIds"])
                val evidence = obj.string("evidence")
                resolveDynamicStateSources(character, ids, evidence, assistantMessage, sources) ?: return@forEach
            }
            state = withDynamicField(state, key, value)
        }
        return character.copy(dynamicState = state)
    }

    /** Resolves a memory-update `timeRef` to an ISO event time, falling back to the raw/now value. */
    private fun resolveEventTime(eventTime: String?, timeRef: TimeRef?, nowBase: ZonedDateTime): String {
        val base = parseZoned(eventTime) ?: nowBase
        resolveTimeRef(timeRef, base)?.iso?.let { return it }
        return parseZoned(eventTime)?.toInstant()?.toString() ?: nowBase.toInstant().toString()
    }

    /** Resolves a long-term `timeRef` to an absolute `eventTime`. */
    private fun resolveLongTermTimeRef(item: LongTermMemory, timeRef: TimeRef?, nowBase: ZonedDateTime): LongTermMemory {
        if (timeRef == null) return item
        val base = parseZoned(item.eventTime) ?: nowBase
        val iso = resolveTimeRef(timeRef, base)?.iso ?: return item
        return item.copy(eventTime = iso)
    }

    /** Base instant for a state update: its `timeRef` resolved against now, else now. */
    private fun resolveFieldBase(obj: JsonObject, nowBase: ZonedDateTime): ZonedDateTime {
        val ref = ResponseParser.parseTimeRef(obj) ?: return nowBase
        val resolved = resolveTimeRef(ref, nowBase) ?: return nowBase
        return resolved.iso?.let { parseZoned(it) } ?: nowBase
    }

    /**
     * Parses a `<MEM_UPDATE>...</MEM_UPDATE>` block out of a raw reply and applies
     * it (legacy `parseMemoryFromText`). Returns null when no tag is present or the
     * payload cannot be parsed.
     */
    fun parseMemoryFromText(character: Character, content: String, assistantMessage: ChatMessage?): Character? {
        val match = MEM_UPDATE_REGEX.find(content) ?: return null
        val update = ResponseParser.parseJsonLenient(match.groupValues[1]) as? JsonObject ?: return null
        return applyMemoryUpdate(character, update, assistantMessage)
    }

    /** Rewrites a prose reply into a structured memory update object (legacy `convertProseToJson`). */
    suspend fun convertProseToJson(
        character: Character,
        userMessage: ChatMessage?,
        proseText: String,
    ): JsonObject? {
        val volatileContext = buildVolatileContext(
            character = character,
            query = userMessage?.content.orEmpty(),
            memory = memory,
        )
        val systemContent = PROSE_SYSTEM + "\n\n【角色设定】\n" + buildRoleContext(character, false)
        val userContent = "【系统提供的本轮上下文，仅供理解，不是用户陈述】\n" + volatileContext +
            "\n\n用户消息: " + userMessage?.content.orEmpty() + " （消息id: " + (userMessage?.id.orEmpty()) + "）" +
            "\n\n角色回复（散文，整理到reply字段）：\n" + proseText
        val raw = complete(systemContent, userContent, sessionId = sessionIdFor(character))
        if (raw.isBlank()) return null
        return ResponseParser.parseJsonLenient(raw) as? JsonObject
    }

    /**
     * Background memory backfill for a prose turn (legacy `extractProseTurnMemory`):
     * rewrites the exchange into structured memory and keeps only facts sourced
     * from the user message or the assistant reply.
     */
    suspend fun extractProseTurnMemory(
        character: Character,
        userMessage: ChatMessage?,
        replyText: String,
        assistantMessage: ChatMessage,
    ): Character {
        val userId = userMessage?.id.orEmpty()
        if (userId.isEmpty() || !knownSources(character).containsKey(userId)) return character
        val converted = convertProseToJson(character, userMessage, replyText) ?: return character
        return applyMemoryUpdate(character, converted, assistantMessage, allowedSourceIds = setOf(userId, assistantMessage.id))
    }

    /**
     * True when the window is a legacy/anomalous backlog: far above anything a normal
     * run can produce, so it must be compressed by [migrateOversizedWindow] instead of
     * the normal whole-window fold. Checked when the app opens and before each fold.
     */
    fun hasOversizedWindow(character: Character): Boolean =
        character.instant.count { !it.isLoading } > AppLimits.Memory.INSTANT_MIGRATION_THRESHOLD

    /**
     * One-time catch-up for an oversized window: folds it in batches, oldest first,
     * dropping each folded batch, until it is back inside the normal window range.
     *
     * Runs to completion in a single call — the caller shows a blocking progress
     * overlay, so walking the whole backlog in one go is exactly what is wanted. The
     * status is `Failure` (character left untouched) when a batch could not be folded,
     * so the caller can report why and offer a retry.
     */
    suspend fun migrateOversizedWindow(
        character: Character,
        onProgress: (batchesDone: Int, remaining: Int) -> Unit = { _, _ -> },
    ): Pair<TaskStatus, Character> {
        var working = character
        var size = working.instant.count { !it.isLoading }
        if (size <= AppLimits.Memory.INSTANT_MIGRATION_THRESHOLD) return TaskStatus.Success to working
        var done = 0
        while (size > AppLimits.Memory.INSTANT) {
            val (status, updated) = foldWindow(working, FoldMode.Migration)
            if (status != TaskStatus.Success) return status to working
            val next = updated.instant.count { !it.isLoading }
            // No forward progress would spin forever; leave the rest for the next attempt.
            if (next >= size) return TaskStatus.Failure to working
            working = updated
            size = next
            done++
            onProgress(done, size)
        }
        return TaskStatus.Success to working
    }

    /** Legacy alias for [migrateOversizedWindow] (no-op unless the window is oversized). */
    suspend fun runInitialMemoryMigration(
        character: Character,
        onProgress: (batchesDone: Int, remaining: Int) -> Unit = { _, _ -> },
    ): Pair<TaskStatus, Character> = migrateOversizedWindow(character, onProgress)

    /** Whether an auto static-field fill may be attempted for [meta] right now. */
    fun canAttemptStaticFill(meta: StaticFillMeta?, nowMillis: Long = System.currentTimeMillis()): Boolean {
        meta ?: return true
        val retryAt = parseZoned(meta.retryAt)?.toInstant()?.toEpochMilli() ?: 0L
        if (retryAt > nowMillis) return false
        val cooldown = when {
            meta.failures > 0 -> minOf(30 * 60_000L, 5 * 60_000L * (1L shl (meta.failures - 1).coerceIn(0, 4)))
            else -> 24 * 3_600_000L
        }
        val attemptedAt = parseZoned(meta.attemptedAt)?.toInstant()?.toEpochMilli() ?: 0L
        return attemptedAt == 0L || nowMillis - attemptedAt >= cooldown
    }

    /** Silently fills the empty static-profile fields the model can infer. */
    suspend fun fillStaticFields(character: Character, missing: List<String>, nowMillis: Long = System.currentTimeMillis()): Character {
        if (missing.isEmpty()) return character
        val nowIso = Instant.ofEpochMilli(nowMillis).toString()
        val labels = missing.joinToString("、") { key ->
            val label = STATIC_PROFILE_FIELDS.firstOrNull { it.first == key }?.second ?: key
            "$label($key)"
        }
        val existing = STATIC_PROFILE_FIELDS
            .filter { it.first !in missing }
            .mapNotNull { (key, _) ->
                val value = trimText(com.deeptalking.domain.agent.prompts.staticProfileValue(character.staticProfile, key), 300)
                if (value.isEmpty()) null else "\"" + key + "\":\"" + value.replace("\"", "\\\"") + "\""
            }
        val samplesRule = if ("speakingStyle" in missing) SPEAKING_STYLE_SAMPLES_RULE else ""
        val addressRule = if ("userAddress" in missing) "“对用户的称呼”(userAddress) 只填一个简短称呼词（如“明明”“老公”），不带任何解释。" else ""
        val prompt = "请只补全下列【当前为空】的字段：" + labels + "。\n" +
            "要求：只补空字段，绝不修改或覆盖已有设定（已有设定是绝对权威，不得改写、润色或替换）；只根据已知信息合理补写，没有把握的字段直接省略；不要编造与已有设定冲突的内容。" +
            samplesRule + addressRule + "\n已有设定:\n{" + existing.joinToString(",") + "}\n只返回JSON对象，键为字段英文名，值为补写内容。"
        val raw = complete(FILL_SYSTEM, prompt, sessionId = sessionIdFor(character))
        val data = if (raw.isBlank()) null else ResponseParser.parseJsonLenient(raw) as? JsonObject
        var profile = character.staticProfile
        var filled = 0
        missing.forEach { key ->
            if (com.deeptalking.domain.agent.prompts.staticProfileValue(profile, key).isNotBlank()) return@forEach
            val value = data?.string(key)?.trim().orEmpty()
            if (value.isEmpty()) return@forEach
            val cleaned = if (key == "userAddress") {
                normalizeUserAddress(value)
            } else {
                MemoryToolSupport.cleanFieldValue(key, parseRelativeText(value, ZonedDateTime.now()))
            }
            if (cleaned.isEmpty()) return@forEach
            profile = withStaticField(profile, key, cleaned)
            filled++
        }
        val meta = StaticFillMeta(attemptedAt = nowIso, failures = if (filled > 0) 0 else (character.staticFillMeta?.failures ?: 0) + 1, retryAt = null)
        return character.copy(staticProfile = profile, staticFillMeta = meta)
    }

    /** Fills empty static fields for up to 3 eligible characters (legacy `autoFillStaticFields`). */
    suspend fun autoFillStaticFields(characters: List<Character>): List<Character> {
        val updated = characters.toMutableList()
        var attempts = 0
        characters.forEachIndexed { index, character ->
            if (attempts >= 3) return@forEachIndexed
            if (character.isGroup) {
                // Legacy `collectStaticFillJobs` also fills group members.
                if (canAttemptStaticFill(character.staticFillMeta) &&
                    character.members.any { missingStaticFields(it.staticProfile).isNotEmpty() }
                ) {
                    attempts++
                    updated[index] = fillGroupMembers(character)
                }
                return@forEachIndexed
            }
            val missing = missingStaticFields(character.staticProfile)
            if (missing.isEmpty() || !canAttemptStaticFill(character.staticFillMeta)) return@forEachIndexed
            attempts++
            updated[index] = fillStaticFields(character, missing)
        }
        return updated
    }

    private fun missingStaticFields(profile: com.deeptalking.core.model.StaticProfile): List<String> =
        STATIC_PROFILE_FIELDS.map { it.first }
            .filter { com.deeptalking.domain.agent.prompts.staticProfileValue(profile, it).isBlank() }

    /** Legacy `fillGroupMemberFields` background pass: fills up to 3 members' empty fields. */
    private suspend fun fillGroupMembers(group: Character): Character {
        var attempts = 0
        val members = group.members.map { member ->
            if (attempts >= 3) return@map member
            val missing = missingStaticFields(member.staticProfile)
            if (missing.isEmpty()) return@map member
            attempts++
            fillMemberFields(group, member, missing)
        }
        if (members == group.members) return group
        val nowIso = Instant.ofEpochMilli(System.currentTimeMillis()).toString()
        return group.copy(
            members = members,
            staticFillMeta = StaticFillMeta(attemptedAt = nowIso, failures = 0, retryAt = null),
        )
    }

    private suspend fun fillMemberFields(group: Character, member: GroupMember, missing: List<String>): GroupMember {
        if (missing.isEmpty()) return member
        val labels = missing.joinToString("、") { key ->
            STATIC_PROFILE_FIELDS.firstOrNull { it.first == key }?.second ?: key
        }
        val existing = STATIC_PROFILE_FIELDS
            .filter { it.first !in missing }
            .mapNotNull { (key, _) ->
                val value = com.deeptalking.domain.agent.prompts.staticProfileValue(member.staticProfile, key)
                if (value.isBlank()) null else "\"" + key + "\":\"" + value.replace("\"", "\\\"") + "\""
            }
        val others = group.members
            .filter { it.id != member.id }
            .joinToString("、") { it.name + "：" + it.staticProfile.personality.take(60) + "；" }
        val prompt = "所属群组：" + group.name + "（" + group.description.take(200) + "）\n" +
            "群内其他成员：" + others.ifEmpty { "（无）" } + "\n" +
            "请只补全该成员下列【当前为空】的字段：" + labels + "。\n" +
            "要求：只补空字段，绝不修改或覆盖已有设定（已有设定是绝对权威，不得改写、润色或替换）；只根据已知信息合理补写，没有把握的字段直接省略；不要编造与已有设定冲突的内容。" +
            (if ("speakingStyle" in missing) SPEAKING_STYLE_SAMPLES_RULE else "") +
            (if ("speakingStyle" in missing) "该成员的说话方式必须与群内其他成员显著不同（看台词就能分辨是谁）。" else "") +
            (if ("userAddress" in missing) "“对用户的称呼”(userAddress) 只填一个简短称呼词（如“明明”“老公”），不带任何解释。" else "") +
            "\n已有设定:\n{" + existing.joinToString(",") + "}\n只返回JSON对象，键为字段英文名，值为补写内容。"
        val raw = complete(FILL_SYSTEM, prompt, sessionId = sessionIdFor(group))
        val data = if (raw.isBlank()) null else ResponseParser.parseJsonLenient(raw) as? JsonObject
        var profile = member.staticProfile
        missing.forEach { key ->
            if (com.deeptalking.domain.agent.prompts.staticProfileValue(profile, key).isNotBlank()) return@forEach
            val value = data?.string(key)?.trim().orEmpty()
            if (value.isEmpty()) return@forEach
            val cleaned = if (key == "userAddress") {
                normalizeUserAddress(value)
            } else {
                MemoryToolSupport.cleanFieldValue(key, parseRelativeText(value, ZonedDateTime.now()))
            }
            if (cleaned.isEmpty()) return@forEach
            profile = withStaticField(profile, key, cleaned)
        }
        return member.copy(staticProfile = profile)
    }

    /** Legacy `FIELDS_MIGRATION_VERSION`. */
    private val fieldsMigrationVersion = "1.2.0"

    /**
     * One-time world-book migration (legacy `migrateWorldLoreForEntity`): splits
     * world-layer facts out of the character background / group description into
     * lorebook entries. Only reorganizes existing text; user entries are protected.
     */
    suspend fun migrateWorldLore(character: Character, trimSource: Boolean): Character {
        val isGroup = character.isGroup
        val sourceText = if (isGroup) character.description else character.staticProfile.background
        val trimmed = sourceText.trim()
        if (trimmed.isEmpty()) return character.copy(lorebookMigratedAt = Instant.now().toString())
        val existing = character.lorebook.map {
            LorebookProposal(name = it.name, content = it.content, keywords = it.keywords, alwaysActive = it.alwaysActive)
        }
        val prompt = "下面是一个" + (if (isGroup) "群组前提" else "角色的个人背景") + "文本，其中可能混有世界观类内容（时代/世界观、地点、组织、专有名词、历史、规则）。\n" +
            "请把其中的**世界层设定**整理成世界书条目；只整理原文已有的信息，禁止编造或扩写；角色/群组本人的性格、经历、关系不要抽成条目。\n" +
            "每条给出 name（条目名）、keywords（剧情里可能出现的称呼）、content（该条目本身的信息，不要理由与解释）、alwaysActive（世界前提/规则这类需要每轮生效的设 true，其余 false）。最多 6 条；没有世界层内容就返回空数组。\n" +
            (if (trimSource) "另外请给出精简后的原文：删掉已经抽成条目的世界观内容，只保留角色/群组本人相关的部分，其他内容一字不改；没有可精简的就原样返回。\n" else "") +
            "只返回 JSON：{\"entries\":[{\"name\":\"\",\"keywords\":[],\"content\":\"\",\"alwaysActive\":false}]" + (if (trimSource) ",\"trimmedSource\":\"\"" else "") + "}。\n\n" +
            "现有世界书条目：" + (if (existing.isEmpty()) "（空）" else json.encodeToString(existing)) +
            "\n\n" + (if (isGroup) "群组前提" else "个人背景") + "：\n" + trimText(trimmed, 2000)
        val raw = complete(LOREBOOK_SYSTEM, prompt, sessionId = sessionIdFor(character))
        val data = if (raw.isBlank()) null else ResponseParser.parseJsonLenient(raw) as? JsonObject
        var lorebook = character.lorebook
        val entries = (data?.get("entries") as? JsonArray)?.take(6).orEmpty()
        entries.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            val name = obj.string("name").trim()
            val content = obj.string("content").trim()
            if (name.isEmpty() || content.isEmpty()) return@forEach
            val proposal = LorebookProposal(
                name = name,
                content = content,
                keywords = stringList(obj["keywords"]),
                alwaysActive = (obj["alwaysActive"] as? JsonPrimitive)?.contentOrNull == "true",
            )
            val result = upsertLorebookEntry(lorebook, proposal)
            if (result.ok) lorebook = result.list
        }
        var updated = character.copy(lorebook = lorebook, lorebookMigratedAt = Instant.now().toString())
        if (trimSource) {
            val newSource = data?.string("trimmedSource")?.trim().orEmpty()
            val limit = if (isGroup) 1200 else 800
            if (newSource.isNotEmpty() && newSource.length < trimmed.length) {
                updated = if (isGroup) {
                    updated.copy(description = trimText(newSource, limit))
                } else {
                    updated.copy(staticProfile = updated.staticProfile.copy(background = trimText(newSource, limit)))
                }
            }
        }
        return updated
    }

    /**
     * One-time field-structure remap (legacy `aiRemapFieldsForEntity`): reorganizes
     * the dynamic-state fields and background into the current schema, dropping
     * duplicates. Group entities only keep their two shared dynamic fields.
     */
    suspend fun remapFields(character: Character): Character {
        val isGroup = character.isGroup
        val state = com.deeptalking.core.model.DynamicState(
            currentSituation = character.dynamicState.currentSituation,
            currentLocation = character.dynamicState.currentLocation,
            currentMood = if (isGroup) "" else character.dynamicState.currentMood,
            currentOccupation = if (isGroup) "" else character.dynamicState.currentOccupation,
            currentGoal = if (isGroup) "" else character.dynamicState.currentGoal,
            currentTone = if (isGroup) "" else character.dynamicState.currentTone,
        )
        val payload = buildJsonObject {
            put("name", character.name)
            put("entityType", if (isGroup) "group" else "character")
            put("dynamicState", buildJsonObject {
                put("currentSituation", state.currentSituation)
                put("currentLocation", state.currentLocation)
                put("currentMood", state.currentMood)
                put("currentOccupation", state.currentOccupation)
                put("currentGoal", state.currentGoal)
                put("currentTone", state.currentTone)
            })
            put("background", if (isGroup) character.description else character.staticProfile.background)
        }
        val prompt = "下面是一个角色/成员的当前字段文本。请把它整理成新版字段结构：\n" +
            "动态状态字段只能是：currentSituation、currentLocation、currentMood、currentOccupation、currentGoal、currentTone；静态设定只保留 background（仅单角色）。\n" +
            "要求：①按语义归入最贴切字段，消除重复；②时间写绝对日期；③只整理已有信息，不编造；④写成自然完整的陈述句。\n" +
            "只返回JSON对象：{\"dynamicState\":{...},\"background\":\"...\"}。\n现有内容（JSON）：\n" + json.encodeToString(payload)
        val raw = complete(FILL_SYSTEM, prompt, sessionId = sessionIdFor(character))
        val data = if (raw.isBlank()) null else ResponseParser.parseJsonLenient(raw) as? JsonObject
        var dynamic = character.dynamicState
        val ds = data?.get("dynamicState") as? JsonObject
        if (ds != null) {
            DYNAMIC_STATE_FIELDS.forEach { (key, _) ->
                if (isGroup && key !in GROUP_SHARED_DYNAMIC_FIELDS) return@forEach
                val value = (ds[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (value.isNotEmpty()) dynamic = withDynamicField(dynamic, key, trimText(value, 700))
            }
        }
        var updated = character.copy(dynamicState = dynamic, fieldsMigrationVersion = fieldsMigrationVersion)
        if (!isGroup) {
            val background = data?.string("background")?.trim().orEmpty()
            if (background.isNotEmpty()) {
                updated = updated.copy(staticProfile = updated.staticProfile.copy(background = trimText(background, 800)))
            }
        } else {
            val description = data?.string("description")?.trim().orEmpty()
            val cleaned = MemoryToolSupport.cleanFieldValue("description", description)
            if (cleaned.isNotEmpty()) updated = updated.copy(description = trimText(cleaned, 1200))
        }
        return updated
    }

    suspend fun critiqueStyle(character: Character, reply: String): String {
        onStatus("正在校正文风…")
        val original = reply
        if (original.trim().isEmpty()) return original
        val violations = detectStyleViolations(
            original,
            character,
            recentReplyTexts(character, STYLE_GUARD.lookbackReplies),
        )
        if (violations.isEmpty()) return original
        return critiqueWithViolations(character, original, violations)
    }

    private suspend fun critiqueWithViolations(
        character: Character,
        original: String,
        violations: List<String>,
    ): String {
        val rules = violations.joinToString("\n") { "- " + (STYLE_VIOLATION_LABELS[it] ?: it) }
        val prompt = "下面是一段角色扮演回复，以及它违反的文风规则清单。请修订文风。\n" +
            "硬性要求：①只改文风，绝不改动情节、事实、对话含义与人物关系；②保持大体长度与段落数；③不要新增情节要素、不要加解释或旁白；④不得复述规则本身。\n" +
            "违反的规则：\n" + rules + "\n\n" +
            "只返回 JSON：{\"reply\":\"修订后的正文\"}，换行写 \\n，双引号写 \\\"。\n\n原文：\n" + original
        val raw = complete(CRITIQUE_SYSTEM, prompt, sessionId = sessionIdFor(character))
        if (raw.isBlank()) return original
        val data = ResponseParser.parseJsonLenient(raw) as? JsonObject ?: return original
        val revised = ResponseParser.unescapeLiteralNewlines(data.string("reply").orEmpty()).trim()
        if (revised.isEmpty() || revised == original) return original
        if (revised.length > STYLE_GUARD.critiqueMaxChars) return original
        if (revised.length < original.length * STYLE_GUARD.critiqueMinRatio) return original
        if (revised.length > original.length * STYLE_GUARD.critiqueMaxRatio) return original
        return revised
    }

    suspend fun repairQuickReplies(
        character: Character,
        reply: String,
        userText: String,
        current: List<String>,
    ): List<String> {
        val currentIssues = detectQuickReplyIssues(current, character, reply)
        if (current.size >= 2 && currentIssues.isEmpty()) return current
        val fallback = listOf("嗯", "继续")
        val prompt = buildQuickReplyAsUserPrompt(character, reply)
        val recentUserLines = recentUserTexts(character, userText)
        val userContent = if (recentUserLines.size > 1) {
            "【用户最近说过的话，仅用于参考语气，不要照抄】\n" +
                recentUserLines.dropLast(1).joinToString("\n") + "\n\n" + prompt.second
        } else {
            prompt.second
        }
        val raw = complete(prompt.first, userContent, sessionId = sessionIdFor(character))
        if (raw.isBlank()) return fallback
        val list = ResponseParser.parseQuickReplyList(ResponseParser.parseJsonLenient(raw))
        if (list.size < 2) return fallback
        if (detectQuickReplyIssues(list, character, reply).isNotEmpty()) return fallback
        return list
    }

    fun scheduleTurn(
        character: Character,
        reply: String,
        userText: String,
        currentQuickReplies: List<String>,
        messages: List<ChatMessage>,
        critiqueEnabled: Boolean,
        quickReplyRepairEnabled: Boolean,
        onCharacterUpdated: (Character) -> Unit,
        onQuickRepliesRepaired: (List<String>) -> Unit = {},
        styleViolations: List<String> = emptyList(),
        proseFallback: Boolean = false,
    ): Job = queue.enqueue {
        var working = character

        if (styleViolations.isNotEmpty()) {
            working = updateReplyMessage(working, reply) { it.copy(styleViolations = styleViolations) }
        }
        val issues = detectQuickReplyIssues(currentQuickReplies, working, reply)
        if (quickReplyRepairEnabled && (currentQuickReplies.size < 2 || issues.isNotEmpty())) {
            val repaired = repairQuickReplies(working, reply, userText, currentQuickReplies)
            working = updateReplyMessage(working, reply) { it.copy(quickReplyIssues = issues) }
            if (repaired.size >= 2 && repaired != currentQuickReplies) onQuickRepliesRepaired(repaired)
        }

        var counters = countersOf(working)
        val nowMillis = System.currentTimeMillis()
        val lastUser = working.instant.lastOrNull { it.role == Role.User && !it.isLoading }
        /** Short-term entries the extraction pass produced in this turn (world-book source). */
        var newlyExtracted: List<ShortTermMemory> = emptyList()

        // Extraction: the *only* producer of short-term memory (the single whole-window
        // pass at the high watermark). Nothing else may append to the window in
        // between, which is what keeps the injected short-term block byte-stable while
        // the history grows (cache hit) and lets its changes ride along with the fold.
        // Oversized window (legacy/anomalous backlog): walk it in batches, oldest first,
        // until it is back inside the normal range. The whole-window fold below cannot
        // send hundreds of messages at once, so the two paths are kept strictly apart.
        if (hasOversizedWindow(working) && canRunMemoryTask(counters, MemoryTaskKind.Extraction, nowMillis)) {
            val hadIds = working.shortTerm.mapTo(HashSet()) { it.id }
            val (status, migrated) = migrateOversizedWindow(working)
            working = migrated
            if (status == TaskStatus.Success) {
                newlyExtracted = working.shortTerm.filterNot { it.id in hadIds }
            }
            counters = when (status) {
                TaskStatus.Success -> resetMemoryRetry(counters, MemoryTaskKind.Extraction)
                TaskStatus.Failure -> scheduleMemoryRetry(counters, MemoryTaskKind.Extraction, nowMillis)
                TaskStatus.Stale -> counters
            }
        } else if (working.instant.size >= AppLimits.Memory.INSTANT &&
            canRunMemoryTask(counters, MemoryTaskKind.Extraction, nowMillis)
        ) {
            val hadIds = working.shortTerm.mapTo(HashSet()) { it.id }
            val (extractStatus, extracted) = extractMemoryStatus(working, messages)
            when (extractStatus) {
                TaskStatus.Success -> {
                    working = extracted
                    // The entries this fold produced: the world-book pass below reads them
                    // while they are still in the window (the analysis drops them later).
                    newlyExtracted = working.shortTerm.filterNot { it.id in hadIds }
                    counters = resetMemoryRetry(counters, MemoryTaskKind.Extraction)
                }
                TaskStatus.Failure -> counters = scheduleMemoryRetry(counters, MemoryTaskKind.Extraction, nowMillis)
                TaskStatus.Stale -> Unit
            }
        }

        // World-book pass. It used to hang off the long→term analysis batch; it now reads
        // the batch the extraction just produced, **before** the analysis consumes (drops)
        // it, plus whatever an earlier failure left in memory. A transient failure therefore
        // delays the entries instead of losing them, and the pass is no longer thrown away
        // when the user happens to type while it runs.
        if (newlyExtracted.isNotEmpty() || (lorebookRetries?.batch(working.id)?.isNotEmpty() == true)) {
            val held = lorebookRetries?.batch(working.id).orEmpty()
            val lorebookBatch = (newlyExtracted + held).distinctBy { it.id }
            if (lorebookRetries?.awaitingUser(working.id) != true &&
                canRunMemoryTask(counters, MemoryTaskKind.Lorebook, nowMillis)
            ) {
                val (lorebookStatus, afterLorebook) = consolidateLorebook(working, lorebookBatch)
                when (lorebookStatus) {
                    TaskStatus.Success -> {
                        working = afterLorebook
                        lorebookRetries?.clear(working.id)
                        counters = resetMemoryRetry(counters, MemoryTaskKind.Lorebook)
                    }
                    TaskStatus.Failure -> {
                        counters = scheduleMemoryRetry(counters, MemoryTaskKind.Lorebook, nowMillis)
                        val state = lorebookRetries?.recordFailure(working.id, lorebookBatch)
                        if (state?.awaitingUser == true) onLorebookRetryNeeded(working.id)
                    }
                    TaskStatus.Stale -> Unit
                }
            }
        }

        // Prose reply fallback (legacy `convertProseToJson` + `extractProseTurnMemory`):
        // a non-structured reply still gets its long-term facts / dynamic state
        // extracted in the background. Short-term writes were removed here on purpose —
        // entries would land mid-window and break the append-only property.
        if (proseFallback && lastUser != null) {
            val assistantMsg = messages.lastOrNull { it.role == Role.Assistant && !it.isLoading }
            if (assistantMsg != null) {
                val proseUpdated = extractProseTurnMemory(working, lastUser, reply, assistantMsg)
                if (proseUpdated != working) working = proseUpdated
            }
        }

        // Consolidate the consumable part of the short-term window into long-term memories
        // (legacy analyzeShortToLongTerm). The batch is what sits above the low-water mark.
        // The world book ran on the same batch *before* this point (see above), so an entry
        // is world-booked before the analysis drops it.
        val consumable = (working.shortTerm.size - AppLimits.Memory.SHORT_TERM_TRIM_FLOOR).coerceAtLeast(0)
        val analyzeSpan = AppLimits.Memory.SHORT_TERM - AppLimits.Memory.SHORT_TERM_TRIM_FLOOR
        if (consumable >= analyzeSpan && canRunMemoryTask(counters, MemoryTaskKind.Analysis, nowMillis)) {
            val outcome = analyzeShortToLongTermStatus(working)
            // Carry the failure diagnostic (or its clearing) into the persisted counters.
            counters = counters.copy(analysisLastError = outcome.character.counters.analysisLastError)
            when (outcome.status) {
                TaskStatus.Success -> {
                    working = outcome.character
                    counters = resetMemoryRetry(counters, MemoryTaskKind.Analysis)
                }
                TaskStatus.Failure -> counters = scheduleMemoryRetry(counters, MemoryTaskKind.Analysis, nowMillis)
                TaskStatus.Stale -> Unit
            }
        }
        working = working.withCounters(counters)

        // Scene summaries + adaptive importance (the world book ran earlier, above).
        val maintained = runMemoryMaintenance(working)
        if (maintained != working) working = maintained

        if (working != character) onCharacterUpdated(working)
        onStatus("")
    }

    private fun updateReplyMessage(
        character: Character,
        reply: String,
        transform: (ChatMessage) -> ChatMessage,
    ): Character {
        val index = character.instant.indexOfLast { it.role == Role.Assistant && !it.isLoading }
        if (index < 0) return character
        if (character.instant[index].content != reply) return character
        val updated = character.instant.toMutableList()
        updated[index] = transform(updated[index])
        return character.copy(instant = updated)
    }

    private fun recentUserTexts(character: Character, userText: String): List<String> {
        val fromInstant = character.instant
            .filter { it.role == Role.User && !it.isLoading && it.content.isNotBlank() }
            .takeLast(3)
            .map { trimText(it.content, 80) }
            .filter { it.isNotEmpty() }
        if (fromInstant.isNotEmpty()) return fromInstant
        val trimmed = trimText(userText, 80)
        return if (trimmed.isNotEmpty()) listOf(trimmed) else emptyList()
    }

    private fun buildQuickReplyAsUserPrompt(character: Character, replyText: String): Pair<String, String> {
        val address = com.deeptalking.domain.agent.prompts.normalizeUserAddress(character.staticProfile.userAddress)
        val charName = character.name.ifBlank { "对方" }
        val system = "你就是这位用户本人，正在手机上和" + charName + "聊天。只输出用户此刻最可能打出的两句话，不要扮演" + charName + "，不要写旁白或动作，不要解释。"
        val user = charName + "刚对你说：\n" + trimText(replyText, 800) + "\n\n" +
            "请写出你（用户" + (if (address.isNotEmpty()) "，对方平时叫你“" + address + "”" else "") + "）此刻最可能发给他的两句话：\n" +
            "①每句都是用户可以原样发送的消息，是\"我\"（用户自己）的立场、感受、提问或要求；\n" +
            "②不得是${charName}会说的话，不得是把${charName}刚说的话换个人称复述一遍；\n" +
            "③每条不超过 ${QUICK_REPLY_GUARD.repairMaxChars} 字，不用括号动作、不用 Markdown。\n" +
            "只返回 JSON：{\"quickReplies\":[\"句子一\",\"句子二\"]}"
        return system to user
    }

    private suspend fun complete(
        system: String,
        user: String,
        sessionId: String? = null,
        taskType: String = "auxiliary",
    ): String = completeDetailed(system, user, sessionId, taskType).text

    /** Text of one auxiliary call, plus the failure reason when it produced no text. */
    private data class Completion(val text: String, val error: String = "")

    /**
     * One auxiliary call with the shared transient-failure policy: network errors,
     * timeouts and HTTP 429/5xx are retried up to [ApiRetry.MAX_API_RETRIES] times with the
     * legacy backoff, everything else fails immediately. The reason is handed back (and
     * recorded in the request log) instead of being swallowed — every background task used
     * to report the same "网络或接口异常" no matter what actually went wrong.
     */
    private suspend fun completeDetailed(
        system: String,
        user: String,
        sessionId: String? = null,
        taskType: String = "auxiliary",
    ): Completion {
        val request = LlmRequest(
            model = model,
            instructions = system,
            input = listOf(ChatMessage(role = Role.User, content = user)),
            temperature = normalizeTemperature(config.temperature),
            maxOutputTokens = AppLimits.Api.AUXILIARY_MAX_OUTPUT_TOKENS,
            stream = false,
            reasoningEffort = "none",
            apiPlatform = apiPlatform,
            sessionId = sessionId ?: "deeptalking-general",
            auxiliary = true,
        )
        var attempt = 0
        while (true) {
            try {
                val result = llm.complete(request)
                result.usage?.let { onAuxiliaryUsage(taskType, it) }
                return Completion(result.text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                attempt++
                val reason = t.message?.replace(Regex("\\s+"), " ")?.trim()?.take(160)
                    ?.takeIf { it.isNotEmpty() }
                    ?: t::class.simpleName
                    ?: "call failed"
                if (attempt > ApiRetry.MAX_API_RETRIES || !isTransientApiError(t)) {
                    onAuxiliaryFailure(taskType, reason)
                    return Completion("", reason)
                }
                delay(ApiRetry.delayFor(attempt))
            }
        }
    }

    private fun parseLongTerm(obj: JsonObject): LongTermMemory {
        val category = when (obj.string("category").lowercase()) {
            "userprofile" -> MemoryCategory.UserProfile
            "relationship" -> MemoryCategory.Relationship
            "promises" -> MemoryCategory.Promises
            "habits" -> MemoryCategory.Habits
            else -> MemoryCategory.Events
        }
        val subject = when (obj.string("subject").lowercase()) {
            "user" -> MemorySubject.User
            "relationship" -> MemorySubject.Relationship
            "world" -> MemorySubject.World
            "character" -> MemorySubject.Character
            else -> MemorySubject.Legacy
        }
        val status = when (obj.string("status").lowercase()) {
            "resolved" -> PromiseStatus.Resolved
            "cancelled" -> PromiseStatus.Cancelled
            else -> PromiseStatus.Active
        }
        return LongTermMemory(
            category = category,
            subject = subject,
            key = obj.string("key"),
            value = obj.string("value"),
            tags = stringList(obj["tags"]),
            importance = (obj["importance"] as? JsonPrimitive)?.intOrNull ?: 0,
            sourceMessageIds = stringList(obj["sourceMessageIds"]),
            evidence = obj.string("evidence"),
            eventTime = obj.string("eventTime").takeIf { it.isNotBlank() },
            dueAt = obj.string("dueAt").takeIf { it.isNotBlank() },
            promisor = obj.string("promisor").takeIf { it.isNotBlank() },
            promisee = obj.string("promisee").takeIf { it.isNotBlank() },
            status = status,
            participants = normalizeParticipants(stringList(obj["participants"])),
            location = obj.string("location").trim(),
            arcOf = obj.string("arcOf").takeIf { it.isNotBlank() },
            arcStage = obj.string("arcStage").takeIf { it.isNotBlank() },
        )
    }

    private fun normalizeParticipants(values: List<String>): List<String> =
        values.map { it.trim() }.filter { it.isNotEmpty() }.take(8).sorted()

    private fun stringList(element: JsonElement?): List<String> =
        (element as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    /**
     * A boolean the model wrote, or `null` when the field is absent (or JSON-null). Used for
     * tri-state fields such as `alwaysActive`, where "not answered" must not be read as
     * "false": switching a stored 常驻 entry off on an omitted field was a silent downgrade.
     */
    private fun optionalBoolean(obj: JsonObject, key: String): Boolean? =
        (obj[key] as? JsonPrimitive)?.contentOrNull?.let { it.equals("true", ignoreCase = true) }

    private fun eventIdentity(eventTime: String?, zone: ZoneId = ZoneId.systemDefault()): String {
        val zoned = parseZoned(eventTime, zone) ?: return ""
        val day = logicalDay(zoned)
        return "${day.year}年${day.monthValue}月${day.dayOfMonth}日[${getTimeSlot(zoned.toLocalTime())}]"
    }

    private fun normalizeTimestamp(value: String?, fallback: String): String {
        if (value.isNullOrEmpty()) return fallback
        val zoned = parseZoned(value) ?: return fallback
        return zoned.toInstant().toString()
    }

    private fun newId(prefix: String): String =
        prefix + "_" + Instant.now().toEpochMilli() + "_" + Random.nextInt(100000, 999999)

    private companion object {
        val MEM_UPDATE_REGEX = Regex("<\\s*MEM_UPDATE\\s*>([\\s\\S]*?)</\\s*MEM_UPDATE\\s*>", RegexOption.IGNORE_CASE)

        const val PROSE_SYSTEM =
            "你是「对话记录整理助手」。下面是一次角色扮演交互（用户消息 + 角色回复），请整理成与主对话一致的标准 JSON，包含全部字段：" +
                "{\"reply\":\"角色回复原文（一字不改）\",\"quickReplies\":[\"用户下一句1\",\"用户下一句2\"],\"shortTerm\":[{\"content\":\"从交互提取的本轮事件摘要，写明谁做了什么\",\"sourceMessageIds\":[\"<用户消息id>\"]}],\"longTerm\":[{\"category\":\"userProfile|relationship|events|promises|habits\",\"subject\":\"user|relationship|world\",\"key\":\"稳定标识\",\"value\":\"用户的具体事实（写明主体）\",\"tags\":[],\"importance\":1-10,\"sourceMessageIds\":[\"<用户消息id>\"],\"evidence\":\"用户原话\",\"promisor\":\"可选，约定的承诺方 user|character\",\"promisee\":\"可选，约定的受约方 user|character\"}],\"dynamicState\":{\"currentSituation\":{\"value\":\"当前处境\",\"sourceMessageIds\":[\"current_response\"],\"evidence\":\"回复中引原句\"},\"currentMood\":{\"value\":\"当前情绪\",\"sourceMessageIds\":[\"current_response\"],\"evidence\":\"概括回复原句\"},\"currentGoal\":{\"value\":\"当前目标\",\"sourceMessageIds\":[\"current_response\"],\"evidence\":\"回复中引原句\"}},\"staticFields\":{},\"memberDynamicState\":[],\"promiseUpdates\":[]}" +
                "。规则：reply必须一字不改保留角色回复原文；quickReplies必须恰好两条用户可直接发送给角色的短句；所有记忆只能从这次交互中明确提取，不得凭空编造；dynamicState来源用current_response、evidence引用回复原句；shortTerm和longTerm的sourceMessageIds引用用户消息id；用户未明确陈述或表现的内容不要写入；无合格记忆返回空数组。记忆字段（shortTerm的content、longTerm的key与value）必须写明主体：涉及用户写“用户”，涉及角色写角色名，禁止“我/你/TA”这类指代不清的代词；时间一律写绝对日期（YYYY-MM-DD 或 YYYY-MM-DD 时段），禁止“明天/明晚/上周/上个月/三天后”这类相对时间词，用户用相对时间说法时改用timeRef（anchor/offsetDays/weekday/slot/explicit）。dynamicState与staticFields的值以段落式的陈述句书写（自然完整陈述句，简短段落），禁止括号注释或理由。只返回JSON对象，不要其他任何文字。"

        const val SCENE_SYSTEM = "你是对话记录整理助手。只输出场景摘要正文，不要任何额外文字。"
        const val SCENE_PROMPT =
            "把下面这段已经告一段落的情节压缩成 1-2 段、不超过 300 字的「场景记忆」，供之后长期参考。\n" +
                "只保留会影响后续剧情的要点：谁做了什么、学到或决定了什么；地点、物品、伤势、关系的变化；新的发现或线索；仍未解决的目标、承诺、威胁或期限。\n" +
                "要求：①写明主体（涉及用户写“用户”，涉及角色写角色名），禁止“我/你/TA”这类指代不清的代词；②时间写绝对日期（YYYY-MM-DD 或 YYYY-MM-DD 时段），禁止“今天/昨天/刚才”这类相对时间词；③不要引用对白原句、不要加标题或 markdown、不要文学化描写；④不得编造原文没有的事实；⑤直接输出摘要正文，不要任何前后缀或解释。\n\n对话内容：\n"

        const val LOREBOOK_SYSTEM = "你是世界书整理助手。只返回 JSON，不要任何额外文字。"
        const val LOREBOOK_PROMPT_HEAD =
            "下面是一段角色扮演对话里已经发生的事件摘要，以及现有的世界书条目清单。\n" +
                "请找出其中**已经出现、值得日后复用**的世界层设定（时代/世界观、地点、组织、专有名词、历史、规则、背景事实），整理成世界书条目。\n" +
                "要求：①只写已经出现或已被明确说出的内容，禁止推测、扩写或发明新设定；②**同一件事物只能有一条**：对照现有条目的名称、关键词与内容，凡与已有条目讲的是同一件事（名称相近、关键词相同、内容重叠），必须复用它的名字、用你这次总结的内容**整条替换**它，绝不新建近似条目；同理，本批不同摘要里指向同一件事的也只输出一条；③只沉淀会反复复用的世界层设定，一次性的小事、可从上下文直接看出的细节不要单独立条；④每条 content 只写该条目本身的信息，不写理由、解释或出处，**一条只讲一件事**；**长度目标 300 字以内**、**硬上限 400 字**（超过 400 字的条目会被退回重写，宁短勿长）；⑤更新一条已有条目时，把它下面给出的**完整原有内容**与你掌握的新事实**重新总结成一条**：仍然有效的信息必须原样保留，重复的、过时的、与本条主题无关的内容删掉，允许比原来短——**只写增量会丢掉原有信息**；⑥清单里标注「内容（略）」的条目本轮不要更新（你看不到它的完整内容）；⑦keywords 写剧情里可能出现的称呼，不超过 8 个；若这条是世界前提/规则这类需要每轮生效的设定，把 alwaysActive 设为 true，否则一律 false；⑧最多 3 条；没有值得沉淀的就返回空数组。\n" +
                "只返回 JSON：{\"entries\":[{\"name\":\"条目名\",\"keywords\":[\"触发词\"],\"content\":\"设定内容\",\"alwaysActive\":false,\"sourceShortTermIds\":[\"上面的摘要ID\"]}]}。sourceShortTermIds 必须是上面出现过的摘要ID，至少要有一个；没有可引用ID的条目不要返回。\n\n" +
                "现有条目清单：\n"
        const val LOREBOOK_PROMPT_TAIL = "\n\n事件摘要"

        /**
         * Re-ask round for entries that came back over the hard cap: the entry is sent back
         * with its own text and told to be shorter, instead of being dropped (or cut).
         */
        const val LOREBOOK_FIX_PROMPT =
            "下面这些世界书条目**超过了 400 字的硬上限**被退回，请把每一条**重新写一遍**：\n" +
                "要求：①压缩到 **300 字以内**，仍然有效的关键信息（专有名词、人名、地点、日期、已确定的事实）必须保留，只删重复、修饰和可从上下文推出的细节；②**一条只讲一件事**，名称保持不变；③sourceShortTermIds 原样照抄，不要新增或改动；④不得编造原文没有的内容。\n" +
                "只返回 JSON：{\"entries\":[{\"name\":\"条目名\",\"keywords\":[\"触发词\"],\"content\":\"压缩后的设定内容\",\"alwaysActive\":false,\"sourceShortTermIds\":[\"摘要ID\"]}]}\n\n" +
                "被退回的条目：\n"

        /** Existing entries whose full content is shown to the consolidation prompt. */
        const val LOREBOOK_EXISTING_FULL_ENTRIES = 12

        /** Total character budget for those full contents (the rest are name-only). */
        const val LOREBOOK_EXISTING_FULL_CHARS = 6_000

        /** Max entries one rewrite of an oversized entry may produce. */
        const val LOREBOOK_REBUILD_MAX_ENTRIES = 3
        const val LOREBOOK_REBUILD_SYSTEM = "你是世界书整理助手。只返回 JSON，不要任何额外文字。"
        const val LOREBOOK_REBUILD_PROMPT =
            "下面是一条已经积累得过长、把好几件不同的事混在一起的世界书条目。请把它**重新整理**成 1~3 条各自只讲一件事的条目。\n" +
                "要求：①只使用原文里已有的信息，**禁止新增、推测或扩写任何设定**；②按主题拆分：读起来是不同话题的内容必须拆成不同条目，同一话题的合并成一条；③删掉重复的说法、已经过时或彼此矛盾的旧信息和与条目主题无关的内容；④每条的 content 都要写成完整、精炼的句子，**目标 300 字以内、硬性不超过 400 字**（超了这次重写会被丢弃、原条目原样保留），只写该条目本身的信息，不写理由或出处；⑤沿用原文的专有名词写法，条目名简洁（不超过 20 字）；⑥keywords 写剧情里可能出现的称呼，不超过 8 个，不要写泛词；⑦只有确实是世界前提/规则这类需要每轮生效的才把 alwaysActive 设为 true，其余一律 false；⑧原文里**仍然有效的信息要尽量保留**，不要因为压缩而丢事实。\n" +
                "只返回 JSON：{\"entries\":[{\"name\":\"条目名\",\"keywords\":[\"触发词\"],\"content\":\"设定内容\",\"alwaysActive\":false}]}"

        const val FILL_SYSTEM = "你是角色卡补全助手。只返回JSON。"
        const val SPEAKING_STYLE_SAMPLES_RULE =
            "speakingStyle 必须写成“整体调性描述；示例：<台词1> / <台词2> / <台词3>”：三条示例台词必须是该角色真的会说的口语短句，" +
                "要体现口头禅、句尾助词、标点习惯与对用户的称呼，三条之间差异明显（能看出是同一个人、但场景不同）；" +
                "**禁止换行，三条之间只能用 \" / \" 分隔**，整个字段不超过 200 字。"

        const val EXTRACTION_SYSTEM = "你是一个信息提取助手。只返回JSON数组，不要其他文字。"
        const val ANALYSIS_SYSTEM = "你是一个记忆分析助手。只返回JSON，不要其他文字。"
        const val CRITIQUE_SYSTEM = "你是文风校对助手。只返回JSON。"

        const val EXTRACTION_COUNT_LIMITED = "返回JSON数组，最多十五个元素。"
        const val EXTRACTION_COUNT_UNLIMITED = "不限事件数量，尽可能完整覆盖本批全部消息。"

        const val EXTRACTION_PROMPT_HEAD =
            "把下面这段即时对话整体压缩为事件流程摘要，覆盖整段对话、不要遗漏内容。事件按“日期+时段”划分：时段只有深夜、凌晨、清晨、早晨、上午、中午、下午、傍晚、晚上、夜里十种，且一天从02:00起算（00:00-02:00算前一天的深夜）；同一天同一时段的多个事情视为同一事件，按时间线集中叙述（如“2026-08-01 下午在家先…晚上…”）。同一时段也可能有不同事件，按事实或持续话题区分，不得把无关事件强行合并；同一事件内用时间线连接各阶段，保留仍然有效的关键细节、结果与未决事项。**只保留会影响后续剧情的内容**：谁做了什么、学到或决定了什么，地点/物品/伤势/关系的变化，新的发现或线索，以及仍未解决的目标、承诺、威胁与期限；一次性的寒暄、当下的情绪起伏、闲聊过程一律不记。**每条content不超过120字**，用平实的叙述句写成，禁止文学化描写、比喻、形容词铺陈与对白原文。eventTime写该事件的真实ISO时间，participants为参与人列表，location写地点（没有明确地点写“未说明”）。若本次某事件是“已有短期事件”中某事件的延续或更新，不要另起一条：把它并进对应事件（沿用同一事件的时间与地点），并在输出里直接给出合并后的完整摘要，旧摘要中**仍然有效**的信息（未过期的约定、未解决的事、仍然成立的关系与状态）必须原样保留，只丢弃已经过时或已被推翻的部分。content按时间和因果顺序书写，不要逐字摘录。content必须写明是谁做的：涉及用户写“用户”，涉及角色写角色名（群组写具体成员名），双方共同的事写清各自做了什么，禁止使用“我、你、TA、他、她”这类指代不清的代词。content中禁止使用“今天、昨天、明天、今晚、刚刚、刚才、现在、最近、这几天”等相对时间词，一律使用具体日期（如“8月1日下午”）或事件本身描述。不要输出任何消息ID。"

        const val EXTRACTION_PROMPT_TAIL =
            "返回JSON数组：[{\"content\":\"一段完整的事件流程摘要\",\"eventTime\":\"ISO时间\",\"participants\":[\"用户\",\"角色名\"],\"location\":\"地点或未说明\"}]。\n已有短期事件（不要重复，只在需要延续或更新时合并进去）:\n"

        const val ANALYSIS_PROMPT_HEAD =
            "分析以下带短期ID、来源消息ID、userEvidence及时间/人物/地点三要素的事件流程摘要，提取未来仍有价值的稳定事实。输入中每条条目带 analyzed 标记：**只为 analyzed=false 的条目产出长期记忆**；analyzed=true 的是前一轮已整理过的上下文，只用于保持情节连贯（可据此合并或修正），不得为它们重复产出条目，也不要在 analyzedShortTermIds 中返回它们。**价值判据**：只有能跨轮复用、会影响后续对话或关系、或用户明确表达过的信息才记（长期偏好、重要人物与关系、承诺与约定、反复出现的习惯、持续的情节线）；一次性的寒暄客套、当下的情绪起伏、可以随口重说的闲聊、纯场景描写一律不记。**证据不足宁可不记**，不要为了产出而脑补或推演。必须检查全部 analyzed=false 的条目，并在analyzedShortTermIds中原样返回它们的短期ID；不得遗漏、增加或重复。**每条 value 不超过200字，arc 叙事摘要不超过300字**，写成平实叙述，不堆砌形容词。合并重复或冲突条目；事件、承诺保留必要日期；普通寒暄不要进入长期记忆。每条长期记忆必须在sourceShortTermIds中列出它实际使用的短期条目id；每个列出的短期条目都必须至少贡献一个sourceMessageIds中的消息ID，否则不要列出该短期ID。sourceMessageIds只选择直接支持该事实的消息，不要复制短期条目的全部来源；userProfile和habits只能选择用户消息。evidence只能逐字引用被选择消息对应的userEvidence.text，不能引用摘要或改写。没有可引用的userEvidence时不要输出该条。events按“日期+时段+事实主题”区分：同一天同一时段（深夜/凌晨/清晨/早晨/上午/中午/下午/傍晚/晚上/夜里，一天从02:00起算）的短期事件合并为一条长期事件，同一时段的独立事实不得互相覆盖，key须标明稳定事实主题；合并时eventTime取最早的ISO时间，participants取并集，location取最新（无明确地点写“未说明”）。剧情弧线：若若干短期事件属于同一持续情节或话题线（同一人物线、同一持续事件、同一反复出现的话题），除按时间合并外，还应在其中一条事件条目上标注arcOf（该情节的持续话题或人物线名称，稳定可复用）与arcStage（只能是起始/发展/转折/现状之一，按情节推进阶段标注），arcOf命名一旦确定就保持稳定——同一情节线不得每轮改名或另起新名，续写时沿用已有名称；并将value整合成按时间顺序、带情绪起伏的叙事摘要；同一arcOf只允许一条带弧线标注的条目，其余同线条目按普通事件输出。无论是否提取出长期记忆，成功完成分析都必须返回status:\"ok\"；没有长期价值时仍需返回完整analyzedShortTermIds和空longTerm。\n分类规则：userProfile/habits仅限用户事实，subject=user；relationship仅限双方关系，subject=relationship；events只记录用户陈述或共同事件；promises只记录用户明确承诺或双方明确约定。promises在此处只能新建为active；完成或取消由主对话的promiseUpdates按承诺ID处理。存在明确期限才填写dueAt。\n条目:\n"

        const val ANALYSIS_PROMPT_TAIL =
            "\n\n返回JSON格式:\n{\"status\":\"ok\",\"analyzedShortTermIds\":[\"全部 analyzed=false 的 short_id\"],\"longTerm\": [{\"category\": \"...\", \"subject\": \"user|relationship|world\", \"key\": \"稳定且可复用的标识\", \"value\": \"...\", \"tags\": [...], \"importance\": 1-10, \"sourceShortTermIds\":[\"short_id\"], \"sourceMessageIds\": [\"msg_id\"], \"evidence\": \"被选择用户消息中的逐字原话\", \"eventTime\": \"ISO时间\", \"participants\":[\"参与者\"],\"location\":\"地点或未说明\",\"status\": \"active\", \"dueAt\": \"ISO时间\", \"arcOf\": \"可选，持续情节线名称\", \"arcStage\": \"可选，起始/发展/转折/现状\"}]}\ncategory可选: userProfile, relationship, events, promises, habits。value同样必须写明主体：涉及用户写“用户”，涉及角色写角色名（群组写具体成员名），禁止“我/你/TA”这类指代不清的代词。key与value中的时间一律写绝对日期（YYYY-MM-DD 或 YYYY-MM-DD 时段），禁止“明天/明晚/上周/上个月/三天后”这类相对时间词。这里只提取用户的约定（promises 的 promisor=user、promisor与promisee只能是 user 或 character）；角色单方承诺由主对话记录，不在此处提取。"
    }
}
