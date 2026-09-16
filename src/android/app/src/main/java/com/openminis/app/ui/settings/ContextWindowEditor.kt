package com.openminis.app.ui.settings

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.ui.theme.MinisTheme

/** The saved global ceiling is separate from the draft and from each model's effective window. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ContextWindowEditor(
    savedTokens: Int,
    draft: String,
    isError: Boolean,
    onDraftChange: (String) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    val parsed = ContextWindowSettings.parse(draft)
    val hasChanges = draft != savedTokens.toString()
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(R.string.settings_context_global_limit), style = MaterialTheme.typography.labelLarge)
                Text(
                    ContextWindowSettings.formatPreset(savedTokens),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.settings_context_current, savedTokens),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Text(
            stringResource(R.string.settings_context_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column {
            Text(stringResource(R.string.settings_context_presets), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ContextWindowSettings.presets.forEach { preset ->
                    FilterChip(
                        selected = parsed == preset,
                        onClick = {
                            focusManager.clearFocus()
                            onDraftChange(preset.toString())
                        },
                        label = {
                            Text(
                                if (preset == ContextWindowSettings.DEFAULT) {
                                    stringResource(R.string.settings_context_default_preset, ContextWindowSettings.formatPreset(preset))
                                } else ContextWindowSettings.formatPreset(preset),
                            )
                        },
                        leadingIcon = if (parsed == preset) {
                            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else null,
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            label = { Text(stringResource(R.string.settings_context_custom)) },
            suffix = { Text(stringResource(R.string.settings_context_unit)) },
            isError = isError,
            supportingText = {
                Text(stringResource(if (isError) R.string.settings_context_invalid else R.string.settings_context_range))
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(if (hasChanges) R.string.settings_context_unsaved else R.string.settings_context_edit_hint),
            style = MaterialTheme.typography.bodySmall,
            color = if (hasChanges) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(
                onClick = {
                    focusManager.clearFocus()
                    onDraftChange(ContextWindowSettings.DEFAULT.toString())
                },
                enabled = draft != ContextWindowSettings.DEFAULT.toString(),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.settings_context_restore_default)) }
            OutlinedButton(
                onClick = { focusManager.clearFocus(); onCancel() },
                enabled = hasChanges,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.common_cancel)) }
            Button(
                onClick = {
                    if (parsed != null) focusManager.clearFocus()
                    onSave()
                },
                enabled = hasChanges,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.common_save)) }
        }
    }
}

@Preview(name = "Light", widthDp = 360, locale = "zh")
@Preview(name = "Dark", widthDp = 360, locale = "zh", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Large text", widthDp = 320, fontScale = 1.5f, locale = "en")
@Composable
private fun ContextWindowEditorPreview() {
    MinisTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            ContextWindowEditor(105_000, "272000", false, {}, {}, {})
        }
    }
}
