package com.openminis.app.ui.settings

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openminis.app.R
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.ui.components.MinisTextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val PROVIDER_CALL_CHECK_TIMEOUT_MS = 120_000L

/** Models that can answer the short text request used by the provider check. */
internal fun textCallCheckEntries(entries: List<ModelEntry>): List<ModelEntry> =
    entries.filter { entry ->
        if (entry.isHidden) return@filter false
        val outputs = entry.model.outputModalities.orEmpty()
        outputs.isEmpty() || outputs.any { it.equals("text", ignoreCase = true) }
    }

internal sealed interface ProviderCallCheckResult {
    data class Success(val output: String, val elapsedMs: Long) : ProviderCallCheckResult
    data class Failure(val message: String, val elapsedMs: Long = 0L) : ProviderCallCheckResult
}

/**
 * Execute exactly one short text request against the selected model.
 *
 * The provider factory and clock are injectable so the request contract can be
 * tested without Android storage, a live endpoint, or real credentials.
 */
internal suspend fun runProviderCallCheck(
    instance: ProviderInstance,
    entry: ModelEntry,
    apiKey: String?,
    context: Context?,
    createProvider: (ProviderInstance, String, LLMModel, Context?) -> LLMProvider =
        { providerInstance, key, model, providerContext ->
            ProviderFactory.create(providerInstance, key, model, providerContext)
        },
    nowMs: () -> Long = { SystemClock.elapsedRealtime() },
): ProviderCallCheckResult {
    val startedAt = nowMs()
    fun elapsed(): Long = (nowMs() - startedAt).coerceAtLeast(0L)

    val key = apiKey ?: return ProviderCallCheckResult.Failure(
        message = "No API key configured for this provider.",
    )

    return try {
        val provider = createProvider(instance, key, entry.model, context)
        val response = try {
            withTimeout(PROVIDER_CALL_CHECK_TIMEOUT_MS) {
                provider.sendMessage(
                    messages = listOf(
                        LLMMessage(
                            role = LLMMessage.Role.USER,
                            content = "Reply OK in one short sentence.",
                        ),
                    ),
                    systemPrompt = null,
                    maxTokens = 1024,
                    temperature = null,
                )
            }
        } catch (_: TimeoutCancellationException) {
            return ProviderCallCheckResult.Failure(
                message = "Request timed out after 120 seconds.",
                elapsedMs = elapsed(),
            )
        }
        val output = response.text.trim()
        if (output.isEmpty()) {
            ProviderCallCheckResult.Failure(
                message = "Provider returned an empty response.",
                elapsedMs = elapsed(),
            )
        } else {
            ProviderCallCheckResult.Success(
                output = output,
                elapsedMs = elapsed(),
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ProviderCallCheckResult.Failure(
            message = e.message?.takeIf { it.isNotBlank() } ?: "Request failed.",
            elapsedMs = elapsed(),
        )
    }
}

/** Guards the UI against duplicate taps and stale completions after cancel. */
internal data class ProviderCallCheckState(
    val modelId: String,
    val result: ProviderCallCheckResult,
)

internal class ProviderCallCheckController {
    private var nextToken = 0L
    private var activeTokenValue: Long? = null
    private var activeModelId: String? = null

    @Synchronized
    fun begin(modelId: String): Long? {
        if (activeTokenValue != null) return null
        val token = ++nextToken
        activeTokenValue = token
        activeModelId = modelId
        return token
    }

    @Synchronized
    fun cancel(token: Long): Boolean {
        if (activeTokenValue != token) return false
        activeTokenValue = null
        activeModelId = null
        return true
    }

    @Synchronized
    fun complete(token: Long, result: ProviderCallCheckResult): ProviderCallCheckState? {
        if (activeTokenValue != token) return null
        val modelId = activeModelId ?: return null
        activeTokenValue = null
        activeModelId = null
        return ProviderCallCheckState(modelId, result)
    }

    @get:Synchronized
    val activeToken: Long?
        get() = activeTokenValue
}

@Composable
internal fun ProviderCallCheckModelPicker(
    entries: List<ModelEntry>,
    onSelect: (ModelEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 440.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 24.dp, bottom = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.provider_call_check_select_model),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(12.dp))
                entries.forEachIndexed { index, entry ->
                    SettingsRow(
                        title = entry.model.displayName,
                        subtitle = entry.model.id,
                        onClick = { onSelect(entry) },
                        showDivider = index != entries.lastIndex,
                        minHeight = 72.dp,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    MinisTextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            }
        }
    }
}

private sealed interface ProviderCallCheckUiState {
    data object Disclosure : ProviderCallCheckUiState
    data object Running : ProviderCallCheckUiState
    data object Cancelled : ProviderCallCheckUiState
    data class Result(val result: ProviderCallCheckResult) : ProviderCallCheckUiState
}

