package com.openminis.app.ui.chat

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.agent.jobs.UserQuestionTool
import org.json.JSONObject

internal data class UserQuestionHistory(val request: PendingUserQuestion, val answers: Map<String, String>) {
    companion object {
        fun parse(block: AssistantBlock): UserQuestionHistory? {
            if (block.toolName !in setOf(UserQuestionTool.NAME, UserQuestionTool.ALIAS,
                    UserQuestionTool.LEGACY_ALIAS, UserQuestionTool.STABLE_NAME)) return null
            if (InFlightToolHeal.isInFlight(block.toolStatus)) return null
            // Larger historical payloads retain the regular detail viewer and its bounded preview.
            if (block.toolArgs.length > 64 * 1024 || block.content.length > 64 * 1024) return null
            return runCatching {
                val request = UserQuestionTool.parse(block.id, block.toolArgs)
                val json = runCatching { JSONObject(block.content).optJSONObject("answers") }.getOrNull()
                val answers = request.questions.mapNotNull { question ->
                    (json?.opt(question.id) as? String)?.let { question.id to it }
                }.toMap()
                if (answers.isEmpty() && block.toolStatus != ToolBlockStatus.CANCELLED) null
                else UserQuestionHistory(request, answers)
            }.getOrNull()
        }
    }
}

/** Answered question cards remain readable after a restart, without reactivating the broker. */
@Composable
internal fun UserQuestionHistoryBlock(block: AssistantBlock, onOpenDetail: () -> Unit, fallback: @Composable () -> Unit) {
    val history = remember(block) { UserQuestionHistory.parse(block) }
    if (history == null) { fallback(); return }
    var expanded by rememberSaveable(block.id) { mutableStateOf(false) }
    val resize = remember(block.id) { TopAnchoredResize() }
    val scope = rememberCoroutineScope()
    val anchor = LocalChatToggleAnchor.current
    Column(
        Modifier.fillMaxWidth().then(resize.modifier).onGloballyPositioned { resize.coords = it }
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { resize.toggle(scope, anchor) { expanded = !expanded } }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(ToolPreviewText.heading(history.request.title.ifBlank { block.toolTitle.ifBlank { block.toolName } }),
                modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(if (history.answers.isEmpty()) R.string.user_ask_stopped else R.string.user_ask_answered),
                style = MaterialTheme.typography.labelMedium)
            Icon(if (expanded) Icons.Default.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(if (expanded) R.string.user_ask_show_less else R.string.user_ask_show_more))
        }
        if (expanded) Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (question in history.request.questions) {
                Text(ToolPreviewText.tail(question.question, 12), style = MaterialTheme.typography.bodyMedium)
                history.answers[question.id]?.let { answer ->
                    Text(ToolPreviewText.tail(answer, 12), color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            TextButton(onClick = onOpenDetail) { Text(stringResource(R.string.user_ask_show_more)) }
        }
    }
}
