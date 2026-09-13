package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ThinkingLevel
import kotlin.math.roundToInt

internal fun thinkingSliderLevels(available: List<ThinkingLevel>): List<ThinkingLevel> =
    (listOf(ThinkingLevel.OFF) + available).distinct().sortedBy { it.rank }

internal fun thinkingSliderIndex(current: ThinkingLevel, levels: List<ThinkingLevel>): Int =
    levels.indexOfLast { it.rank <= current.rank }.coerceAtLeast(0)

/** Input-local controls. Advanced group editing and quick tests retain their existing sheet. */
@Composable
internal fun ComposerModelControls(
    modelName: String,
    groupName: String,
    providerName: String,
    config: ProviderConfig,
    groups: List<ModelGroup>,
    selectedGroupId: String?,
    activeEntryId: String?,
    currentThinking: ThinkingLevel,
    availableLevels: List<ThinkingLevel>,
    supportsThinking: Boolean,
    fastMode: Boolean,
    onSelectEntry: (String) -> Unit,
    onSelectGroup: (String) -> Unit,
    onSelectThinking: (ThinkingLevel) -> Unit,
    onMoreModels: () -> Unit,
) {
    var openMenu by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val levels = thinkingSliderLevels(availableLevels)
    val displayedLevel = levels[thinkingSliderIndex(currentThinking, levels)]
    val menuWidth = (LocalConfiguration.current.screenWidthDp - 40).coerceIn(200, 340).dp
    val modelLabel = modelName.ifBlank { stringResource(R.string.model_picker_title) }
    val reasoningLabel = stringResource(R.string.composer_thinking_label, displayedLevel.localizedName(context))
    LaunchedEffect(activeEntryId, supportsThinking) { openMenu = null }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            ComposerSelector(
                label = modelLabel,
                description = listOf(modelLabel, providerName, groupName).filter { it.isNotBlank() }.joinToString(" · "),
                fastMode = fastMode,
                onClick = { openMenu = "model" },
            )
            DropdownMenu(
                expanded = openMenu == "model",
                onDismissRequest = { openMenu = null },
                shape = RoundedCornerShape(20.dp),
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                var query by remember { mutableStateOf("") }
                val instances = remember(config) { config.instances.filter { it.isEnabled }.associateBy { it.id } }
                val entries = remember(config, query, activeEntryId) {
                    config.modelEntries.filter { entry ->
                        !entry.isHidden && entry.providerInstanceId in instances &&
                            (query.isBlank() || listOf(entry.model.displayName, entry.model.id,
                                instances[entry.providerInstanceId]?.label.orEmpty()).any { it.contains(query.trim(), true) })
                    }.sortedBy { if (it.id == activeEntryId) 0 else 1 }
                }
                val matchedGroups = groups.filter { query.isBlank() || it.name.contains(query.trim(), true) }
                Column(Modifier.width(menuWidth)) {
                    Text(stringResource(R.string.model_picker_title), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.model_picker_search_placeholder), style = MaterialTheme.typography.bodyMedium) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        shape = RoundedCornerShape(12.dp),
                    )
                    // Explicit dimensions keep lazy rows out of DropdownMenu's intrinsic measurement.
                    LazyColumn(Modifier.fillMaxWidth().height(260.dp), contentPadding = PaddingValues(8.dp)) {
                        items(matchedGroups, key = { "group:${it.id}" }) { group ->
                            ComposerModelRow(
                                title = group.name,
                                subtitle = stringResource(R.string.composer_model_group),
                                selected = selectedGroupId == group.id,
                                onClick = { openMenu = null; onSelectGroup(group.id) },
                            )
                        }
                        if (matchedGroups.isNotEmpty() && entries.isNotEmpty()) item { HorizontalDivider(Modifier.padding(vertical = 6.dp)) }
                        items(entries, key = { "entry:${it.id}" }) { entry ->
                            ComposerModelRow(
                                title = entry.model.displayName,
                                subtitle = instances[entry.providerInstanceId]?.label.orEmpty(),
                                selected = entry.id == activeEntryId,
                                onClick = { openMenu = null; onSelectEntry(entry.id) },
                            )
                        }
                        if (entries.isEmpty() && matchedGroups.isEmpty()) item {
                            Text(stringResource(R.string.model_picker_no_results), Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider()
                    TextButton(onClick = { openMenu = null; onMoreModels() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.composer_more_models), color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        if (supportsThinking && availableLevels.isNotEmpty()) {
            Box(Modifier.weight(1f)) {
                ComposerSelector(label = reasoningLabel, description = reasoningLabel, onClick = { openMenu = "thinking" })
                DropdownMenu(
                    expanded = openMenu == "thinking",
                    onDismissRequest = { openMenu = null },
                    shape = RoundedCornerShape(20.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    Column(Modifier.width(menuWidth).padding(16.dp)) {
                        ThinkingLevelSlider(currentThinking, availableLevels, onSelectThinking)
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerSelector(label: String, description: String, fastMode: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (fastMode) Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ComposerModelRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f) else androidx.compose.ui.graphics.Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Icon(Icons.Default.Check, contentDescription = stringResource(R.string.composer_selected),
            modifier = Modifier.padding(start = 8.dp).size(18.dp))
    }
}

@Composable
internal fun ThinkingLevelSlider(current: ThinkingLevel, availableLevels: List<ThinkingLevel>, onSelect: (ThinkingLevel) -> Unit) {
    val context = LocalContext.current
    val levels = remember(availableLevels) { thinkingSliderLevels(availableLevels) }
    var position by remember(current, levels) { mutableFloatStateOf(thinkingSliderIndex(current, levels).toFloat()) }
    val selected = levels[position.roundToInt().coerceIn(0, levels.lastIndex)]
    val title = stringResource(R.string.thinking_level_sheet_title)
    val levelLabel = selected.localizedName(context)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(levelLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = position,
            onValueChange = { position = it },
            onValueChangeFinished = { onSelect(levels[position.roundToInt().coerceIn(0, levels.lastIndex)]) },
            valueRange = 0f..levels.lastIndex.toFloat().coerceAtLeast(1f),
            steps = (levels.size - 2).coerceAtLeast(0),
            enabled = levels.size > 1,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = title; stateDescription = levelLabel },
            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.onSurface,
                activeTrackColor = MaterialTheme.colorScheme.onSurface,
                inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(levels.first().localizedName(context), style = MaterialTheme.typography.labelSmall)
            Text(levels.last().localizedName(context), style = MaterialTheme.typography.labelSmall)
        }
        Text(stringResource(R.string.composer_thinking_hint), Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (current.rank > levels.last().rank) Text(
            stringResource(R.string.composer_thinking_capped, levels.last().localizedName(context)),
            Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