@Composable
internal fun ProviderCallCheckDialog(
    instance: ProviderInstance,
    entry: ModelEntry,
    providerRepository: ProviderRepository,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(entry.id) { ProviderCallCheckController() }
    val apiKey = remember(instance.id, providerRepository) {
        providerRepository.usableApiKey(instance)
    }
    var uiState by remember(entry.id) {
        mutableStateOf<ProviderCallCheckUiState>(ProviderCallCheckUiState.Disclosure)
    }
    val jobHolder = remember(entry.id) { mutableStateOf<Job?>(null) }

    fun cancelRun() {
        controller.activeToken?.let(controller::cancel)
        jobHolder.value?.cancel()
        jobHolder.value = null
        uiState = ProviderCallCheckUiState.Cancelled
    }

    fun dismiss() {
        controller.activeToken?.let(controller::cancel)
        jobHolder.value?.cancel()
        jobHolder.value = null
        onDismiss()
    }

    DisposableEffect(entry.id) {
        onDispose {
            controller.activeToken?.let(controller::cancel)
            jobHolder.value?.cancel()
            jobHolder.value = null
        }
    }

    fun startRun() {
        val token = controller.begin(entry.model.id) ?: return
        uiState = ProviderCallCheckUiState.Running
        jobHolder.value = scope.launch {
            val thisJob = currentCoroutineContext()[Job]
            try {
                val result = runProviderCallCheck(
                    instance = instance,
                    entry = entry,
                    apiKey = apiKey,
                    context = context,
                )
                val completed = controller.complete(token, result)
                if (completed != null) {
                    uiState = ProviderCallCheckUiState.Result(result)
                }
            } catch (e: CancellationException) {
                controller.cancel(token)
                throw e
            } finally {
                controller.cancel(token)
                if (jobHolder.value === thisJob) {
                    jobHolder.value = null
                }
            }
        }
    }

    Dialog(
        onDismissRequest = ::dismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = uiState != ProviderCallCheckUiState.Running,
        ),
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 440.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            ) {
                Text(
                    text = stringResource(R.string.provider_call_check_disclosure_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(stringResource(R.string.daily_capabilities,
                    entry.model.inputModalities?.joinToString() ?: "text",
                    entry.model.outputModalities?.joinToString() ?: "text",
                    entry.model.reasoningEffortValues?.joinToString() ?: if (entry.model.supportsReasoning == true) "supported" else "—"),
                    style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.daily_capabilities_note), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                Text(
                    text = when (val state = uiState) {
                        ProviderCallCheckUiState.Disclosure -> stringResource(
                            R.string.provider_call_check_disclosure_message,
                            instance.label,
                            entry.model.displayName,
                        )
                        ProviderCallCheckUiState.Running -> stringResource(
                            R.string.provider_call_check_running,
                            entry.model.displayName,
                        )
                        ProviderCallCheckUiState.Cancelled -> stringResource(
                            R.string.provider_call_check_cancelled,
                        )
                        is ProviderCallCheckUiState.Result -> when (val result = state.result) {
                            is ProviderCallCheckResult.Success -> stringResource(
                                R.string.provider_call_check_success,
                                result.elapsedMs,
                            )
                            is ProviderCallCheckResult.Failure -> stringResource(
                                R.string.provider_call_check_failure,
                                result.elapsedMs,
                            )
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                when (val state = uiState) {
                    ProviderCallCheckUiState.Disclosure -> {
                        Spacer(Modifier.height(20.dp))
                        ProviderCallCheckActions(
                            cancelText = stringResource(R.string.common_cancel),
                            confirmText = stringResource(R.string.provider_call_check_send),
                            onCancel = ::dismiss,
                            onConfirm = ::startRun,
                        )
                    }
                    ProviderCallCheckUiState.Running -> {
                        Spacer(Modifier.height(20.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                            Text(
                                text = entry.model.id,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            MinisTextButton(onClick = ::cancelRun) {
                                Text(stringResource(R.string.provider_call_check_cancel))
                            }
                        }
                    }
                    ProviderCallCheckUiState.Cancelled -> {
                        Spacer(Modifier.height(12.dp))
                        ProviderCallCheckActions(
                            cancelText = stringResource(R.string.provider_call_check_close),
                            confirmText = stringResource(R.string.provider_call_check_try_again),
                            onCancel = ::dismiss,
                            onConfirm = {
                                uiState = ProviderCallCheckUiState.Disclosure
                            },
                        )
                    }
                    is ProviderCallCheckUiState.Result -> {
                        Spacer(Modifier.height(12.dp))
                        when (val result = state.result) {
                            is ProviderCallCheckResult.Success -> {
                                Text(
                                    text = stringResource(R.string.provider_call_check_output),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = result.output.ifEmpty {
                                        stringResource(R.string.provider_call_check_empty_output)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            is ProviderCallCheckResult.Failure -> {
                                Text(
                                    text = result.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        ProviderCallCheckActions(
                            cancelText = stringResource(R.string.provider_call_check_close),
                            confirmText = stringResource(R.string.provider_call_check_try_again),
                            onCancel = ::dismiss,
                            onConfirm = {
                                uiState = ProviderCallCheckUiState.Disclosure
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderCallCheckActions(
    cancelText: String,
    confirmText: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        MinisTextButton(onClick = onCancel) { Text(cancelText) }
        MinisTextButton(onClick = onConfirm) { Text(confirmText) }
    }
}
