package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.text.format.DateUtils
import com.openminis.app.R
import com.openminis.app.auth.KimiOAuthManager
import com.openminis.app.provider.usage.KimiSubscriptionUsageClient
import com.openminis.app.provider.usage.KimiUsageFailure
import com.openminis.app.provider.usage.KimiUsageLimit
import com.openminis.app.provider.usage.KimiUsageState
import com.openminis.app.ui.components.MinisSmallOutlinedButton
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

@Composable
internal fun KimiSubscriptionUsageBlock(instanceId: String, credentialRevision: String) {
    val context = LocalContext.current.applicationContext
    val manager = remember(context, instanceId) { KimiOAuthManager(context, instanceId) }
    val client = remember { KimiSubscriptionUsageClient() }
    // New credentials discard the previous account's snapshot immediately.
    var state by remember(instanceId, credentialRevision) { mutableStateOf<KimiUsageState>(KimiUsageState.Loading()) }
    var refresh by remember(instanceId, credentialRevision) { mutableIntStateOf(0) }
    LaunchedEffect(instanceId, credentialRevision, refresh) {
        val previous = state.usage
        state = KimiUsageState.Loading(previous)
        state = client.load(previous) { manager.validAccessToken(allowManualOverride = false) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.kimi_usage_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            MinisSmallOutlinedButton(enabled = state !is KimiUsageState.Loading, onClick = { refresh++ }) {
                Text(stringResource(R.string.kimi_usage_refresh))
            }
        }
        Text(stringResource(R.string.kimi_usage_note), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (val current = state) {
            is KimiUsageState.Loading -> Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(if (current.usage == null) R.string.kimi_usage_loading else R.string.kimi_usage_refreshing),
                    style = MaterialTheme.typography.bodySmall)
            }
            KimiUsageState.Empty -> Text(stringResource(R.string.kimi_usage_empty), style = MaterialTheme.typography.bodySmall)
            is KimiUsageState.Failed -> {
                Text(stringResource(when (current.reason) {
                    KimiUsageFailure.AUTHENTICATION -> R.string.kimi_usage_auth_required
                    KimiUsageFailure.UNAVAILABLE -> R.string.kimi_usage_unavailable
                    KimiUsageFailure.RATE_LIMITED -> R.string.kimi_usage_rate_limited
                    KimiUsageFailure.SERVER -> R.string.kimi_usage_server_error
                    KimiUsageFailure.NETWORK -> R.string.kimi_usage_network_error
                    KimiUsageFailure.TIMEOUT -> R.string.kimi_usage_timeout
                    KimiUsageFailure.INVALID_RESPONSE -> R.string.kimi_usage_invalid_response
                }), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (current.usage != null) Text(stringResource(R.string.kimi_usage_stale),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            is KimiUsageState.Ready -> Unit
        }
        state.usage?.let { usage ->
            usage.weekly?.let { UsageLimitRow(it, stringResource(R.string.kimi_usage_weekly)) }
            usage.limits.forEachIndexed { index, limit ->
                UsageLimitRow(limit, stringResource(R.string.kimi_usage_limit, index + 1))
            }
        }
    }
}

@Composable
private fun UsageLimitRow(limit: KimiUsageLimit, fallbackLabel: String) {
    val unknown = stringResource(R.string.kimi_usage_unknown)
    val numbers = NumberFormat.getIntegerInstance()
    val title = limit.name ?: limit.windowLabel?.let { stringResource(R.string.kimi_usage_window_limit, it) } ?: fallbackLabel
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(stringResource(R.string.kimi_usage_used_limit,
            limit.used?.let(numbers::format) ?: unknown, limit.limit?.let(numbers::format) ?: unknown),
            style = MaterialTheme.typography.bodySmall)
        val fraction = limit.remainingFraction
        limit.remaining?.let { remaining ->
            val value = numbers.format(remaining) + (fraction?.let { " (${NumberFormat.getPercentInstance().format(it)})" } ?: "")
            Text(stringResource(R.string.kimi_usage_remaining, value), style = MaterialTheme.typography.bodySmall)
        }
        if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        limit.resetAt?.let { instant ->
            val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date.from(instant))
            Text(stringResource(R.string.kimi_usage_reset_at, date), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } ?: limit.resetInSeconds?.let { seconds ->
            Text(stringResource(R.string.kimi_usage_reset_in, DateUtils.formatElapsedTime(seconds)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
