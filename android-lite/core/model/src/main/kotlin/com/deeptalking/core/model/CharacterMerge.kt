package com.deeptalking.core.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Three-way merge of a character change onto the freshest stored row.
 *
 * Every writer in the app (the chat turn, the background memory passes, the editor) used
 * to persist its **whole** snapshot, so whichever write landed last silently rolled back
 * whatever another writer had changed in the meantime — the world book was the worst
 * case, because a world-book pass that had been running for seconds would write back the
 * character as it was when that pass started.
 *
 * This helper applies only what a writer actually changed:
 *
 * - a scalar/list field is taken from [after] **only** when it differs from [before];
 * - [Character.lorebook], each member's world book, the long-term memory list, the short-term
 *   window ([Character.shortTerm]), the `instant` message window ([Character.instant]), the
 *   scene summaries ([Character.scenes]) and the pending-recall items
 *   ([Character.pendingRecall]) are merged **entry-wise** (keyed by entry id), so two writers
 *   that touch different entries both keep their work, and an entry added concurrently by
 *   someone else is not dropped;
 * - members are matched by id; every other field of a member follows the same rules.
 *
 * Additions are applied, changes are applied, and a removal is only applied when the
 * writer genuinely had the element before and no longer does.
 */
fun mergeCharacterChanges(before: Character, after: Character, live: Character): Character {
    if (before == after) return live
    return live.copy(
        name = pick(before.name, after.name, live.name),
        emoji = pick(before.emoji, after.emoji, live.emoji),
        description = pick(before.description, after.description, live.description),
        isGroup = pick(before.isGroup, after.isGroup, live.isGroup),
        interactionRules = pick(before.interactionRules, after.interactionRules, live.interactionRules),
        staticProfile = mergeStaticProfile(before.staticProfile, after.staticProfile, live.staticProfile),
        dynamicState = mergeDynamicState(before.dynamicState, after.dynamicState, live.dynamicState),
        groupSharedDynamic = mergeDynamicState(before.groupSharedDynamic, after.groupSharedDynamic, live.groupSharedDynamic),
        dynamicStateMeta = mergeDynamicStateMeta(before.dynamicStateMeta, after.dynamicStateMeta, live.dynamicStateMeta),
        avatarRepairPending = pick(before.avatarRepairPending, after.avatarRepairPending, live.avatarRepairPending),
        counters = pick(before.counters, after.counters, live.counters),
        sceneState = pick(before.sceneState, after.sceneState, live.sceneState),
        staticFillMeta = pick(before.staticFillMeta, after.staticFillMeta, live.staticFillMeta),
        staticFieldMeta = pick(before.staticFieldMeta, after.staticFieldMeta, live.staticFieldMeta),
        timeParseVersion = pick(before.timeParseVersion, after.timeParseVersion, live.timeParseVersion),
        fieldsMigrationVersion = pick(before.fieldsMigrationVersion, after.fieldsMigrationVersion, live.fieldsMigrationVersion),
        lorebookMigratedAt = pick(before.lorebookMigratedAt, after.lorebookMigratedAt, live.lorebookMigratedAt),
        memoryRepairVersion = pick(before.memoryRepairVersion, after.memoryRepairVersion, live.memoryRepairVersion),
        revision = pick(before.revision, after.revision, live.revision),
        workerIssues = pick(before.workerIssues, after.workerIssues, live.workerIssues),
        shortTerm = mergeEntryList(before.shortTerm, after.shortTerm, live.shortTerm) { it.id },
        longTerm = mergeMemories(before.longTerm, after.longTerm, live.longTerm),
        instant = mergeEntryList(before.instant, after.instant, live.instant) { it.id },
        scenes = mergeEntryList(before.scenes, after.scenes, live.scenes) { it.id },
        stickers = pick(before.stickers, after.stickers, live.stickers),
        pendingRecall = mergeEntryList(before.pendingRecall, after.pendingRecall, live.pendingRecall) { it.id },
        lastInjectedRecallIds = pick(before.lastInjectedRecallIds, after.lastInjectedRecallIds, live.lastInjectedRecallIds),
        lorebook = mergeLorebook(before.lorebook, after.lorebook, live.lorebook),
        lorebookArchive = mergeLorebook(before.lorebookArchive, after.lorebookArchive, live.lorebookArchive),
        todos = mergeTodos(before.todos, after.todos, live.todos),
        members = mergeMembers(before.members, after.members, live.members),
    )
}

