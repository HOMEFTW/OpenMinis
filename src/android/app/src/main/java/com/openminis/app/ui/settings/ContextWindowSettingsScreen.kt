package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openminis.app.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextWindowSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val initialValue = remember(context) { ContextWindowSettings.initialize(context) }
    var savedTokens by remember(context) { mutableStateOf(initialValue) }
    var draft by remember(context) { mutableStateOf(initialValue.toString()) }
    var isError by remember(context) { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val discardDraft = {
        draft = savedTokens.toString()
        isError = false
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    // Preserve the existing rule: only Save persists; leaving discards edits.
    DisposableEffect(lifecycleOwner, savedTokens) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) discardDraft()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_context_size)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
        ) {
            ContextWindowEditor(
                savedTokens = savedTokens,
                draft = draft,
                isError = isError,
                onDraftChange = { draft = it; isError = false },
                onCancel = discardDraft,
                onSave = {
                    val parsed = ContextWindowSettings.parse(draft)
                    if (parsed == null) {
                        isError = true
                    } else {
                        ContextWindowSettings.set(context, parsed)
                        savedTokens = ContextWindowSettings.get(context)
                        discardDraft()
                        scope.launch { snackbar.showSnackbar(context.getString(R.string.settings_context_saved)) }
                    }
                },
            )
        }
    }
}
