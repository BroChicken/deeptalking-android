package com.deeptalking.lite

import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.model.ChatMessage

/**
 * Materialises the persisted `character.instant` window as an append-only tail of the
 * live chat: the window content is the state (the fold pass compacts it down to the
 * low watermark), and a turn only appends the messages the window has not seen yet.
 *
 * Entries that are not (yet) rows of the chat table — the in-flight synthetic
 * assistant turn — are dropped first, so the window stays an ordered subset of the
 * chat tail and the reply cannot appear twice (once synthetic, once as its row).
 */
internal fun growInstantWindow(instant: List<ChatMessage>, room: List<ChatMessage>): List<ChatMessage> {
    if (instant.isEmpty()) {
        return if (room.size > AppLimits.Memory.INSTANT) room.takeLast(AppLimits.Memory.INSTANT) else room
    }
    val roomIndex = room.withIndex().associate { it.value.id to it.index }
    // The persisted window is authoritative: keep its content and append only the
    // chat rows it has not seen yet. Re-deriving the window from the archive (the
    // old behaviour) silently undid every fold, because the archive never drops a
    // row that the fold trimmed away, so the window could only ever grow.
    val kept = instant.filter { it.id in roomIndex }
    if (kept.isEmpty()) {
        return if (room.size > AppLimits.Memory.INSTANT) room.takeLast(AppLimits.Memory.INSTANT) else room
    }
    // Only a truly collapsed window (one synthetic/in-flight message) is rebuilt. A
    // legitimate fold can leave 18/19 messages, and treating that as corruption used to
    // re-inject already folded history on every turn.
    if (kept.size <= 1 && room.size > kept.size) {
        return room.takeLast(AppLimits.Memory.INSTANT)
    }
    val lastSeen = kept.maxOf { roomIndex.getValue(it.id) }
    val seen = kept.mapTo(HashSet()) { it.id }
    val appended = room.drop(lastSeen + 1).filter { it.id !in seen }
    // Append-only: the fold is the only trim point. Clamping here would slide the
    // window by the number of new messages and break the request prefix every turn.
    return kept + appended
}
