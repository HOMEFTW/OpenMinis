package com.openminis.app.ui.chat

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R

@Composable
internal fun UserQuestionCard(
    request: PendingUserQuestion,
    onAnswer: (Map<String, String>) -> Boolean,
) {
    var selected by rememberSaveable(request.id) { mutableStateOf(mapOf<String, Int>()) }
    var freeText by rememberSaveable(request.id) { mutableStateOf(mapOf<String, String>()) }
    var submitted by rememberSaveable(request.id) { mutableStateOf(false) }
    val answers = userQuestionAnswers(request, selected, freeText)
    Column(
        modifier = Modifier.fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.chat_question_title), style = MaterialTheme.typography.titleSmall)
        request.questions.forEach { question ->
            if (question.header.isNotBlank()) {
                Text(question.header, style = MaterialTheme.typography.labelLarge)
            }
            Text(question.question, style = MaterialTheme.typography.bodyMedium)
            question.options.forEachIndexed { index, option ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !submitted) {
                        selected = selected + (question.id to index)
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selected[question.id] == index,
                        onClick = { selected = selected + (question.id to index) },
                        enabled = !submitted,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(option.label, style = MaterialTheme.typography.bodyMedium)
                        if (option.description.isNotBlank()) {
                            Text(
                                option.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (question.options.isEmpty() || question.allowOther) {
                OutlinedTextField(
                    value = freeText[question.id].orEmpty(),
                    onValueChange = {
                        freeText = freeText + (question.id to it)
                        selected = selected + (question.id to -1)
                    },
                    label = { Text(stringResource(R.string.chat_question_other)) },
                    enabled = !submitted,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 1,
                    maxLines = 5,
                )
            }
        }
        TextButton(
            enabled = answers != null && !submitted,
            onClick = { answers?.let { submitted = onAnswer(it) } },
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(stringResource(R.string.chat_question_submit))
        }
    }
}
