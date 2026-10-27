package com.cloudgallery.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val GalleryBlue: Color @Composable get() = MaterialTheme.colorScheme.primary
internal val GalleryPaper: Color @Composable get() = MaterialTheme.colorScheme.background
internal val GalleryInk: Color @Composable get() = MaterialTheme.colorScheme.onSurface
internal val GalleryMuted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

@Composable
internal fun GalleryTheme(theme: String = "system", content: @Composable () -> Unit) {
    val dark = theme == "dark" || theme == "system" && androidx.compose.foundation.isSystemInDarkTheme()
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(view, dark) {
        (view.context as? android.app.Activity)?.let { activity ->
            androidx.core.view.WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        onDispose { }
    }
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme(primary = Color(0xFF9BB9FF), onPrimary = Color(0xFF102A60),
            background = Color(0xFF10151F), surface = Color(0xFF171E2B), surfaceContainer = Color(0xFF202938),
            onSurface = Color(0xFFE8EDF7), onBackground = Color(0xFFE8EDF7), onSurfaceVariant = Color(0xFFAAB6CA)) else lightColorScheme(
            primary = Color(0xFF356AE6), onPrimary = Color.White,
            primaryContainer = Color(0xFFE7EEFF), onPrimaryContainer = Color(0xFF356AE6),
            secondary = Color(0xFF737E91), secondaryContainer = Color(0xFFE7EEFF),
            onSecondaryContainer = Color(0xFF356AE6), background = Color(0xFFF5F7FB), onBackground = Color(0xFF202735),
            surface = Color(0xFFF5F7FB), surfaceContainer = Color.White,
            surfaceContainerHigh = Color(0xFFF0F3F9), onSurface = Color(0xFF202735),
            onSurfaceVariant = Color(0xFF737E91), outline = Color(0xFFB8C1D2),
            outlineVariant = Color(0xFFE4E9F2)),
        typography = Typography(
            headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
            headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
            titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
            titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)),
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp)),
        content = {
            androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) { content() }
        })
}

/** Translucent layers and a highlight rim, without expensive per-frame backdrop capture. */
@Composable
internal fun GlassSurface(modifier: Modifier = Modifier, corner: Int = 28, content: @Composable () -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(corner.dp), color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .94f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shadowElevation = 4.dp) {
        Box {
            content()
        }
    }
}

@Composable
internal fun GalleryBackground(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(GalleryPaper)) {
        Box(Modifier.fillMaxWidth().height(300.dp).background(Brush.verticalGradient(
            listOf(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .35f), GalleryPaper))))
        content()
    }
}

internal fun albumTitle(path: String): String = path.trimEnd('/').substringAfterLast('/').ifEmpty { "全部照片" }
