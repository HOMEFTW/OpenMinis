package com.openminis.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.openminis.app.R
import com.openminis.app.ui.sandbox.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun TaskArtifactsDialog(sessionId: String, onDismiss: () -> Unit, onPreview: (FileItem) -> Unit) {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    var listing by remember(sessionId) { mutableStateOf<TaskArtifactListing?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(sessionId, revision) {
        listing = null; failed = false
        try {
            listing = withContext(Dispatchers.IO) { TaskArtifacts.list(context.filesDir, sessionId) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { failed = true }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
            Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).padding(16.dp)) {
                Text(stringResource(R.string.task_artifacts_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.task_artifacts_description), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                when {
                    failed -> Text(stringResource(R.string.task_artifacts_failed))
                    listing == null -> CircularProgressIndicator()
                    listing!!.files.isEmpty() -> Text(stringResource(R.string.task_artifacts_empty))
                    else -> LazyColumn(Modifier.weight(1f, fill = false)) {
                        items(listing!!.files, key = { it.relativePath }) { artifact ->
                            val item = FileItem(artifact.file, artifact.file.name, false, false, artifact.size, artifact.modified)
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Column(Modifier.weight(1f).clickable { onDismiss(); onPreview(item) }) {
                                    Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    Text(artifact.relativePath, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                    val type = item.file.extension.takeIf { it.isNotEmpty() }?.let {
                                        stringResource(R.string.task_artifacts_file_type, it.uppercase(java.util.Locale.ROOT))
                                    } ?: stringResource(R.string.task_artifacts_generic_type)
                                    Text("$type · ${android.text.format.Formatter.formatShortFileSize(context, item.size)}", style = MaterialTheme.typography.labelSmall)
                                }
                                TextButton(onClick = { com.openminis.app.ui.sandbox.shareFile(context, item) }) {
                                    Text(stringResource(R.string.task_artifacts_share))
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
                if (listing?.limited == true) Text(stringResource(R.string.task_artifacts_limited), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { revision++ }) { Text(stringResource(R.string.task_artifacts_refresh)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.task_artifacts_close)) }
                }
            }
        }
    }
}
