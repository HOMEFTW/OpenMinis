package com.openminis.app.ui.chat

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DraftsScreen(repository: ChatRepository, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    var drafts by remember { mutableStateOf<List<Pair<String, ComposerDraft>>>(emptyList()) }
    var titles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
        withContext(Dispatchers.IO) {
            val sessions = repository.dao.listSessions().associate { it.id to it.title.orEmpty() }
            val saved = ComposerDraftStore(context).list().filterKeys { it.startsWith("__new__") || it in sessions }
            sessions to saved.toList()
        }.let { titles = it.first; drafts = it.second }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { failed = true }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.daily_drafts)) },
            navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.daily_back)) } })
    }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(16.dp)) {
            if (failed) item { Text(stringResource(R.string.daily_load_failed)) }
            if (!failed && drafts.isEmpty()) item { Text(stringResource(R.string.daily_empty)) }
            items(drafts, key = { it.first }) { (id, draft) ->
                Text(titles[id]?.takeIf { it.isNotBlank() } ?: stringResource(R.string.daily_new_draft), style = MaterialTheme.typography.titleMedium)
                Text(draft.text, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(stringResource(R.string.daily_draft_attachments, draft.attachments.size))
                TextButton(onClick = { onOpen(id) }) { Text(stringResource(R.string.daily_draft_continue)) }
                HorizontalDivider()
            }
        }
    }
}
