package com.deeptalking.core.model

/** Editor section a registered field belongs to. */
enum class FieldSection(val id: String) {
    /** Character/member static profile (`StaticProfile`). */
    Static("static"),

    /** Current state (`DynamicState` / `groupSharedDynamic`). */
    Dynamic("dynamic"),
}

/**
 * Identifies one editable field of one entity/member.
 *
 * [memberId] is `"-"` for the entity itself; a legacy member row without an id also maps to
 * `"-"` instead of pretending to address a member (see [FieldKey.of]).
 */
data class FieldKey(
    val characterId: String,
    val memberId: String,
    val section: FieldSection,
    val field: String,
) {
    companion object {
        /** Placeholder member id: the character/group itself, not one of its members. */
        const val SELF_MEMBER: String = "-"

        fun of(characterId: String, memberId: String?, section: FieldSection, field: String): FieldKey =
            FieldKey(
                characterId = characterId,
                memberId = memberId?.takeIf { it.isNotBlank() } ?: SELF_MEMBER,
                section = section,
                field = field,
            )
    }
}

/**
 * In-memory registry of the fields the user is currently typing in.
 *
 * A background pass (analysis, auto-fill, the turn worker) computes its new value from a
 * snapshot taken **before** the user started typing; applying that value straight onto the
 * row would silently discard the text the user is entering (it is only in the editor's draft).
 * Writers therefore ask this registry and leave a focused field alone — every other field is
 * applied normally.
 *
 * Keys are `(characterId, memberId, section, field)`; [owner] identifies the editor instance
 * that registered the focus, so closing/replacing an editor can drop exactly its own entries
 * ([clearOwner]) without disturbing another. All operations are thread-safe: the compose UI
 * registers on the main thread while workers apply fields on IO threads.
 */
object FieldEditRegistry {

    private val lock = Any()

    /** field -> owner currently editing it. */
    private val focused = HashMap<FieldKey, String>()

    /** Registers [key] as being edited by [owner]. Re-registering replaces the owner. */
    fun focus(key: FieldKey, owner: String) {
        synchronized(lock) { focused[key] = owner }
    }

    /** Drops [key] only when [owner] still owns it (a stale blur must not clear someone else). */
    fun blur(key: FieldKey, owner: String) {
        synchronized(lock) { if (focused[key] == owner) focused.remove(key) }
    }

    fun isFocused(key: FieldKey): Boolean = synchronized(lock) { focused.containsKey(key) }

    /** Convenience overload for writers: `isFocused(id, memberId, FieldSection.Dynamic, key)`. */
    fun isFocused(characterId: String, memberId: String?, section: FieldSection, field: String): Boolean =
        isFocused(FieldKey.of(characterId, memberId, section, field))

    /** Fields of one entity/member/section that are focused right now. */
    fun focusedFields(characterId: String, memberId: String?, section: FieldSection): Set<String> {
        val member = memberId?.takeIf { it.isNotBlank() } ?: FieldKey.SELF_MEMBER
        return synchronized(lock) {
            focused.keys.filter { it.characterId == characterId && it.memberId == member && it.section == section }
                .map { it.field }
                .toSet()
        }
    }

    /** Removes every field registered by one editor instance (dialog closed / saved / switched). */
    fun clearOwner(owner: String) {
        synchronized(lock) { focused.entries.removeAll { it.value == owner } }
    }

    /** Removes every entry; only for tests and full-editor teardown. */
    fun clearAll() {
        synchronized(lock) { focused.clear() }
    }
}