/** [after] wins only when it differs from [before]; otherwise the stored value stays. */
private fun <T> pick(before: T, after: T, live: T): T = if (after != before) after else live

/**
 * Field-wise merge of a dynamic state: a writer only wins on the keys it actually changed,
 * so a worker mood update and a user situation edit made at the same time both survive.
 */
fun mergeDynamicState(before: DynamicState, after: DynamicState, live: DynamicState): DynamicState {
    if (before == after) return live
    return live.copy(
        currentSituation = pick(before.currentSituation, after.currentSituation, live.currentSituation),
        currentLocation = pick(before.currentLocation, after.currentLocation, live.currentLocation),
        currentMood = pick(before.currentMood, after.currentMood, live.currentMood),
        currentOccupation = pick(before.currentOccupation, after.currentOccupation, live.currentOccupation),
        currentGoal = pick(before.currentGoal, after.currentGoal, live.currentGoal),
        currentTone = pick(before.currentTone, after.currentTone, live.currentTone),
    )
}

/** Dynamic-state keys whose value differs between [before] and [after]. */
fun changedDynamicStateKeys(before: DynamicState, after: DynamicState): Set<String> = buildSet {
    if (before.currentSituation != after.currentSituation) add("currentSituation")
    if (before.currentLocation != after.currentLocation) add("currentLocation")
    if (before.currentMood != after.currentMood) add("currentMood")
    if (before.currentOccupation != after.currentOccupation) add("currentOccupation")
    if (before.currentGoal != after.currentGoal) add("currentGoal")
    if (before.currentTone != after.currentTone) add("currentTone")
}

/**
 * Add-only bookkeeping merge for a writer that knows exactly which keys it changed: live
 * entries stay, [source] may fill gaps, and every key in [keys] gets a fresh [now] stamp.
 * Nothing is ever removed, so a stale snapshot cannot wipe another writer's timestamps.
 */
fun stampDynamicStateMeta(
    live: Map<String, DynamicStateMeta>,
    source: Map<String, DynamicStateMeta>,
    keys: Set<String>,
    now: String,
): Map<String, DynamicStateMeta> {
    if (keys.isEmpty() && source.isEmpty()) return live
    val merged = live.toMutableMap()
    merged.putAll(source)
    keys.forEach { merged[it] = DynamicStateMeta(updatedAt = now) }
    return merged
}

/** Same rule for the per-field timestamps: merge by key, never replace the whole map. */
fun mergeDynamicStateMeta(
    before: Map<String, DynamicStateMeta>,
    after: Map<String, DynamicStateMeta>,
    live: Map<String, DynamicStateMeta>,
): Map<String, DynamicStateMeta> {
    if (before == after) return live
    val result = live.toMutableMap()
    after.forEach { (key, value) ->
        if (before[key] != value) result[key] = value
    }
    (before.keys - after.keys).forEach { key -> result.remove(key) }
    return result
}

/** Field-wise merge of the static profile (field revision vs. auto-fill/edit). */
fun mergeStaticProfile(before: StaticProfile, after: StaticProfile, live: StaticProfile): StaticProfile {
    if (before == after) return live
    return live.copy(
        gender = pick(before.gender, after.gender, live.gender),
        age = pick(before.age, after.age, live.age),
        race = pick(before.race, after.race, live.race),
        appearance = pick(before.appearance, after.appearance, live.appearance),
        personality = pick(before.personality, after.personality, live.personality),
        fears = pick(before.fears, after.fears, live.fears),
        background = pick(before.background, after.background, live.background),
        speakingStyle = pick(before.speakingStyle, after.speakingStyle, live.speakingStyle),
        language = pick(before.language, after.language, live.language),
        userAddress = pick(before.userAddress, after.userAddress, live.userAddress),
    )
}

/** Static-profile keys whose value differs between [before] and [after]. */
fun changedStaticProfileKeys(before: StaticProfile, after: StaticProfile): Set<String> = buildSet {
    if (before.gender != after.gender) add("gender")
    if (before.age != after.age) add("age")
    if (before.race != after.race) add("race")
    if (before.appearance != after.appearance) add("appearance")
    if (before.personality != after.personality) add("personality")
    if (before.fears != after.fears) add("fears")
    if (before.background != after.background) add("background")
    if (before.speakingStyle != after.speakingStyle) add("speakingStyle")
    if (before.language != after.language) add("language")
    if (before.userAddress != after.userAddress) add("userAddress")
}

