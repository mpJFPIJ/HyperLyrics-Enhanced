package com.juren233.hyperlyricsenhanced.ui.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.ui.component.AppWindowDialog
import com.juren233.hyperlyricsenhanced.utils.diagnostics.DiagnosticGitHubSettings
import com.juren233.hyperlyricsenhanced.utils.diagnostics.DiagnosticUploadScheduler
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun DiagnosticGitHubSettingsControls() {
    val context = LocalContext.current
    val queuedStatus = stringResource(R.string.diagnostics_queued)
    var configuration by remember(context) { mutableStateOf(DiagnosticGitHubSettings.configuration(context)) }
    var status by remember(context) { mutableStateOf(DiagnosticGitHubSettings.lastStatus(context)) }
    var showDialog by remember { mutableStateOf(false) }
    var repository by remember { mutableStateOf(configuration.repository.orEmpty()) }
    var token by remember { mutableStateOf("") }
    var enableOnSave by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    fun openSettings(enable: Boolean) {
        repository = configuration.repository.orEmpty()
        token = ""
        enableOnSave = enable
        error = ""
        showDialog = true
    }

    LaunchedEffect(context) {
        while (true) {
            status = DiagnosticGitHubSettings.lastStatus(context)
            delay(5_000L)
        }
    }

    SwitchPreference(
        title = stringResource(R.string.diagnostics_auto_title),
        summary = stringResource(R.string.diagnostics_auto_summary),
        checked = configuration.enabled,
        onCheckedChange = { enabled ->
            if (enabled && (!configuration.hasToken || configuration.repository == null)) {
                openSettings(true)
            } else {
                runCatching {
                    DiagnosticGitHubSettings.setEnabled(context, enabled)
                    DiagnosticUploadScheduler.sync(context)
                    if (enabled) DiagnosticUploadScheduler.uploadNow(context)
                    configuration = DiagnosticGitHubSettings.configuration(context)
                }.onFailure { error = it.message.orEmpty() }
            }
        },
    )
    ArrowPreference(
        title = stringResource(R.string.diagnostics_setup_title),
        summary = configuration.repository ?: stringResource(R.string.diagnostics_not_configured),
        onClick = { openSettings(configuration.enabled) },
    )
    if (configuration.enabled) {
        ArrowPreference(
            title = stringResource(R.string.diagnostics_upload_now),
            summary = status,
            onClick = {
                DiagnosticUploadScheduler.uploadNow(context)
                status = queuedStatus
            },
        )
    }

    AppWindowDialog(
        show = showDialog,
        title = stringResource(R.string.diagnostics_setup_title),
        onDismissRequest = { showDialog = false; token = "" },
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.diagnostics_private_warning))
            OutlinedTextField(
                value = repository,
                onValueChange = { repository = it },
                label = { Text(stringResource(R.string.diagnostics_repository)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text(stringResource(R.string.diagnostics_token)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (configuration.hasToken) Text(stringResource(R.string.diagnostics_token_optional))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.diagnostics_auto_title))
                Switch(checked = enableOnSave, onCheckedChange = { enableOnSave = it })
            }
            if (error.isNotBlank()) Text(error)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(text = stringResource(R.string.diagnostics_disconnect), onClick = {
                    DiagnosticGitHubSettings.disconnect(context)
                    DiagnosticUploadScheduler.sync(context)
                    configuration = DiagnosticGitHubSettings.configuration(context)
                    token = ""
                    showDialog = false
                })
                TextButton(text = stringResource(R.string.cancel), onClick = {
                    token = ""
                    showDialog = false
                })
                TextButton(text = stringResource(R.string.confirm), onClick = {
                    runCatching {
                        DiagnosticGitHubSettings.save(context, repository, token, enableOnSave)
                        DiagnosticUploadScheduler.sync(context)
                        if (enableOnSave) DiagnosticUploadScheduler.uploadNow(context)
                        configuration = DiagnosticGitHubSettings.configuration(context)
                        token = ""
                        showDialog = false
                    }.onFailure { error = it.message ?: "Invalid configuration" }
                })
            }
        }
    }
}
