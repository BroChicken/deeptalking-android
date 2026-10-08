package com.deeptalking.core.data

import android.content.Context
import com.deeptalking.core.database.AppDatabase
import java.io.File

/** Manual DI container wiring the Room database and repositories for the app. */
class CoreDataContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val database: AppDatabase = AppDatabase.build(appContext)
    val characters: CharacterRepository = CharacterRepository(
        database.characterDao(),
        onCorruptRow = { id, payload -> preserveCorruptCharacter(id, payload) },
    )
    val chat: ChatRepository = ChatRepository(database.messageDao())
    val config: ConfigRepository = ConfigRepository(database.configDao())
    val settings: SettingsStore = SettingsStore(appContext)

    /**
     * Legacy `loadData` preserved an unparseable store under `STORAGE_RECOVERY_KEY`
     * instead of dropping it. Mirror that by dumping the raw payload of a corrupt
     * character row into `filesDir/character_recovery/`.
     */
    private fun preserveCorruptCharacter(id: String, payload: String) {
        runCatching {
            val dir = File(appContext.filesDir, "character_recovery").apply { mkdirs() }
            val safeName = id.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "unknown" }
            File(dir, "$safeName.json").writeText(payload)
        }
    }
}
