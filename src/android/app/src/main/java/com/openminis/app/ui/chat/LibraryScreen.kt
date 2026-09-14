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
import com.openminis.app.R
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.ui.sandbox.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive

private data class LibraryArtifact(val sessionId: String, val title: String, val artifact: TaskArtifact)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryScreen(repository: ChatRepository, tasks: Boolean, onBack: () -> Unit,
    onSession: (String) -> Unit, onPreview: (FileItem) -> Unit) {
    val context = LocalContext.current
    val entries by TaskCenter.entries.collectAsState()
    val pending by com.openminis.app.config.confirm.ConfigConfirmationGate.pending.collectAsState()
    var query by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    var artifacts by remember { mutableStateOf<List<LibraryArtifact>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var incomplete by remember { mutableStateOf(false) }
    LaunchedEffect(tasks, revision) {
        if (tasks) return@LaunchedEffect
        busy = true
        try {
            val result = withContext(Dispatchers.IO) {
                val sessions = repository.dao.listSessions()
                var limited = sessions.size > 100
                val found = mutableListOf<LibraryArtifact>()
                for (session in sessions.take(100)) {
                    ensureActive()
                    val listing = runCatching { TaskArtifacts.list(context.filesDir, session.id) }.getOrNull()
                    if (listing == null) { limited = true; continue }
                    limited = limited || listing.limited
                    found += listing.files.map { LibraryArtifact(session.id, session.title.orEmpty(), it) }
                    if (found.size >= 2000) { limited = true; break }
                }
                found.sortedByDescending { it.artifact.modified }.take(2000) to limited
            }
            artifacts = result.first; incomplete = result.second
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { incomplete = true; artifacts = emptyList() }
        finally { busy = false }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(if (tasks) R.string.daily_tasks else R.string.daily_library)) },
            navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.daily_back)) } },
            actions = { if (!tasks) TextButton(onClick = { revision++ }) { Text(stringResource(R.string.task_artifacts_refresh)) } })
    }) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 16.dp).fillMaxSize()) {
            OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.daily_search)) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            if (tasks) {
                Text(stringResource(R.string.daily_tasks_scope), style = MaterialTheme.typography.bodySmall)
                if (pending != null) Text(stringResource(R.string.daily_waiting), color = MaterialTheme.colorScheme.primary)
                val visible = entries.filter { query.isBlank() || it.title.contains(query, true) || it.sessionId.contains(query, true) }
                    .sortedWith(compareBy<TaskCenterEntry> { it.run.status.isTerminal }.thenByDescending { it.run.startedAtMs })
                if (visible.isEmpty()) Text(stringResource(R.string.daily_empty))
                LazyColumn {
                    items(visible, key = { it.sessionId + "/" + it.run.runId }) { entry ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                            Text(entry.title.ifBlank { entry.sessionId }, modifier = Modifier.clickable { onSession(entry.sessionId) })
                            Text(stringResource(when (entry.run.status) {
                                ChatRunStatus.Running -> R.string.daily_running
                                ChatRunStatus.Queued -> R.string.daily_queued
                                ChatRunStatus.Completed -> R.string.daily_completed
                                ChatRunStatus.Cancelled -> R.string.daily_cancelled
                                ChatRunStatus.Timeout -> R.string.daily_timeout
                                ChatRunStatus.BudgetExceeded -> R.string.daily_budget
                                else -> R.string.daily_failed
                            }), style = MaterialTheme.typography.labelLarge)
                            entry.run.error?.let { Text(stringResource(FailureAdvice.classify(it).messageRes), style = MaterialTheme.typography.bodySmall) }
                            Row {
                                TextButton(onClick = { onSession(entry.sessionId) }) { Text(stringResource(R.string.daily_source)) }
                                if (!entry.run.status.isTerminal) TextButton(onClick = { TaskCenter.stop(entry.sessionId) }) { Text(stringResource(R.string.daily_stop)) }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            } else {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (incomplete) Text(stringResource(R.string.daily_library_limited), style = MaterialTheme.typography.bodySmall)
                val visible = artifacts.filter { query.isBlank() || it.artifact.relativePath.contains(query, true) || it.title.contains(query, true) }
                if (!busy && visible.isEmpty()) Text(stringResource(R.string.daily_empty))
                LazyColumn {
                    items(visible, key = { it.sessionId + "/" + it.artifact.relativePath }) { entry ->
                        val a = entry.artifact
                        val item = FileItem(a.file, a.file.name, false, false, a.size, a.modified)
                        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                            Text(a.file.name, modifier = Modifier.clickable { onPreview(item) }, style = MaterialTheme.typography.titleSmall)
                            Text(entry.title + " · " + android.text.format.Formatter.formatShortFileSize(context, a.size), style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(onClick = { onPreview(item) }) { Text(stringResource(R.string.daily_preview_save)) }
                                TextButton(onClick = { com.openminis.app.ui.sandbox.shareFile(context, item) }) { Text(stringResource(R.string.task_artifacts_share)) }
                                TextButton(onClick = { onSession(entry.sessionId) }) { Text(stringResource(R.string.daily_source)) }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
