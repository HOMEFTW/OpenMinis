package com.openminis.app.ui.markdown

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openminis.app.ui.chat.LargeContentBadge

/** Reuse the bounded preview and full-source export; expanding uses lazy text windows. */
@Composable
internal fun BoundedMarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
) {
    if (text.length <= MarkdownWorkLimits.MAX_INLINE_CHARS) {
        Text(text = text, modifier = modifier, color = color, style = style)
        return
    }
    var showFull by remember { mutableStateOf(false) }
    Column(modifier) {
        LargeContentBadge(content = text, stableKey = "markdown", onExpand = { showFull = true })
    }
    if (showFull) {
        Dialog(
            onDismissRequest = { showFull = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    TextButton(onClick = { showFull = false }) {
                        Text(stringResource(android.R.string.ok))
                    }
                    MarkdownPlainTextDocument(text, Modifier.weight(1f), color = color, style = style)
                }
            }
        }
    }
}

@Composable
internal fun MarkdownPlainTextDocument(
    text: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(12.dp),
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
) {
    LazyColumn(modifier = modifier, contentPadding = contentPadding) {
        items(MarkdownWorkLimits.plainTextChunkCount(text)) { index ->
            val chunk = remember(text, index) { MarkdownWorkLimits.plainTextChunk(text, index) }
            Text(text = chunk, color = color, style = style, modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}
