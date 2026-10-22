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
    // A normal fold never leaves a window below the low-water mark. If a partial
    // write/import left only one synthetic assistant message while the chat archive
    // is intact, rebuilding from the tail is safe and prevents a permanently
    // context-less conversation (the merge path cannot resurrect lost entries).
    if (kept.size < AppLimits.Memory.INSTANT_TRIM_FLOOR &&
        room.size >= AppLimits.Memory.INSTANT_TRIM_FLOOR
    ) {
        return room.takeLast(AppLimits.Memory.INSTANT)
    }
    val lastSeen = kept.maxOf { roomIndex.getValue(it.id) }
    val seen = kept.mapTo(HashSet()) { it.id }
    val appended = room.drop(lastSeen + 1).filter { it.id !in seen }
    // Safety clamp: an imported/legacy window can be far larger than the watermark
    // (the archive never drops rows). Only the recent slice is the working window.
    return (kept + appended).takeLast(AppLimits.Memory.INSTANT)
}