/**
 * Stamps [keys] into `staticFieldMeta` with a fresh `{"updatedAt": now}` entry, keeping
 * every other key. Manual editor saves and the manual per-field regenerate path use this
 * shape; auto-fill must not overwrite a field the user already touched.
 *
 * Only the keys that actually changed may be stamped (never the whole map).
 */
fun withStaticFieldTimestamps(
    meta: Map<String, JsonElement>,
    keys: Set<String>,
    now: String,
): Map<String, JsonElement> {
    if (keys.isEmpty()) return meta
    val result = meta.toMutableMap()
    keys.forEach { result[it] = buildJsonObject { put("updatedAt", now) } }
    return result
}

/**
 * Entry-wise merge of a world book. Entries the writer added or changed are applied
 * (added ones only when they were genuinely new to the writer), entries it removed are
 * removed — and entries only the concurrent writer knows about are left alone.
 */
internal fun mergeLorebook(
    before: List<LorebookEntry>,
    after: List<LorebookEntry>,
    live: List<LorebookEntry>,
): List<LorebookEntry> {
    if (before == after) return live
    val beforeById = before.associateBy { it.id }
    val afterById = after.associateBy { it.id }
    val result = live.toMutableList()
    afterById.forEach { (id, entry) ->
        val index = result.indexOfFirst { it.id == id }
        val previous = beforeById[id]
        when {
            index < 0 -> if (previous == null && !live.any { it.id == id }) result += entry
            previous != entry -> result[index] = entry
            else -> Unit
        }
    }
    val removed = beforeById.keys - afterById.keys
    if (removed.isNotEmpty()) result.removeAll { it.id in removed }
    return result
}

/**
 * Entry-wise merge of the todo list — the same rules as [mergeLorebook], keyed by id.
 *
 * Needed so the card editor (which holds a snapshot of the draft from when it opened) does not
 * roll back a todo the turn or a background pass added in the meantime. A blank id (legacy rows
 * that cannot be located) falls back to the writer's own list rather than guessing.
 */
fun mergeTodos(before: List<Todo>, after: List<Todo>, live: List<Todo>): List<Todo> {
    if (before == after) return live
    if (before.any { it.id.isBlank() } || after.any { it.id.isBlank() }) return after
    val beforeById = before.associateBy { it.id }
    val afterById = after.associateBy { it.id }
    val result = live.toMutableList()
    afterById.forEach { (id, item) ->
        val index = result.indexOfFirst { it.id == id }
        val previous = beforeById[id]
        when {
            index < 0 -> if (previous == null && !live.any { it.id == id }) result += item
            previous != item -> result[index] = item
            else -> Unit
        }
    }
    val removed = beforeById.keys - afterById.keys
    if (removed.isNotEmpty()) result.removeAll { it.id in removed }
    return result
}

/**
 * Entry-wise merge of any list whose entries carry a stable id — the short-term window,
 * the `instant` message window, scene summaries and the pending-recall items.
 *
 * These lists used to be a whole-table `pick`, so any writer that touched them once (a fold
 * consuming the front of the window, a turn appending a message, an analysis pass replacing
 * the pending-recall set) rewrote the whole list from the snapshot it started with and threw
 * away whatever another writer had done in the meantime — a turn's fresh message, or a fold's
 * analysis result that had been running for seconds.
 *
 * The rules are the same as [mergeLorebook]'s: additions are applied, a change is applied only
 * for the ids the writer actually changed (an untouched entry always keeps the live copy), and
 * a removal is applied only for ids the writer had before and no longer has. An entry that
 * only the live row knows is never dropped, and an entry the writer kept but that live already
 * consumed is not resurrected.
 *
 * Entries whose id is blank (legacy rows) or duplicated inside one of the lists cannot be
 * located, so they are never merged one by one: they are treated as one opaque group that is
 * only replaced as a whole, and only when the writer arguably changed that group. That way a
 * legacy window without ids is still consumed, while a concurrent writer's unlocatable entries
 * are never collapsed onto a single `""` key or silently dropped.
 */
