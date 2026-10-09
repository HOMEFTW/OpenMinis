package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.network.EndpointCertificates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun EndpointCertificateSettings(baseUrl: String) {
    var pem by remember(baseUrl) { mutableStateOf(EndpointCertificates.get(baseUrl)) }
    var result by remember(baseUrl) { mutableStateOf<Int?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    SettingsSection(
        header = stringResource(R.string.provider_ca_title),
        footer = stringResource(R.string.provider_ca_description),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            OutlinedTextField(
                value = pem,
                onValueChange = { pem = it; result = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.provider_ca_pem)) },
                minLines = 2, maxLines = 5, enabled = !saving,
            )
            TextButton(enabled = !saving, onClick = {
                saving = true
                val value = pem
                scope.launch {
                    val success = withContext(Dispatchers.IO) {
                        runCatching { EndpointCertificates.save(baseUrl, value) }.isSuccess
                    }
                    result = if (success) R.string.provider_ca_saved else R.string.provider_ca_invalid
                    saving = false
                }
            }) { Text(stringResource(R.string.common_save)) }
            result?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
        }
    }
}
