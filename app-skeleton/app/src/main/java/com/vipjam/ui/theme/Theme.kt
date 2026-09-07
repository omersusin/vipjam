package com.vipjam.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object StudioPalette {
    val RackBlack = Color(0xFF0B0D10)
    val RackPanel = Color(0xFF14181D)
    val RackPanelHi = Color(0xFF1B2129)
    val RackLine = Color(0xFF262E38)
    val VuAmber = Color(0xFFFFB224)
    val VuAmberDim = Color(0xFF8A5F12)
    val SignalTeal = Color(0xFF4FD1C5)
    val ClipRed = Color(0xFFFF6B5E)
    val BoneText = Color(0xFFE8E6E1)
    val DimText = Color(0xFF9AA3AD)
    val PaperBg = Color(0xFFF4F2ED)
    val PaperPanel = Color(0xFFFFFFFF)
    val PaperLine = Color(0xFFE0DCD2)
    val InkText = Color(0xFF16181B)
}

private val StudioDarkColors = darkColorScheme(
    primary = StudioPalette.VuAmber,
    onPrimary = Color.Black,
    primaryContainer = StudioPalette.VuAmberDim,
    onPrimaryContainer = StudioPalette.BoneText,
    secondary = StudioPalette.SignalTeal,
    onSecondary = Color.Black,
    tertiary = StudioPalette.SignalTeal,
    background = StudioPalette.RackBlack,
    onBackground = StudioPalette.BoneText,
    surface = StudioPalette.RackPanel,
    onSurface = StudioPalette.BoneText,
    surfaceVariant = StudioPalette.RackPanelHi,
    onSurfaceVariant = StudioPalette.DimText,
    surfaceContainer = StudioPalette.RackPanel,
    surfaceContainerHigh = StudioPalette.RackPanelHi,
    outline = StudioPalette.RackLine,
    outlineVariant = StudioPalette.RackLine,
    error = StudioPalette.ClipRed,
    onError = Color.Black
)

private val StudioLightColors = lightColorScheme(
    primary = Color(0xFF9A6200),
    onPrimary = Color.White,
    secondary = Color(0xFF0E7C72),
    background = StudioPalette.PaperBg,
    onBackground = StudioPalette.InkText,
    surface = StudioPalette.PaperPanel,
    onSurface = StudioPalette.InkText,
    surfaceVariant = StudioPalette.PaperBg,
    onSurfaceVariant = Color(0xFF5B6068),
    surfaceContainer = StudioPalette.PaperPanel,
    surfaceContainerHigh = StudioPalette.PaperBg,
    outline = StudioPalette.PaperLine,
    outlineVariant = StudioPalette.PaperLine,
    error = Color(0xFFC62F22)
)

private val StudioType = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.5).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.8.sp
    )
)

private val StudioShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
)

@Composable
fun VipJamTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) StudioDarkColors else StudioLightColors,
        typography = StudioType,
        shapes = StudioShapes,
        content = content
    )
}
