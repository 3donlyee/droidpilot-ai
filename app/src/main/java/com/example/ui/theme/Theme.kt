package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = CyanNeon,
    onPrimary = SpaceBlack,
    primaryContainer = VioletNeonDark,
    onPrimaryContainer = Color.White,
    secondary = VioletNeon,
    onSecondary = Color.White,
    tertiary = EmeraldGreen,
    background = SpaceBlack,
    onBackground = TextPrimary,
    surface = SpaceCard,
    onSurface = TextPrimary,
    surfaceVariant = SpaceCardElevated,
    onSurfaceVariant = TextSecondary,
    outline = SpaceBorder
)

private val LightColorScheme = lightColorScheme(
    primary = CyanNeonDark,
    onPrimary = Color.White,
    primaryContainer = VioletNeon,
    onPrimaryContainer = Color.White,
    secondary = VioletNeonDark,
    onSecondary = Color.White,
    tertiary = EmeraldGreen,
    background = Color(0xFFF1F5F9),
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1)
)

@Composable
fun DroidPilotTheme(
    darkTheme: Boolean = true, // Default to futuristic dark mode
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
