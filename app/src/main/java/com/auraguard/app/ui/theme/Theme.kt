package com.auraguard.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val AuraDarkColors = darkColorScheme(
    background = OpsBackground,
    surface = OpsSurface,
    surfaceVariant = OpsSurfaceElevated,
    primary = OpsAccent,
    onPrimary = OpsBackground,
    secondary = OpsInfo,
    error = OpsCritical,
    onBackground = OpsTextPrimary,
    onSurface = OpsTextPrimary,
    outline = OpsBorder
)

@Composable
fun AuraGuardTheme(content: @Composable () -> Unit) {
    // AURA Guard is always dark — a bright command console would defeat the point at night.
    val useDark = true
    MaterialTheme(
        colorScheme = AuraDarkColors,
        typography = AuraTypography,
        content = content
    )
}
