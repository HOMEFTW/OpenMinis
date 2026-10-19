package com.openminis.app.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.model.ProviderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.ZoneId

@Composable
fun UsageStatsScreen(chatDao: ChatDao, providerConfig: ProviderConfig? = null, onBack: () -> Unit) {
    var samples by remember { mutableStateOf<List<UsageSample>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var days by rememberSaveable { mutableStateOf(0) }
    var search by rememberSaveable { mutableStateOf("") }
    var selectedProvider by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedModel by rememberSaveable { mutableStateOf<String?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val zone = ZoneId.systemDefault()

    // Re-evaluate local midnight/timezone changes while the page stays open.
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); delay(60_000) }
    }
    LaunchedEffect(chatDao, providerConfig, reload) {
        loaded = false
        failed = false
        try {
            samples = withContext(Dispatchers.IO) { usageSamples(chatDao.allUsageRecords(), providerConfig) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
        loaded = true
    }
    val inRange = remember(samples, days, now, zone) { samples.filter { usageInDateRange(it.createdAt, now, days, zone) } }
    val filtered = remember(inRange, selectedProvider, selectedModel, search) {
        inRange.filter {
            (selectedProvider == null || it.providerKey == selectedProvider) &&
                (selectedModel == null || it.modelId == selectedModel) &&
                usageMatchesSearch(it.modelId, it.displayName, it.providerName, search)
        }
    }
    val total = remember(filtered) { usageTotal(filtered) }
    val models = remember(filtered, zone) { usageModelStats(filtered, zone) }
    val trend = remember(filtered, now, days, zone) { usageTrend(filtered, now, days, zone) }

    SettingsScaffold(title = stringResource(R.string.usage_title), onBack = onBack) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((value, label) in listOf(1 to R.string.b34_usage_today, 7 to R.string.b34_usage_7_days,
                30 to R.string.b34_usage_30_days, 0 to R.string.b34_usage_all)) {
                FilterChip(selected = days == value, onClick = { days = value }, label = { Text(stringResource(label)) })
            }
        }
        OutlinedTextField(value = search, onValueChange = { search = it }, singleLine = true,
            label = { Text(stringResource(R.string.pr_usage_search)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        if (!loaded) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            return@SettingsScaffold
        }
        if (failed) {
            Text(stringResource(R.string.b34_usage_load_failed), modifier = Modifier.padding(16.dp))
            TextButton(onClick = { reload++ }, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.b34_usage_retry))
            }
            return@SettingsScaffold
        }
        if (selectedProvider != null || selectedModel != null) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { selectedProvider = null; selectedModel = null }) {
                    Text(stringResource(R.string.b34_usage_all_providers))
                }
                selectedProvider?.let { key ->
                    val label = samples.firstOrNull { it.providerKey == key }?.providerName ?: key
                    TextButton(onClick = { selectedModel = null }) { Text(label) }
                }
                selectedModel?.let { id ->
                    Text(samples.firstOrNull { it.modelId == id && it.providerKey == selectedProvider }?.displayName ?: id,
                        modifier = Modifier.align(Alignment.CenterVertically))
                }
            }
        }
        SettingsSection(header = stringResource(R.string.usage_section_total)) { TokenTotals(total) }
        SettingsSection(header = stringResource(R.string.b34_usage_trend)) {
            Text(stringResource(R.string.b34_usage_timezone, zone.id),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            if (total.total <= 0) {
                Text(stringResource(R.string.b34_usage_no_data), modifier = Modifier.padding(16.dp))
            } else UsageTrendChart(trend)
        }
        for ((provider, providerModels) in models.groupBy { it.sample.providerKey }) {
            val name = providerModels.first().sample.providerName
            SettingsSection(header = name) {
                if (selectedProvider == null) {
                    SettingsRow(title = stringResource(R.string.b34_usage_provider_details),
                        subtitle = formatCount(providerModels.sumOf { it.tokens.total }),
                        onClick = { selectedProvider = provider; selectedModel = null })
                }
                providerModels.forEachIndexed { index, stats ->
                    UsageModelRow(stats, selectedModel == stats.sample.modelId, index < providerModels.lastIndex) {
                        selectedProvider = provider
                        selectedModel = stats.sample.modelId
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TokenTotals(tokens: UsageTokens) {
    val values = listOfNotNull(
        stringResource(R.string.b34_usage_total_tokens) to formatCount(tokens.total),
        stringResource(R.string.usage_label_total_input) to formatCount(tokens.totalInput),
        stringResource(R.string.usage_label_output) to formatCount(tokens.output),
        if (tokens.cacheRead > 0) stringResource(R.string.usage_label_cache_read) to formatCount(tokens.cacheRead) else null,
        if (tokens.cacheCreation > 0) stringResource(R.string.usage_label_cache_creation) to formatCount(tokens.cacheCreation) else null,
        tokens.cacheHitRate?.takeIf { tokens.cacheRead > 0 }?.let { stringResource(R.string.usage_label_cache_hit_rate) to String.format("%.1f%%", it) },
    )
    values.forEachIndexed { index, (label, value) ->
        SettingsValueRow(title = label, value = value, valueColor = MaterialTheme.colorScheme.onSurface,
            showDivider = index < values.lastIndex)
    }
}

@Composable
private fun UsageModelRow(stats: UsageModelStats, selected: Boolean, showDivider: Boolean, onClick: () -> Unit) {
    val row = stats.sample
    Column {
        SettingsRow(title = row.displayName,
            subtitle = when (row.attribution) {
                Attribution.ESTIMATED -> stringResource(R.string.b34_usage_estimated)
                Attribution.UNKNOWN_SESSION -> stringResource(R.string.usage_attr_unknown_session)
                Attribution.MEASURED_REMOVED -> stringResource(R.string.usage_attr_removed)
                Attribution.MEASURED -> row.modelId
            },
            trailing = { Text(formatCount(stats.tokens.total), style = MaterialTheme.typography.bodyMedium) },
            onClick = onClick, showDivider = showDivider && !selected)
        if (selected) {
            Column(Modifier.padding(start = 32.dp, end = 16.dp, bottom = 12.dp)) {
                DetailRow(stringResource(R.string.usage_detail_input), formatCount(stats.tokens.input))
                DetailRow(stringResource(R.string.usage_detail_output), formatCount(stats.tokens.output))
                if (stats.tokens.cacheRead > 0) DetailRow(stringResource(R.string.usage_label_cache_read), formatCount(stats.tokens.cacheRead))
                if (stats.tokens.cacheCreation > 0) DetailRow(stringResource(R.string.usage_label_cache_creation), formatCount(stats.tokens.cacheCreation))
                stats.tokens.cacheHitRate?.takeIf { stats.tokens.cacheRead > 0 }?.let {
                    DetailRow(stringResource(R.string.usage_label_cache_hit_rate), String.format("%.1f%%", it))
                }
                if (stats.days > 0) DetailRow(stringResource(R.string.usage_detail_daily_avg), formatCount(stats.tokens.total / stats.days))
                if (stats.sessions > 0) DetailRow(stringResource(R.string.usage_detail_session_avg), formatCount(stats.tokens.total / stats.sessions))
                DetailRow(stringResource(R.string.usage_detail_sessions), stats.sessions.toString())
                DetailRow(stringResource(R.string.usage_detail_active_days), stats.days.toString())
            }
        }
    }
}

@Composable
private fun UsageTrendChart(buckets: List<UsageBucket>) {
    if (buckets.isEmpty()) return
    var selected by remember(buckets) { mutableStateOf<Int?>(null) }
    val colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.error)
    val labels = listOf(stringResource(R.string.usage_detail_input), stringResource(R.string.usage_label_cache_read),
        stringResource(R.string.usage_label_cache_creation), stringResource(R.string.usage_detail_output))
    val peak = buckets.maxBy { it.tokens.total }
    val max = peak.tokens.total.coerceAtLeast(1).toDouble()
    val description = stringResource(R.string.b34_usage_chart_description,
        formatCount(buckets.sumOf { it.tokens.total }), formatCount(peak.tokens.total), peak.label)
    val highlight = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    Column(Modifier.padding(16.dp)) {
        Text(stringResource(R.string.b34_usage_peak, formatCount(peak.tokens.total)), style = MaterialTheme.typography.bodySmall)
        Canvas(Modifier.fillMaxWidth().height(156.dp).padding(top = 8.dp)
            .semantics { contentDescription = description }
            .pointerInput(buckets) { detectTapGestures { point ->
                selected = (point.x / size.width * buckets.size).toInt().coerceIn(0, buckets.lastIndex)
            } }) {
            val slot = size.width / buckets.size
            buckets.forEachIndexed { index, bucket ->
                val x = index * slot
                if (selected == index) drawRect(highlight, Offset(x, 0f), Size(slot, size.height))
                var bottom = size.height
                listOf(bucket.tokens.input, bucket.tokens.cacheRead, bucket.tokens.cacheCreation, bucket.tokens.output)
                    .forEachIndexed { segment, count ->
                        val height = (count / max * size.height).toFloat()
                        bottom -= height
                        drawRect(colors[segment], Offset(x + slot * 0.12f, bottom), Size(slot * 0.76f, height))
                    }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(buckets.first().label, style = MaterialTheme.typography.labelSmall)
            Text(buckets.last().label, style = MaterialTheme.typography.labelSmall)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            labels.forEachIndexed { index, label ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(8.dp).background(colors[index]))
                    Text(label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        selected?.let { index ->
            val bucket = buckets[index]
            Text("${bucket.label} · ${bucket.tokens.total}", style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp))
            labels.zip(listOf(bucket.tokens.input, bucket.tokens.cacheRead, bucket.tokens.cacheCreation, bucket.tokens.output))
                .forEach { (label, value) -> DetailRow(label, value.toString()) }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format("%.1fk", n / 1_000.0)
    else -> n.toString()
}
