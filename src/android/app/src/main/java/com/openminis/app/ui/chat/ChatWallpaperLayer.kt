package com.openminis.app.ui.chat

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.openminis.app.R
import com.openminis.app.data.model.ChatWallpaper

/** Decorative only: no pointer/semantics handlers, no dependency on streaming message content. */
@Composable
internal fun ChatWallpaperLayer(wallpaper: ChatWallpaper?, transparency: Int, modifier: Modifier = Modifier) {
    if (transparency >= 100) return
    Crossfade(
        targetState = wallpaper,
        modifier = modifier.graphicsLayer { alpha = 1f - transparency.coerceIn(0, 100) / 100f },
        animationSpec = tween(220),
        label = "chatWallpaper",
    ) { current ->
        if (current != null) {
            val (drawable, canvasColor) = when (current) {
                ChatWallpaper.DEEPSEEK -> R.drawable.chat_wallpaper_deepseek to Color(0xFFFEFBF5)
                ChatWallpaper.GPT -> R.drawable.chat_wallpaper_gpt to Color(0xFFFEFBF8)
                ChatWallpaper.CLAUDE -> R.drawable.chat_wallpaper_claude to Color(0xFFFDFAF3)
            }
            // Match the artwork's flat canvas so Fit preserves the full character on tablets
            // and in landscape without showing a differently colored letterbox.
            Box(Modifier.fillMaxSize().background(canvasColor)) {
                AsyncImage(
                    model = drawable,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.BottomCenter,
                )
            }
        }
    }
}
