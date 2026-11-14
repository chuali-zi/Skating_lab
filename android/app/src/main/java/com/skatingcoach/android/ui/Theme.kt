package com.skatingcoach.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF185D67), onPrimary = Color.White,
    primaryContainer = Color(0xFFD8EEF0), onPrimaryContainer = Color(0xFF133E46),
    secondary = Color(0xFF52657A), secondaryContainer = Color(0xFFE3EAF2),
    background = Color(0xFFF7F8F6), surface = Color(0xFFFCFDFC),
    surfaceVariant = Color(0xFFE9EFED), onSurface = Color(0xFF172A30), onSurfaceVariant = Color(0xFF4B5F64),
    outline = Color(0xFF718285), outlineVariant = Color(0xFFD0DCDA),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DD2D8), onPrimary = Color(0xFF06363D),
    primaryContainer = Color(0xFF224E56), onPrimaryContainer = Color(0xFFD8EEF0),
    secondary = Color(0xFFB9CADD), secondaryContainer = Color(0xFF34485D),
    background = Color(0xFF101C20), surface = Color(0xFF152328),
    surfaceVariant = Color(0xFF27393F), onSurface = Color(0xFFE0ECEC), onSurfaceVariant = Color(0xFFB9CBCC),
    outline = Color(0xFF819A9D), outlineVariant = Color(0xFF3C555B),
)
private val AppTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 40.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 23.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
)

@Composable
fun SkatingTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, typography = AppTypography, content = content)
}
