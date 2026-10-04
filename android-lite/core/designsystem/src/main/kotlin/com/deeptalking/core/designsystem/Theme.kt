package com.deeptalking.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * App-level fixed palettes mirroring the legacy WebView themes
 * (`src/styles/app.css`). [id] matches `AppConfig.activeTheme`.
 */
enum class AppTheme(val id: String, val label: String) {
    Qingqian("", "清浅"),
    Black("theme-black", "夜色"),
    Blue("theme-blue", "深海"),
    Yellow("theme-yellow", "旧灯");

    companion object {
        fun fromId(id: String?): AppTheme = entries.firstOrNull { it.id == id } ?: Qingqian
    }
}

/** Extra colors the four legacy themes need beyond Material's scheme. */
data class AppColors(
    val userBubble: Color,
    val onUserBubble: Color,
    val aiBubble: Color,
    val onAiBubble: Color,
    val bubbleBorder: Color,
    val warning: Color,
)

private val LocalAppColors = staticCompositionLocalOf {
    AppColors(
        userBubble = Color(0xFF2864D8),
        onUserBubble = Color.White,
        aiBubble = Color.White,
        onAiBubble = Color(0xFF19212B),
        bubbleBorder = Color(0xFFDFE4EA),
        warning = Color(0xFFB45309),
    )
}

/** Reads the current palette; defaults to 清浅 outside the app theme. */
val MaterialTheme.appColors: AppColors
    @Composable get() = LocalAppColors.current

private fun qingqianColors() = lightColorScheme(
    primary = Color(0xFF2864D8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8F0FF),
    onPrimaryContainer = Color(0xFF19212B),
    secondary = Color(0xFF4F5D6B),
    background = Color(0xFFEDF0F4),
    onBackground = Color(0xFF19212B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF19212B),
    surfaceVariant = Color(0xFFF7F9FB),
    onSurfaceVariant = Color(0xFF7C8795),
    outline = Color(0xFFCFD7E2),
    outlineVariant = Color(0xFFDFE4EA),
    error = Color(0xFFB3261E),
)

private fun blackColors() = darkColorScheme(
    primary = Color(0xFF6DA8DB),
    onPrimary = Color(0xFF0F151C),
    primaryContainer = Color(0xFF20354A),
    onPrimaryContainer = Color(0xFFE9EDF3),
    secondary = Color(0xFFAAB4C1),
    background = Color(0xFF0F151C),
    onBackground = Color(0xFFE9EDF3),
    surface = Color(0xFF121A23),
    onSurface = Color(0xFFE9EDF3),
    surfaceVariant = Color(0xFF1A2A3C),
    onSurfaceVariant = Color(0xFF7F8B9A),
    outline = Color(0xFF384758),
    outlineVariant = Color(0xFF2C3644),
    error = Color(0xFFCF6679),
)

private fun blueColors() = darkColorScheme(
    primary = Color(0xFF42A5F5),
    onPrimary = Color(0xFF0F202C),
    primaryContainer = Color(0x2042A5F5),
    onPrimaryContainer = Color(0xFFB3D4FC),
    secondary = Color(0xFF7FA8D4),
    background = Color(0xFF0F202C),
    onBackground = Color(0xFFB3D4FC),
    surface = Color(0xFF102532),
    onSurface = Color(0xFFB3D4FC),
    surfaceVariant = Color(0xFF183447),
    onSurfaceVariant = Color(0xFF4A6A8A),
    outline = Color(0xFF315469),
    outlineVariant = Color(0xFF284557),
    error = Color(0xFFE57373),
)

private fun yellowColors() = darkColorScheme(
    primary = Color(0xFFD4A017),
    onPrimary = Color(0xFF2A271B),
    primaryContainer = Color(0x26D4A017),
    onPrimaryContainer = Color(0xFFF5E6B8),
    secondary = Color(0xFFC4A96A),
    background = Color(0xFF2A271B),
    onBackground = Color(0xFFF5E6B8),
    surface = Color(0xFF302C1D),
    onSurface = Color(0xFFF5E6B8),
    surfaceVariant = Color(0xFF3E3722),
    onSurfaceVariant = Color(0xFF8A7340),
    outline = Color(0xFF625431),
    outlineVariant = Color(0xFF51472A),
    error = Color(0xFFE57373),
)

private fun appColorsFor(theme: AppTheme): AppColors = when (theme) {
    AppTheme.Qingqian -> AppColors(
        userBubble = Color(0xFF2864D8),
        onUserBubble = Color.White,
        aiBubble = Color.White,
        onAiBubble = Color(0xFF19212B),
        bubbleBorder = Color(0xFFDFE4EA),
        warning = Color(0xFFB45309),
    )
    AppTheme.Black -> AppColors(
        userBubble = Color(0xFF2F6FA9),
        onUserBubble = Color.White,
        aiBubble = Color(0xFF1A2A3C),
        onAiBubble = Color(0xFFE9EDF3),
        bubbleBorder = Color(0xFF2C3644),
        warning = Color(0xFFE0A458),
    )
    AppTheme.Blue -> AppColors(
        userBubble = Color(0xFF2474AA),
        onUserBubble = Color.White,
        aiBubble = Color(0xFF183447),
        onAiBubble = Color(0xFFB3D4FC),
        bubbleBorder = Color(0xFF284557),
        warning = Color(0xFFE0A458),
    )
    AppTheme.Yellow -> AppColors(
        userBubble = Color(0xFF9B741B),
        onUserBubble = Color.White,
        aiBubble = Color(0xFF3E3722),
        onAiBubble = Color(0xFFF5E6B8),
        bubbleBorder = Color(0xFF51472A),
        warning = Color(0xFFE0A458),
    )
}

private fun colorSchemeFor(theme: AppTheme) = when (theme) {
    AppTheme.Qingqian -> qingqianColors()
    AppTheme.Black -> blackColors()
    AppTheme.Blue -> blueColors()
    AppTheme.Yellow -> yellowColors()
}

/**
 * App theme driven by the user's saved [AppTheme]. Defaults to 清浅, matching
 * the legacy `setTheme('')` default.
 */
@Composable
fun DeepTalkingTheme(
    theme: AppTheme = AppTheme.Qingqian,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalAppColors provides appColorsFor(theme)) {
        MaterialTheme(
            colorScheme = colorSchemeFor(theme),
            content = content,
        )
    }
}
