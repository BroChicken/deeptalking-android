package com.deeptalking.engine.remote

/**
 * Selection rules for the remote (PC) voice picker.
 *
 * `AppConfig.ttsRemoteVoice` is **not** simply "the selected server voice": an empty
 * value means *follow the on-device voice*, resolved by `NativeCore.remoteTtsConfig`
 * as `ttsRemoteVoice.ifBlank { <on-device file name without .gguf> }`. Everything that
 * edits the server-side selection has to reason in terms of that *effective* id, or it
 * edits the wrong voice (the old "rename silently hit the first server voice" bug).
 */
object RemoteVoicePolicy {

    /**
     * Selection to persist after [deletedId] was deleted from the PC.
     *
     * @param deletedId the server-side voice that was just removed.
     * @param selectedId `AppConfig.ttsRemoteVoice` ("" = follow the on-device voice).
     * @param followStem the on-device selection as a bare speaker name (`builtin_1_v2`),
     *   i.e. what an empty [selectedId] resolves to.
     * @param remaining the manifest after the removal (ids that still exist on the PC).
     * @return the new `ttsRemoteVoice`: **unchanged** when the deleted voice was not the
     *   one in use, otherwise the first voice that is still there - and `""` when the PC
     *   has none left, which the caller must surface as a warning: an empty selection
     *   resolves back to the on-device stem that was just deleted, so the next read-aloud
     *   would 404. (There is no "remote read-aloud off" config field; warning is the
     *   honest option.)
     */
    fun nextAfterDelete(
        deletedId: String,
        selectedId: String,
        followStem: String,
        remaining: List<String>,
    ): String {
        val deleted = deletedId.trim()
        val selected = selectedId.trim()
        val effective = selected.ifBlank { followStem.trim() }
        // Nothing to resolve (no selection and no on-device voice) or a different voice
        // was deleted: the stored selection stays exactly as it was.
        if (effective.isEmpty() || deleted.isEmpty() || deleted != effective) return selectedId
        return remaining.firstOrNull { it.isNotBlank() && it.trim() != deleted }?.trim().orEmpty()
    }
}