internal fun <T> mergeEntryList(
    before: List<T>,
    after: List<T>,
    live: List<T>,
    idOf: (T) -> String,
): List<T> {
    if (before == after) return live
    val ambiguous = ambiguousIds(before, after, idOf)
    val beforeById = before.filter { idOf(it) !in ambiguous }.associateBy(idOf)
    val afterById = after.filter { idOf(it) !in ambiguous }.associateBy(idOf)
    val removed = beforeById.keys - afterById.keys
    val beforeUnlocatable = before.filter { idOf(it) in ambiguous }
    val afterUnlocatable = after.filter { idOf(it) in ambiguous }
    val unlocatableChanged = beforeUnlocatable != afterUnlocatable

    val result = ArrayList<T>(live.size + afterById.size)
    var unlocatablePlaced = false
    live.forEach { entry ->
        val id = idOf(entry)
        if (id in ambiguous) {
            if (unlocatableChanged) {
                // One slot for the whole group, in the position the live group occupied.
                if (!unlocatablePlaced) {
                    result += afterUnlocatable
                    unlocatablePlaced = true
                }
            } else {
                result += entry
            }
        } else if (id !in removed) {
            val previous = beforeById[id]
            val written = afterById[id]
            result += if (written != null && previous != written) written else entry
        }
    }
    val present = result.mapTo(HashSet(), idOf)
    afterById.forEach { (id, entry) ->
        // Only ids the writer genuinely added; an id that live already lost stays lost.
        if (id !in present && beforeById[id] == null) result += entry
    }
    if (unlocatableChanged && !unlocatablePlaced && afterUnlocatable.isNotEmpty()) result += afterUnlocatable
    return result
}

/**
 * Ids that cannot be used as a merge key: blank ones and ones duplicated **inside** one list
 * (`associateBy` would silently collapse two distinct entries into one). An id that occurs once
 * in [before] and once in [after] is of course perfectly locatable.
 */
private fun <T> ambiguousIds(before: List<T>, after: List<T>, idOf: (T) -> String): Set<String> = buildSet {
    listOf(before, after).forEach { list ->
        list.groupingBy(idOf).eachCount().forEach { (id, count) ->
            if (id.isBlank() || count > 1) add(id)
        }
    }
}

/**
 * Merge of the card editor's member fields onto the freshest member row.
 *
 * The editor owns a member's `name`/`emoji`/`roleInGroup`, the static profile (field-wise, so a
 * background auto-fill of another field is not rolled back) and the dynamic state (per changed
 * key). The member's world book, memories and counters stay live: background tasks own those and
 * the card editor never exposes them.
 */
fun mergeMemberCardEdits(live: GroupMember, draft: GroupMember, now: String): GroupMember {
    val stateKeys = changedDynamicStateKeys(live.dynamicState, draft.dynamicState)
    val staticKeys = changedStaticProfileKeys(live.staticProfile, draft.staticProfile)
    return live.copy(
        name = draft.name,
        emoji = draft.emoji,
        roleInGroup = draft.roleInGroup,
        staticProfile = mergeStaticProfile(live.staticProfile, draft.staticProfile, live.staticProfile),
        staticFieldMeta = withStaticFieldTimestamps(live.staticFieldMeta, staticKeys, now),
        dynamicState = mergeDynamicState(live.dynamicState, draft.dynamicState, live.dynamicState),
        dynamicStateMeta = stampDynamicStateMeta(live.dynamicStateMeta, draft.dynamicStateMeta, stateKeys, now),
    )
}

/**
 * Entry-wise merge of a long-term memory list — the same rules as [mergeLorebook], keyed by id.
 *
 * Needed because a scalar-style `pick` on the list would let *any* writer that touched one
 * memory (e.g. the promise-confirmation stamp) rewrite the whole list from its own snapshot and
 * drop a memory a concurrent background pass had just written.
 *
 * Two pathological shapes deliberately fall back to the whole-list rule instead of guessing:
 * a blank id (legacy rows that cannot be located) and a duplicated id (`associateBy` would
 * silently collapse two distinct entries into one).
 */
