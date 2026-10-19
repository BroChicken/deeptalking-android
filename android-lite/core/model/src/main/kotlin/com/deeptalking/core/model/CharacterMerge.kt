package com.deeptalking.core.model

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
 * - [Character.lorebook], each member's world book, and the long-term memory list are merged
 *   **entry-wise** (keyed by entry id), so two writers that touch different entries both keep
 *   their work, and an entry added concurrently by someone else is not dropped;
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
        staticProfile = pick(before.staticProfile, after.staticProfile, live.staticProfile),
        dynamicState = pick(before.dynamicState, after.dynamicState, live.dynamicState),
        groupSharedDynamic = pick(before.groupSharedDynamic, after.groupSharedDynamic, live.groupSharedDynamic),
        dynamicStateMeta = pick(before.dynamicStateMeta, after.dynamicStateMeta, live.dynamicStateMeta),
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
        shortTerm = pick(before.shortTerm, after.shortTerm, live.shortTerm),
        longTerm = mergeMemories(before.longTerm, after.longTerm, live.longTerm),
        instant = pick(before.instant, after.instant, live.instant),
        scenes = pick(before.scenes, after.scenes, live.scenes),
        stickers = pick(before.stickers, after.stickers, live.stickers),
        pendingRecall = pick(before.pendingRecall, after.pendingRecall, live.pendingRecall),
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
internal fun mergeTodos(before: List<Todo>, after: List<Todo>, live: List<Todo>): List<Todo> {
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
        staticProfile = pick(before.staticProfile, after.staticProfile, live.staticProfile),
        dynamicState = pick(before.dynamicState, after.dynamicState, live.dynamicState),
        dynamicStateMeta = pick(before.dynamicStateMeta, after.dynamicStateMeta, live.dynamicStateMeta),
        shortTerm = pick(before.shortTerm, after.shortTerm, live.shortTerm),
        longTerm = mergeMemories(before.longTerm, after.longTerm, live.longTerm),
        instant = pick(before.instant, after.instant, live.instant),
        pendingRecall = pick(before.pendingRecall, after.pendingRecall, live.pendingRecall),
        scenes = pick(before.scenes, after.scenes, live.scenes),
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
