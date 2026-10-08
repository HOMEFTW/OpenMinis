package com.openminis.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.data.model.ModelEntry

@Composable
internal fun ModelAvailabilityHint(entry: ModelEntry, modifier: Modifier = Modifier) {
    if (entry.isUnavailableFromProvider) {
        Text(
            text = stringResource(R.string.model_not_listed_by_provider),
            modifier = modifier,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
