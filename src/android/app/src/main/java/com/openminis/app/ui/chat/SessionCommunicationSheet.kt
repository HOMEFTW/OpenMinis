package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.service.SessionMailbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SessionCommunicationSheet(sessionId: String, latestReply: String, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as MinisApp
    val opened by produceState<Result<com.openminis.app.service.SessionMessenger>?>(null, app) {
        value = withContext(Dispatchers.IO) { runCatching { app.sessionMessenger } }
    }
    val messenger = opened?.getOrNull()
    if (messenger == null) {
        StandardChatSheet(stringResource(R.string.session_mail_title), onDismiss) {
            if (opened == null) CircularProgressIndicator(modifier = Modifier.padding(20.dp))
            else Text(opened?.exceptionOrNull()?.message ?: "Cannot open mailbox", modifier = Modifier.padding(20.dp))
        }
        return
    }
    val sessions by app.chatRepository.observeSessions().collectAsState(emptyList())
    val allMail by messenger.mailbox.messages.collectAsState()
    val allowed by messenger.mailbox.allowed.collectAsState()
    val deliveryError by messenger.error.collectAsState()
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val sourceExists = sessions.any { it.id == sessionId }
    val records = allMail.filter { it.source == sessionId || it.target == sessionId }.asReversed()

    StandardChatSheet(stringResource(R.string.session_mail_title), onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(stringResource(R.string.session_mail_help), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.session_mail_allow), modifier = Modifier.weight(1f))
                    Switch(
                        checked = sessionId in allowed,
                        enabled = sourceExists,
                        onCheckedChange = { value -> scope.launch {
                            error = runCatching { withContext(Dispatchers.IO) { messenger.mailbox.setAllowed(sessionId, value) } }.exceptionOrNull()?.message
                        } },
                    )
                }
                if (!sourceExists) Text(stringResource(R.string.session_mail_save_first), color = MaterialTheme.colorScheme.error)
            }
            item {
                Box {
                    OutlinedButton(onClick = { picking = true }, enabled = sourceExists && !sending, modifier = Modifier.fillMaxWidth()) {
                        Text(sessions.find { it.id == target }?.let { it.title ?: it.id } ?: stringResource(R.string.session_mail_pick))
                    }
                    DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                        sessions.filter { it.id != sessionId }.forEach { session ->
                            DropdownMenuItem(
                                text = { Text(session.title ?: session.id, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                onClick = { target = session.id; picking = false },
                            )
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    label = { Text(stringResource(R.string.session_mail_content)) },
                    supportingText = { Text("${text.length} / ${SessionMailbox.MAX_TEXT}") },
                    isError = text.length > SessionMailbox.MAX_TEXT,
                    minLines = 3,
                    maxLines = 8,
                    enabled = !sending,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { text = latestReply }, enabled = latestReply.isNotBlank() && !sending) {
                    Text(stringResource(R.string.session_mail_forward_latest))
                }
                Button(
                    enabled = sourceExists && sessions.any { it.id == target } && target != sessionId &&
                        text.isNotBlank() && text.length <= SessionMailbox.MAX_TEXT && !sending,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        sending = true
                        scope.launch {
                            try {
                                messenger.send(sessionId, target, text, false)
                                text = ""
                                error = null
                            } catch (e: Exception) { error = e.message }
                            finally { sending = false }
                        }
                    },
                ) { Text(stringResource(R.string.session_mail_send)) }
                (error ?: deliveryError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            item { Text(stringResource(R.string.session_mail_history), style = MaterialTheme.typography.titleMedium) }
            if (records.isEmpty()) item { Text(stringResource(R.string.session_mail_empty)) }
            items(records, key = { it.id }) { mail ->
                var expanded by remember(mail.id) { mutableStateOf(false) }
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val peerId = if (mail.source == sessionId) mail.target else mail.source
                        val peer = sessions.find { it.id == peerId }?.title ?: peerId
                        Text(stringResource(if (mail.source == sessionId) R.string.session_mail_to else R.string.session_mail_from, peer),
                            style = MaterialTheme.typography.labelLarge)
                        val status = when (mail.status) {
                            "queued" -> R.string.session_mail_queued
                            "running" -> R.string.session_mail_running
                            "completed" -> R.string.session_mail_completed
                            "interrupted" -> R.string.session_mail_interrupted
                            else -> R.string.session_mail_failed
                        }
                        Text(stringResource(status), color = MaterialTheme.colorScheme.primary)
                        SelectionContainer {
                            Text(mail.text, maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
                        }
                        if (mail.reply.isNotEmpty()) {
                            Text(stringResource(R.string.session_mail_reply), style = MaterialTheme.typography.labelLarge)
                            SelectionContainer {
                                Text(mail.reply, maxLines = if (expanded) Int.MAX_VALUE else 6, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        TextButton(onClick = { expanded = !expanded }) {
                            Text(stringResource(if (expanded) R.string.session_mail_collapse else R.string.session_mail_expand))
                        }
                        if (mail.status != "running") TextButton(onClick = { scope.launch {
                            error = runCatching { withContext(Dispatchers.IO) { messenger.mailbox.remove(mail.id, sessionId) } }.exceptionOrNull()?.message
                        } }) { Text(stringResource(if (mail.status == "queued") R.string.session_mail_cancel else R.string.session_mail_remove)) }
                    }
                }
            }
        }
    }
}
