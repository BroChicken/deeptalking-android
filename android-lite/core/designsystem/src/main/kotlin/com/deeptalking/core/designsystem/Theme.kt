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
enum class AppTheme(val id: String, val label: String, val swatch: Color) {
    Qingqian("", "清浅", Color(0xFFEDF0F4)),
    Black("theme-black", "夜色", Color(0xFF0F151C)),
    Blue("theme-blue", "深海", Color(0xFF0F202C)),
    Yellow("theme-yellow", "旧灯", Color(0xFF2A271B));

    companion object {
        fun fromId(id: String?): AppTheme = entries.firstOrNull { it.id == id } ?: Qingqian
    }
}

/**
 * Every CSS custom property from the legacy `:root` / theme classes, ported
 * verbatim so the native UI can match the WebView pixel-for-pixel instead of
 * guessing Material roles (which caused on-theme contrast bugs).
 */
data class LegacyColors(
    val bg: Color,
    val bgGradient: Color,
    val text: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val userBubble: Color,
    val onUserBubble: Color,
    val aiBubble: Color,
    val onAiBubble: Color,
    val aiBubbleBorder: Color,
    val border: Color,
    val panel: Color,
    val accent: Color,
    val accentBg: Color,
    val btnPrimary: Color,
    val btnPrimaryHover: Color,
    val header: Color,
    val sidebar: Color,
    val actionText: Color,
    val input: Color,
    val inputBorder: Color,
    val overlay: Color,
    val warning: Color,
)

private val qingqian = LegacyColors(
    bg = Color(0xFFEDF0F4),
    bgGradient = Color(0xFFEDF0F4),
    text = Color(0xFF19212B),
    textSecondary = Color(0xFF4F5D6B),
    textMuted = Color(0xFF7C8795),
    userBubble = Color(0xFF2864D8),
    onUserBubble = Color(0xFFFFFFFF),
    aiBubble = Color(0xFFFFFFFF),
    onAiBubble = Color(0xFF1A1A2E),
    aiBubbleBorder = Color(0xFFB6C3D1),
    border = Color(0xFFDFE4EA),
    panel = Color(0xFFFFFFFF),
    accent = Color(0xFF2864D8),
    accentBg = Color(0xFFE8F0FF),
    btnPrimary = Color(0xFF2864D8),
    btnPrimaryHover = Color(0xFF1F55BD),
    header = Color(0xEBFFFFFF),
    sidebar = Color(0xFFF7F9FB),
    actionText = Color(0xFF2864D8),
    input = Color(0xFFFFFFFF),
    inputBorder = Color(0xFFCFD7E2),
    overlay = Color(0x4D000000),
    warning = Color(0xFFB45309),
)

private val black = LegacyColors(
    bg = Color(0xFF0F151C),
    bgGradient = Color(0xFF0F151C),
    text = Color(0xFFE9EDF3),
    textSecondary = Color(0xFFAAB4C1),
    textMuted = Color(0xFF7F8B9A),
    userBubble = Color(0xFF2F6FA9),
    onUserBubble = Color(0xFFE2E8F0),
    aiBubble = Color(0xFF1A2A3C),
    onAiBubble = Color(0xFFE2E8F0),
    aiBubbleBorder = Color(0xFF3B5269),
    border = Color(0xFF2C3644),
    panel = Color(0xFF151C25),
    accent = Color(0xFF6DA8DB),
    accentBg = Color(0xFF20354A),
    btnPrimary = Color(0xFF3979AD),
    btnPrimaryHover = Color(0xFF4A8ABD),
    header = Color(0xF0111922),
    sidebar = Color(0xFF121A23),
    actionText = Color(0xFF60A5FA),
    input = Color(0xFF121A23),
    inputBorder = Color(0xFF384758),
    overlay = Color(0x99000000),
    warning = Color(0xFFE0A458),
)

