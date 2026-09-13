package com.openminis.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.TaskBudgetPrefs

@Composable
internal fun TaskBudgetDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableIntStateOf(TaskBudgetPrefs.limit(context)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.task_budget_title)) },
        text = {
            Column {
                Text(stringResource(R.string.task_budget_description))
                TaskBudgetPrefs.options.forEach { rounds ->
                    Row(Modifier.fillMaxWidth().clickable { selected = rounds }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected == rounds, onClick = { selected = rounds })
                        Text(stringResource(R.string.task_budget_rounds, rounds))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { TaskBudgetPrefs.save(context, selected); onDismiss() }) { Text(stringResource(R.string.task_budget_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.task_artifacts_close)) } },
    )
}
