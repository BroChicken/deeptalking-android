package com.deeptalking.feature.settings

/** The "follow the on-device voice" choice stores an empty id (see `AppConfig.ttsRemoteVoice`). */
internal const val FOLLOW_VOICE_ID = ""

/** One row of the remote-engine voice picker: [label] is shown, [id] is what gets stored. */
internal data class RemoteVoiceChoice(val label: String, val id: String)

/**
 * Builds the remote-engine voice picker.
 *
 * The two ends share one file set: the server registers its prompt-speech files under the
 * on-device file names (minus `.gguf`), so the same voice exists on both ends. Each end
 * also keeps its own *display* name - the phone's in `index.json`, the PC's in
 * `--voice-dir/names.json` - and the phone alone used to render the list, which made the
 * "重命名电脑音色" dialog look like it did nothing. Hence the label rules:
 *
 *  - both ends, PC display name differs from the phone name -> `电脑名（手机名）`
 *    (the PC name first because that is the field the rename dialog edits);
 *  - both ends, names equal -> the pre-existing `名字（stem）` / `stem` label;
 *  - PC only -> `名字 · 仅服务端`;
 *  - phone only -> the phone label plus ` · 服务端未注册`.
 *
 * An empty [remoteVoices] means "the server did not tell us" (old server, WebUI mode, or
 * not fetched yet) - never "the server has nothing" - so nothing is marked unregistered.
 */
internal fun remoteVoiceChoices(
    voices: List<VoiceOption>,
    activeVoiceId: String,
    remoteVoices: List<String>,
    remoteVoiceNames: Map<String, String>,
): List<RemoteVoiceChoice> {
    val active = voices.firstOrNull { it.id == activeVoiceId }
    val followLabel = "跟随端侧音色" + (active?.let { "（${it.name}）" }.orEmpty())
    val manifestKnown = remoteVoices.isNotEmpty()
    return buildList {
        add(RemoteVoiceChoice(followLabel, FOLLOW_VOICE_ID))
        voices.forEach { voice ->
            val stem = voiceStem(voice.id)
            val registered = !manifestKnown || stem in remoteVoices
            val remoteName = remoteVoiceNames[stem].orEmpty().trim()
            val shown = if (registered && remoteName.isNotEmpty() && remoteName != voice.name) {
                "$remoteName（${voice.name}）"
            } else {
                phoneVoiceLabel(voice.name, stem)
            }
            add(RemoteVoiceChoice(if (registered) shown else "$shown · 服务端未注册", stem))
        }
        // Voices that only exist on the PC (registered there, not on the phone).
        remoteVoices.filter { id -> voices.none { voiceStem(it.id) == id } }
            .forEach { id ->
                val name = remoteVoiceNames[id].orEmpty().trim().ifBlank { id }
                add(RemoteVoiceChoice("$name · 仅服务端", id))
            }
    }
}

/**
 * Which server-side voice the rename / delete buttons act on.
 *
 * 1. the configured remote selection, when the server still has it;
 * 2. otherwise the on-device voice's stem, when the server has it (this is the
 *    "跟随端侧音色" case, where [remoteVoice] is empty);
 * 3. otherwise `""` - the on-device voice was never uploaded, so the buttons must be
 *    disabled instead of guessing another voice (that guess is what used to rename the
 *    wrong entry);
 * 4. only when the phone has no voice at all, fall back to the first server voice.
 */
internal fun remoteVoiceEditTarget(
    remoteVoice: String,
    activeLocalVoiceId: String,
    remoteVoices: List<String>,
): String {
    val selected = remoteVoice.trim()
    if (selected.isNotEmpty() && selected in remoteVoices) return selected
    val stem = voiceStem(activeLocalVoiceId)
    if (stem.isNotEmpty() && stem in remoteVoices) return stem
    if (stem.isNotEmpty()) return ""
    return remoteVoices.firstOrNull().orEmpty()
}

/** Rename needs a concrete server voice to act on. */
internal fun canRenameRemoteVoice(busy: Boolean, target: String): Boolean = !busy && target.isNotBlank()

/**
 * Delete needs a manifest (otherwise the PC server predates the endpoint) **and** a
 * concrete voice: `!busy && remoteVoices.isNotEmpty()` alone would let the button pick a
 * voice for a local one that was never uploaded, i.e. delete an unrelated voice.
 */
internal fun canDeleteRemoteVoice(busy: Boolean, target: String, remoteVoices: List<String>): Boolean =
    !busy && remoteVoices.isNotEmpty() && target.isNotBlank()

/** `builtin_1_v2.gguf` (or a stored path) -> `builtin_1_v2`, the server's speaker id. */
internal fun voiceStem(file: String): String = file.trim()
    .substringAfterLast('/')
    .substringAfterLast('\\')
    .removeSuffix(".gguf")

/** The on-device label: bare [stem] when the name is just the file name, else `名字（stem）`. */
private fun phoneVoiceLabel(name: String, stem: String): String = if (name == stem) stem else "$name（$stem）"
