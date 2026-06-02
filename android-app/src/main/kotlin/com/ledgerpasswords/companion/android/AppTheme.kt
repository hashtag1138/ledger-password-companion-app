package com.ledgerpasswords.companion.android

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val EmberBackground = Color(0xFF120D0A)
private val EmberSurface = Color(0xFF1C130F)
private val EmberSurfaceAlt = Color(0xFF261913)
private val EmberPanel = Color(0xFF312018)
private val EmberPrimary = Color(0xFFFF9B4A)
private val EmberSecondary = Color(0xFFF3B874)
private val EmberTertiary = Color(0xFFE2672E)
private val EmberOnDark = Color(0xFFF7EEE8)
private val EmberMuted = Color(0xFFD5BCA7)
private val EmberError = Color(0xFFFF7A59)

private val LedgerWarmDarkColors =
    darkColorScheme(
        primary = EmberPrimary,
        onPrimary = Color(0xFF2F1700),
        primaryContainer = Color(0xFF5A2F09),
        onPrimaryContainer = Color(0xFFFFD9B7),
        secondary = EmberSecondary,
        onSecondary = Color(0xFF36210A),
        secondaryContainer = Color(0xFF523515),
        onSecondaryContainer = Color(0xFFFFDEBA),
        tertiary = EmberTertiary,
        onTertiary = Color(0xFF381406),
        tertiaryContainer = Color(0xFF5A2410),
        onTertiaryContainer = Color(0xFFFFD8CB),
        background = EmberBackground,
        onBackground = EmberOnDark,
        surface = EmberSurface,
        onSurface = EmberOnDark,
        surfaceVariant = EmberSurfaceAlt,
        onSurfaceVariant = EmberMuted,
        error = EmberError,
        onError = Color(0xFF3A1209),
        errorContainer = Color(0xFF5B1A10),
        onErrorContainer = Color(0xFFFFDAD2),
        outline = Color(0xFF8A6A58),
        outlineVariant = Color(0xFF4C372B),
        scrim = Color(0xFF000000),
    )

private val LedgerWarmShapes =
    Shapes(
        extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        small = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        medium = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        large = androidx.compose.foundation.shape.RoundedCornerShape(32.dp),
    )

@Composable
internal fun LedgerWarmTheme(
    content: @Composable () -> Unit,
) {
    val colors = if (isSystemInDarkTheme()) LedgerWarmDarkColors else LedgerWarmDarkColors
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(),
        shapes = LedgerWarmShapes,
        content = content,
    )
}

internal object LedgerWarmPalette {
    val Panel = EmberPanel
    val Muted = EmberMuted
}
