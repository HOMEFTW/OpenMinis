package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import com.openminis.app.ui.settings.confirmBeforeStoppingEnabled
import com.openminis.app.R
import com.openminis.app.ui.components.MinisAlertDialog

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun StopChatConfirmation(onDismiss: () -> Unit, onConfirm: () -> Unit, onStopCurrent: (() -> Unit)? = null) {
    if (onStopCurrent != null) {
        ModalBottomSheet(onDismissRequest = onDismiss) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                TextButton(onClick = onStopCurrent, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.build34_stop_current))
                }
                TextButton(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.build34_stop_all), color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
        return
    }
    if (!confirmBeforeStoppingEnabled(LocalContext.current)) {
        LaunchedEffect(Unit) { onConfirm() }
        return
    }
    MinisAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.chat_stop_dialog_title),
        text = stringResource(R.string.chat_stop_dialog_body),
        confirmText = stringResource(R.string.chat_stop_dialog_confirm),
        isDestructive = true,
        onConfirm = onConfirm,
    )
}

@Composable
internal fun AgentSteerDialog(
    jobId: String,
    onDismiss: () -> Unit,
    onSteer: (String) -> Boolean,
) {
    var message by rememberSaveable(jobId) { mutableStateOf("") }
    var rejected by rememberSaveable(jobId) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.helper_steer)) },
        text = {
            OutlinedTextField(
                value = message,
                onValueChange = { message = it; rejected = false },
                label = { Text(stringResource(R.string.helper_steer_message)) },
                supportingText = if (rejected) ({
                    Text(stringResource(R.string.helper_steer_failed))
                }) else null,
                isError = rejected,
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8,
            )
        },
        confirmButton = {
            TextButton(
                enabled = message.isNotBlank(),
                onClick = { if (onSteer(message.trim())) onDismiss() else rejected = true },
            ) { Text(stringResource(R.string.helper_steer_send)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
