package com.deeptalking.lite

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.deeptalking.feature.characters.CharactersScreen
import com.deeptalking.feature.chat.ChatScreen
import com.deeptalking.feature.memory.MemoryScreen
import com.deeptalking.feature.settings.SettingsScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

@Composable
fun AppRoot(core: NativeCore) {
    val vm: AppViewModel = viewModel(
        factory = viewModelFactory { initializer { AppViewModel(core) } },
    )

    val characters by vm.characters.collectAsState()
    val activeId by vm.activeId.collectAsState()
    val messages by vm.messages.collectAsState()
    val quickReplies by vm.quickReplies.collectAsState()
    val isSending by vm.isSending.collectAsState()
    val status by vm.status.collectAsState()
    val config by vm.config.collectAsState()

    val activeCharacter = characters.firstOrNull { it.id == activeId }
    val hasApiKey = remember(config) { vm.hasApiKey() }

    val tabs = listOf(
        Tab("chat", "聊天", Icons.Default.Chat),
        Tab("characters", "角色", Icons.Default.Person),
        Tab("memory", "记忆", Icons.Default.Psychology),
        Tab("settings", "设置", Icons.Default.Settings),
    )

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            if (currentRoute != tab.route) {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.startDestinationId) { inclusive = false }
                                    launchSingleTop = true
                                }
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "chat",
            modifier = Modifier.padding(padding),
        ) {
            composable("chat") {
                ChatScreen(
                    characterName = activeCharacter?.name ?: "未选择角色",
                    messages = messages,
                    quickReplies = quickReplies,
                    isSending = isSending,
                    status = status,
                    onSend = vm::send,
                    onQuickReply = vm::send,
                )
            }
            composable("characters") {
                CharactersScreen(
                    characters = characters,
                    activeId = activeId,
                    onSelect = vm::select,
                    onCreate = vm::createCharacter,
                    onDelete = vm::deleteCharacter,
                )
            }
            composable("memory") {
                MemoryScreen(character = activeCharacter)
            }
            composable("settings") {
                SettingsScreen(
                    config = config,
                    hasApiKey = hasApiKey,
                    onSave = vm::saveSettings,
                    onTestReminder = vm::testReminder,
                )
            }
        }
    }
}
