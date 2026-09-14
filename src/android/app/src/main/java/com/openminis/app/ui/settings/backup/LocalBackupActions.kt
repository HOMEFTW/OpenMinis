package com.openminis.app.ui.settings.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.ui.components.MinisOutlinedButton
import java.io.File

@Composable
internal fun LocalBackupActions(file: File, vm: BackupViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val context = LocalContext.current
    val savingLocal by vm.savingLocal.collectAsState()
    val localSaveMessage by vm.localSaveMessage.collectAsState()
    var pendingSavePath by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var shareError by remember { mutableStateOf<String?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val source = pendingSavePath
        pendingSavePath = null
        if (uri != null && source != null) vm.saveLocalPackage(source, uri)
    }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(stringResource(R.string.pr_backup_local_hint), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MinisOutlinedButton(enabled = !savingLocal && pendingSavePath == null, onClick = {
                pendingSavePath = file.absolutePath
                saveLauncher.launch(file.name)
            }) { Text(stringResource(R.string.pr_backup_save)) }
            MinisOutlinedButton(enabled = !savingLocal, onClick = {
                try {
                    check(file.isFile) { "Backup file is no longer available." }
                    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "application/octet-stream"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        clipData = android.content.ClipData.newRawUri("backup", uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(android.content.Intent.createChooser(intent, null))
                    shareError = null
                } catch (e: Exception) { shareError = e.message }
            }) { Text(stringResource(R.string.pr_backup_share)) }
        }
        if (savingLocal) Text(stringResource(R.string.pr_backup_saving))
        localSaveMessage?.let { Text(it) }
        shareError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