internal fun mergeMemories(
    before: List<LongTermMemory>,
    after: List<LongTermMemory>,
    live: List<LongTermMemory>,
): List<LongTermMemory> {
    if (before == after) return live
    if (!isLocatable(before) || !isLocatable(after)) return after
    val beforeById = before.associateBy { it.id }
    val afterById = after.associateBy { it.id }
    val result = live.toMutableList()
    afterById.forEach { (id, item) ->
        val index = result.indexOfFirst { it.id == id }
        val previous = beforeById[id]
        when {
            index < 0 -> if (previous == null && !live.any { it.id == id }) result += item
            previous != item -> result[index] = item
            else -> Unit
        }
    }
    val removed = beforeById.keys - afterById.keys
    if (removed.isNotEmpty()) result.removeAll { it.id in removed }
    return result
}

/** True when every entry carries a non-blank, unique id, so an entry-wise merge is well defined. */
private fun isLocatable(items: List<LongTermMemory>): Boolean {
    if (items.any { it.id.isBlank() }) return false
    return items.map { it.id }.toSet().size == items.size
}

/** Member-wise merge: same rules one level down (including each member's world book). */
internal fun mergeMembers(
    before: List<GroupMember>,
    after: List<GroupMember>,
    live: List<GroupMember>,
): List<GroupMember> {
    if (before == after) return live
    val beforeById = before.associateBy { it.id }
    val afterById = after.associateBy { it.id }
    val result = live.toMutableList()
    afterById.forEach { (id, member) ->
        val index = result.indexOfFirst { it.id == id }
        val previous = beforeById[id]
        when {
            index < 0 -> if (previous == null && !live.any { it.id == id }) result += member
            previous == null -> result[index] = member
            previous != member -> result[index] = mergeMemberChanges(previous, member, result[index])
            else -> Unit
        }
    }
    val removed = beforeById.keys - afterById.keys
    if (removed.isNotEmpty()) result.removeAll { it.id in removed }
    return result
}

/** Field-wise merge of one group member (see [mergeCharacterChanges]). */
internal fun mergeMemberChanges(before: GroupMember, after: GroupMember, live: GroupMember): GroupMember =
    live.copy(
        name = pick(before.name, after.name, live.name),
        emoji = pick(before.emoji, after.emoji, live.emoji),
        roleInGroup = pick(before.roleInGroup, after.roleInGroup, live.roleInGroup),
        staticProfile = mergeStaticProfile(before.staticProfile, after.staticProfile, live.staticProfile),
        dynamicState = mergeDynamicState(before.dynamicState, after.dynamicState, live.dynamicState),
        dynamicStateMeta = mergeDynamicStateMeta(before.dynamicStateMeta, after.dynamicStateMeta, live.dynamicStateMeta),
        shortTerm = mergeEntryList(before.shortTerm, after.shortTerm, live.shortTerm) { it.id },
        longTerm = mergeMemories(before.longTerm, after.longTerm, live.longTerm),
        instant = mergeEntryList(before.instant, after.instant, live.instant) { it.id },
        pendingRecall = mergeEntryList(before.pendingRecall, after.pendingRecall, live.pendingRecall) { it.id },
        scenes = mergeEntryList(before.scenes, after.scenes, live.scenes) { it.id },
        sceneState = pick(before.sceneState, after.sceneState, live.sceneState),
        counters = pick(before.counters, after.counters, live.counters),
        revision = pick(before.revision, after.revision, live.revision),
        avatarRepairPending = pick(before.avatarRepairPending, after.avatarRepairPending, live.avatarRepairPending),
        staticFillMeta = pick(before.staticFillMeta, after.staticFillMeta, live.staticFillMeta),
        staticFieldMeta = pick(before.staticFieldMeta, after.staticFieldMeta, live.staticFieldMeta),
        fieldsMigrationVersion = pick(before.fieldsMigrationVersion, after.fieldsMigrationVersion, live.fieldsMigrationVersion),
        timeParseVersion = pick(before.timeParseVersion, after.timeParseVersion, live.timeParseVersion),
        lorebookMigratedAt = pick(before.lorebookMigratedAt, after.lorebookMigratedAt, live.lorebookMigratedAt),
        memoryRepairVersion = pick(before.memoryRepairVersion, after.memoryRepairVersion, live.memoryRepairVersion),
        lorebook = mergeLorebook(before.lorebook, after.lorebook, live.lorebook),
        lorebookArchive = mergeLorebook(before.lorebookArchive, after.lorebookArchive, live.lorebookArchive),
    )
