package com.openminis.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.tools.AgentToolSwitch

/** Optional tool capabilities; the agents switch lives with its roster. */
@Composable
fun AgentToolsSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var browserEnabled by remember {
        mutableStateOf(AgentToolSwitch.BROWSER.isEnabled(context))
    }
    var userAskEnabled by remember { mutableStateOf(AgentToolSwitch.USER_ASK.isEnabled(context)) }
    val prefs = remember { context.getSharedPreferences(AgentToolSwitch.PREFS, android.content.Context.MODE_PRIVATE) }
    var backgroundQuestions by remember { mutableStateOf(prefs.getBoolean("agent.tools.userAsk.notifyInBackground", true)) }
    var timeoutMinutes by remember { mutableStateOf(com.openminis.app.agent.jobs.UserQuestionSettings.timeoutMinutes(context)) }
    var showQuestionTimeout by remember { mutableStateOf(false) }

    if (showQuestionTimeout) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showQuestionTimeout = false },
            title = { androidx.compose.material3.Text(stringResource(R.string.stable115_question_timeout)) },
            text = {
                androidx.compose.foundation.layout.Column {
                    com.openminis.app.agent.jobs.UserQuestionSettings.TIMEOUT_CHOICES.forEach { minutes ->
                        SettingsChoiceRow(
                            title = if (minutes == 0) stringResource(R.string.stable115_question_unlimited)
                                else stringResource(R.string.stable115_question_minutes, minutes),
                            selected = timeoutMinutes == minutes,
                            onSelect = {
                                timeoutMinutes = minutes
                                com.openminis.app.agent.jobs.UserQuestionSettings.setTimeoutMinutes(context, minutes)
                                showQuestionTimeout = false
                            }, showDivider = false,
                        )
                    }
                }
            },
            confirmButton = {},
        )
    }

    SettingsScaffold(title = stringResource(R.string.settings_agent_tools), onBack = onBack) {
        SettingsSection(footer = stringResource(R.string.agent_tools_browser_footer)) {
            SettingsSwitchRow(
                title = stringResource(R.string.agent_tools_browser),
                checked = browserEnabled,
                onCheckedChange = {
                    browserEnabled = it
                    AgentToolSwitch.BROWSER.setEnabled(context, it)
                },
                showDivider = false,
            )
        }
        SettingsSection {
            SettingsSwitchRow(
                title = stringResource(R.string.build34_user_questions),
                checked = userAskEnabled,
                onCheckedChange = { userAskEnabled = it; AgentToolSwitch.USER_ASK.setEnabled(context, it) },
            )
            SettingsValueRow(
                title = stringResource(R.string.stable115_question_timeout),
                value = if (timeoutMinutes == 0) stringResource(R.string.stable115_question_unlimited)
                    else stringResource(R.string.stable115_question_minutes, timeoutMinutes),
                onClick = { if (userAskEnabled) showQuestionTimeout = true },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.build34_user_questions_background),
                checked = backgroundQuestions,
                enabled = userAskEnabled,
                onCheckedChange = {
                    backgroundQuestions = it
                    prefs.edit().putBoolean("agent.tools.userAsk.notifyInBackground", it).apply()
                },
                showDivider = false,
            )
        }
    }
}
