package com.vipjam.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val FallbackLightColors = lightColorScheme()
private val FallbackDarkColors = darkColorScheme()
private val VipJamTypography = Typography()
private val VipJamShapes = Shapes()

@Composable
fun VipJamTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (darkTheme) {
            dynamicDarkColorScheme(context)
        } else {
            dynamicLightColorScheme(context)
        }
        darkTheme -> FallbackDarkColors
        else -> FallbackLightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = VipJamTypography,
        shapes = VipJamShapes,
        content = content
    )
}
