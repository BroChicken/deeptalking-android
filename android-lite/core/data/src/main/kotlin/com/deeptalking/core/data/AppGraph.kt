package com.deeptalking.core.data

import android.content.Context
import com.deeptalking.core.database.AppDatabase

/** Manual DI container wiring the Room database and repositories for the app. */
class CoreDataContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val database: AppDatabase = AppDatabase.build(appContext)
    val characters: CharacterRepository = CharacterRepository(database.characterDao())
    val chat: ChatRepository = ChatRepository(database.messageDao())
    val config: ConfigRepository = ConfigRepository(database.configDao())
    val settings: SettingsStore = SettingsStore(appContext)
}
