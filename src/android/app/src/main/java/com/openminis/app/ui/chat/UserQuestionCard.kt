package com.openminis.app.ui.chat

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import com.openminis.app.R
import kotlinx.coroutines.delay

@Composable
internal fun UserQuestionCard(
    request: PendingUserQuestion,
    onSelect: (String, String, Boolean) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    val question = request.currentQuestion ?: return
    val selection = request.selections[question.id]
    val remainingMs = userQuestionRemainingMs(request)
    val enabled = remainingMs > 0L
    // Let the chat's LazyColumn own vertical scrolling. A second scrollable
    // inside the card fought its parent at the edges, especially on long prompts.
    Column(
        modifier = Modifier.fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                request.title.ifBlank { stringResource(R.string.chat_question_title) },
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
            )
            Text(com.openminis.app.service.chipTimerText(remainingMs), style = MaterialTheme.typography.labelMedium)
        }
        Text(
            stringResource(R.string.chat_question_progress, request.currentIndex + 1, request.questions.size),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (question.header.isNotBlank()) Text(question.header, style = MaterialTheme.typography.labelLarge)
        Text(question.question, style = MaterialTheme.typography.bodyMedium)
        question.options.forEachIndexed { index, option ->
            val picked = selection?.isOther == false && selection.value == option.label
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .background(if (picked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable(enabled = enabled) { onSelect(question.id, option.label, false) }
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = picked, onClick = { onSelect(question.id, option.label, false) }, enabled = enabled)
                Column(Modifier.weight(1f)) {
                    Text(questionOptionLabel(question, index), style = MaterialTheme.typography.bodyMedium)
                    if (option.description.isNotBlank()) {
                        Text(option.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (question.options.isEmpty() || question.allowOther) {
            val draft = request.freeTextDrafts[question.id].orEmpty()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (question.options.isNotEmpty()) {
                    RadioButton(
                        selected = selection?.isOther == true,
                        onClick = { onSelect(question.id, draft, true) }, enabled = enabled,
                    )
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { onSelect(question.id, it, true) },
                    label = { Text(stringResource(if (question.options.isEmpty()) R.string.chat_question_answer else R.string.chat_question_other)) },
                    enabled = enabled, modifier = Modifier.weight(1f), minLines = 1, maxLines = 5,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onCancel, enabled = enabled) { Text(stringResource(R.string.chat_question_cancel)) }
            TextButton(enabled = enabled && !selection?.value.isNullOrBlank(), onClick = onSubmit) {
                Text(stringResource(if (request.currentIndex == request.questions.lastIndex) R.string.chat_question_submit else R.string.chat_question_next))
            }
        }
    }
}

@Composable
internal fun UserQuestionMiniature(request: PendingUserQuestion) {
    val question = request.currentQuestion ?: request.questions.lastOrNull() ?: return
    val selection = request.selections[question.id]
    Column(Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(request.title.ifBlank { stringResource(R.string.chat_question_title) }, fontSize = 7.sp, lineHeight = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = androidx.compose.ui.graphics.Color.White)
        Text(
            stringResource(R.string.chat_question_progress, (request.currentIndex.takeIf { it >= 0 } ?: request.questions.lastIndex) + 1, request.questions.size),
            fontSize = 5.5.sp, lineHeight = 6.sp, color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f),
        )
        Text(question.question, fontSize = 6.sp, lineHeight = 7.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = androidx.compose.ui.graphics.Color.White)
        question.options.take(3).forEachIndexed { index, option ->
            val picked = selection?.isOther == false && selection.value == option.label
            Text("${if (picked) "●" else "○"} ${questionOptionLabel(question, index)}", fontSize = 5.5.sp, lineHeight = 6.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (picked) androidx.compose.ui.graphics.Color(0xFF9ED7FF) else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f))
        }
        if (selection?.isOther == true || question.options.isEmpty()) {
            Text(selection?.value.orEmpty().ifBlank { stringResource(R.string.chat_question_other) }, fontSize = 5.5.sp, lineHeight = 6.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = androidx.compose.ui.graphics.Color(0xFF9ED7FF))
        }
    }
}

@Composable
internal fun questionOptionLabel(question: UserQuestionPrompt, index: Int): String =
    if (question.kind == UserQuestionKind.CONFIRMATION) stringResource(if (index == 0) R.string.chat_question_allow else R.string.chat_question_cancel)
    else question.options[index].label

@Composable
private fun userQuestionRemainingMs(request: PendingUserQuestion): Long {
    val remaining by produceState(request.remainingMs(android.os.SystemClock.elapsedRealtime()), request.instanceId, request.expiresAtElapsedMs) {
        while (value > 0L) {
            delay(1000L)
            value = request.remainingMs(android.os.SystemClock.elapsedRealtime())
        }
    }
    return remaining
}