private val blue = LegacyColors(
    bg = Color(0xFF0F202C),
    bgGradient = Color(0xFF0F202C),
    text = Color(0xFFB3D4FC),
    textSecondary = Color(0xFF7FA8D4),
    textMuted = Color(0xFF4A6A8A),
    userBubble = Color(0xFF2474AA),
    onUserBubble = Color(0xFFFFFFFF),
    aiBubble = Color(0xFF183447),
    onAiBubble = Color(0xFFB3D4FC),
    aiBubbleBorder = Color(0xFF2D536A),
    border = Color(0xFF284557),
    panel = Color(0xFF142A39),
    accent = Color(0xFF42A5F5),
    accentBg = Color(0x1F42A5F5),
    btnPrimary = Color(0xFF2474AA),
    btnPrimaryHover = Color(0xFF3286BD),
    header = Color(0xF00D2432),
    sidebar = Color(0xFF102532),
    actionText = Color(0xFF64B5F6),
    input = Color(0xFF142A39),
    inputBorder = Color(0xFF315469),
    overlay = Color(0x99000A1E),
    warning = Color(0xFFE0A458),
)

private val yellow = LegacyColors(
    bg = Color(0xFF2A2310),
    bgGradient = Color(0xFF2A271B),
    text = Color(0xFFF5E6B8),
    textSecondary = Color(0xFFC4A96A),
    textMuted = Color(0xFF8A7340),
    userBubble = Color(0xFF9B741B),
    onUserBubble = Color(0xFFFFFFFF),
    aiBubble = Color(0xFF3E3722),
    onAiBubble = Color(0xFFF5E6B8),
    aiBubbleBorder = Color(0xFF594F30),
    border = Color(0xFF51472A),
    panel = Color(0xFF342F1D),
    accent = Color(0xFFD4A017),
    accentBg = Color(0x26D4A017),
    btnPrimary = Color(0xFF9B741B),
    btnPrimaryHover = Color(0xFFAD8425),
    header = Color(0xF02A271B),
    sidebar = Color(0xFF302C1D),
    actionText = Color(0xFFE0B84C),
    input = Color(0xFF342F1D),
    inputBorder = Color(0xFF625431),
    overlay = Color(0x99140F05),
    warning = Color(0xFFE0A458),
)

/** Legacy bubble colors kept for backward compatibility with existing callers. */
data class AppColors(
    val userBubble: Color,
    val onUserBubble: Color,
    val aiBubble: Color,
    val onAiBubble: Color,
    val bubbleBorder: Color,
    val warning: Color,
)

private val LocalLegacy = staticCompositionLocalOf { qingqian }

/** Reads the current legacy palette; defaults to 清浅 outside the app theme. */
val MaterialTheme.legacy: LegacyColors
    @Composable get() = LocalLegacy.current

/** Bubble/misc colors derived from the legacy palette. */
val MaterialTheme.appColors: AppColors
    @Composable get() = LocalLegacy.current.let {
        AppColors(it.userBubble, it.onUserBubble, it.aiBubble, it.onAiBubble, it.aiBubbleBorder, it.warning)
    }

private fun legacyFor(theme: AppTheme): LegacyColors = when (theme) {
    AppTheme.Qingqian -> qingqian
    AppTheme.Black -> black
    AppTheme.Blue -> blue
    AppTheme.Yellow -> yellow
}

private fun colorSchemeFor(theme: AppTheme): androidx.compose.material3.ColorScheme {
    val l = legacyFor(theme)
    fun scheme() = if (theme == AppTheme.Qingqian) lightColorScheme() else darkColorScheme()
    return scheme().copy(
        primary = l.accent,
        onPrimary = l.bg,
        primaryContainer = l.accentBg,
        onPrimaryContainer = l.text,
        secondary = l.textSecondary,
        background = l.bg,
        onBackground = l.text,
        surface = l.panel,
        onSurface = l.text,
        surfaceVariant = l.sidebar,
        onSurfaceVariant = l.textMuted,
        outline = l.inputBorder,
        outlineVariant = l.border,
        error = Color(0xFFEF4444),
        onError = Color.White,
    )
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
    CompositionLocalProvider(LocalLegacy provides legacyFor(theme)) {
        MaterialTheme(
            colorScheme = colorSchemeFor(theme),
            content = content,
        )
    }
}
