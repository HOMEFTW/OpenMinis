package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.model.ChatWallpaper
import kotlin.math.roundToInt

@Composable
internal fun WallpaperTransparencySlider(value: Int, onValueChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val sliderLabel = stringResource(R.string.chat_wallpaper_transparency)
    Column(modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.chat_wallpaper_transparency), style = MaterialTheme.typography.bodyLarge)
            Text("$value%", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value.toFloat(), onValueChange = { onValueChange(it.roundToInt()) }, valueRange = 0f..100f,
            modifier = Modifier.semantics { contentDescription = sliderLabel })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.chat_wallpaper_visible), style = MaterialTheme.typography.labelSmall)
            Text(stringResource(R.string.chat_wallpaper_transparent), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun WallpaperPreview(wallpaper: ChatWallpaper, transparency: Int, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.background)) {
        ChatWallpaperLayer(wallpaper, transparency, Modifier.matchParentSize())
        Text(
            when (wallpaper) { ChatWallpaper.DEEPSEEK -> "DeepSeek"; ChatWallpaper.GPT -> "GPT"; ChatWallpaper.CLAUDE -> "Claude" },
            Modifier.align(Alignment.TopCenter).padding(10.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatWallpaperSheet(
    wallpaper: ChatWallpaper?, transparency: Int, followsDefault: Boolean,
    onTransparencyChange: (Int) -> Unit, onReset: () -> Unit, onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 20.dp)) {
            Text(stringResource(R.string.chat_wallpaper_title), Modifier.padding(horizontal = 20.dp),
                style = MaterialTheme.typography.titleLarge)
            Text(stringResource(if (followsDefault) R.string.chat_wallpaper_following_default else R.string.chat_wallpaper_override),
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (wallpaper != null) WallpaperPreview(wallpaper, transparency,
                Modifier.align(Alignment.CenterHorizontally).width(108.dp).height(200.dp))
            else Text(stringResource(R.string.chat_wallpaper_unavailable), Modifier.padding(horizontal = 20.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            WallpaperTransparencySlider(transparency, onTransparencyChange)
            TextButton(onClick = onReset, enabled = !followsDefault, modifier = Modifier.align(Alignment.End).padding(end = 12.dp)) {
                Text(stringResource(R.string.chat_wallpaper_restore_default))
            }
        }
    }
}
