package com.deeptalking.engine.cosyvoice

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** A selectable voice: the prompt-speech file plus its user-facing name. */
@Serializable
data class VoiceInfo(
    val file: String,
    val name: String,
    val builtin: Boolean = false,
    val createdAt: Long = 0L,
)

/**
 * Persistent, user-named voice catalog stored next to the prompt-speech GGUF
 * files. Display names live in `index.json`; audio files are keyed by file name,
 * so a voice can be renamed without re-encoding.
 */
class VoiceStore(private val dir: File) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val indexFile: File get() = File(dir, "index.json")

    fun list(): List<VoiceInfo> {
        val stored = read()
        val onDisk = dir.listFiles { f -> f.isFile && f.extension == "gguf" }?.map { it.name }?.toSet() ?: emptySet()
        val known = stored.filter { it.file in onDisk }
        val untracked = onDisk.filterNot { name -> known.any { it.file == name } }
            .map { VoiceInfo(file = it, name = it.removeSuffix(".gguf")) }
        return (known + untracked)
            .sortedWith(compareByDescending<VoiceInfo> { it.builtin }.thenBy { it.name })
    }

    fun add(file: File, name: String, builtin: Boolean): VoiceInfo {
        val info = VoiceInfo(file.name, name, builtin, System.currentTimeMillis())
        write(read().filterNot { it.file == file.name } + info)
        return info
    }

    fun rename(file: String, name: String): Boolean {
        val current = read()
        val index = current.indexOfFirst { it.file == file }
        if (index < 0) return false
        current[index] = current[index].copy(name = name)
        write(current)
        return true
    }

    /** Removes a user voice (built-in voices are kept). Returns true on success. */
    fun remove(file: String): Boolean {
        val current = read()
        val target = current.firstOrNull { it.file == file } ?: return false
        if (target.builtin) return false
        write(current.filterNot { it.file == file })
        runCatching { File(dir, file).delete() }
        return true
    }

    fun fileFor(file: String): File = File(dir, file)

    private fun read(): MutableList<VoiceInfo> {
        if (!indexFile.exists()) return mutableListOf()
        return runCatching { json.decodeFromString<List<VoiceInfo>>(indexFile.readText()).toMutableList() }
            .getOrDefault(mutableListOf())
    }

    private fun write(list: List<VoiceInfo>) {
        dir.mkdirs()
        runCatching { indexFile.writeText(json.encodeToString(list)) }
    }
}
