package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.data.model.ChatWallpaper
import com.openminis.app.ui.chat.WallpaperPreview
import com.openminis.app.ui.chat.WallpaperTransparencySlider

@Composable
internal fun ChatWallpaperSettingsSection() {
    val repository = (LocalContext.current.applicationContext as MinisApp).chatWallpaperRepository
    val state by repository.state.collectAsState()
    SettingsSection(header = stringResource(R.string.chat_wallpaper_title), footer = stringResource(R.string.chat_wallpaper_default_hint)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChatWallpaper.entries.forEach { wallpaper ->
                WallpaperPreview(wallpaper, state.defaultTransparency, Modifier.weight(1f).height(176.dp))
            }
        }
        WallpaperTransparencySlider(state.defaultTransparency, repository::setDefaultTransparency)
    }
}
