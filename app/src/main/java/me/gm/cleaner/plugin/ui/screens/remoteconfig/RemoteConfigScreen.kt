/*
 * Copyright 2021 Green Mushroom
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.gm.cleaner.plugin.ui.screens.remoteconfig

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.gm.cleaner.plugin.R
import me.gm.cleaner.plugin.model.Template
import me.gm.cleaner.plugin.model.Templates
import me.gm.cleaner.plugin.ui.components.PreferenceGroup
import me.gm.cleaner.plugin.ui.components.SecondaryTopBar
import me.gm.cleaner.plugin.ui.components.SectionHeader
import me.gm.cleaner.plugin.ui.module.BinderViewModel
import me.gm.cleaner.plugin.util.collatorComparator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 远程配置页面 —— 查看远程拉取的配置、连接状态及调试日志。
 */
@Composable
fun RemoteConfigScreen(
    binderViewModel: BinderViewModel,
    onNavigateBack: () -> Unit,
) {
    var statusJson by remember { mutableStateOf<String?>(null) }
    var remoteTemplates by remember { mutableStateOf<List<Template>>(emptyList()) }
    var isPulling by remember { mutableStateOf(false) }

    // 调试日志（从服务端拉取）
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }

    fun refreshLogs() {
        val logJson = binderViewModel.getRemoteConfigLogs()
        if (!logJson.isNullOrBlank()) {
            try {
                val arr = org.json.JSONArray(logJson)
                logs = (0 until arr.length()).map { arr.getString(it) }
            } catch (_: Exception) {
                logs = logs
            }
        }
    }

    fun refreshStatus() {
        val status = binderViewModel.getRemoteConfigStatus()
        statusJson = status
        refreshLogs()

        val remoteJson = binderViewModel.readRemoteSp()
        if (!remoteJson.isNullOrBlank()) {
            val parsed = runCatching {
                Templates(remoteJson).values.sortedWith(
                    collatorComparator { it.templateName }
                )
            }.getOrDefault(emptyList())
            remoteTemplates = parsed
        } else {
            remoteTemplates = emptyList()
        }
    }

    LaunchedEffect(Unit) {
        refreshStatus()
    }

    val scope = rememberCoroutineScope()

    fun triggerPull() {
        if (isPulling) return
        isPulling = true
        scope.launch(Dispatchers.IO) {
            try {
                binderViewModel.triggerRemotePull()
            } catch (_: Exception) {
                // Binder 异常（DeadObjectException 等），由 refreshStatus 显示 disconnected 状态
            }
            withContext(Dispatchers.Main) {
                try {
                    refreshStatus()
                } catch (_: Exception) { }
                isPulling = false
            }
        }
    }

    val statusObj = remember(statusJson) {
        runCatching { statusJson?.let { JSONObject(it) } }.getOrNull()
    }
    val lastPull = statusObj?.optLong("lastPull", 0) ?: 0L
    val errorMsg = statusObj?.optString("error", null)?.takeIf { it != "null" }
    val templateCount = statusObj?.optInt("templateCount", 0) ?: 0
    val isRetryingStatus = statusObj?.optBoolean("isRetrying", false) ?: false
    val isSubscribed = statusObj?.optBoolean("isSubscribed", false) ?: false
    val logCount = statusObj?.optInt("logCount", 0) ?: 0
    val isConnected = lastPull > 0 && errorMsg == null && !isRetryingStatus

    val context = androidx.compose.ui.platform.LocalContext.current

    val lastPullTime = remember(lastPull) {
        if (lastPull > 0) {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date(lastPull))
        } else {
            context.getString(R.string.remote_never)
        }
    }

    Scaffold(
        topBar = {
            SecondaryTopBar(
                title = stringResource(R.string.remote_config_title),
                onNavigateBack = onNavigateBack,
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ========== 连接状态 ==========
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader(title = stringResource(R.string.remote_connection_status))
                    PreferenceGroup {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val (icon, color, label) = when {
                                isPulling -> Triple(
                                    Icons.Default.CloudSync,
                                    MaterialTheme.colorScheme.tertiary,
                                    stringResource(R.string.remote_pulling),
                                )
                                isRetryingStatus -> Triple(
                                    Icons.Default.CloudSync,
                                    MaterialTheme.colorScheme.tertiary,
                                    stringResource(R.string.remote_retrying),
                                )
                                isSubscribed -> Triple(
                                    Icons.Default.CloudDownload,
                                    MaterialTheme.colorScheme.primary,
                                    stringResource(R.string.remote_connected),
                                )
                                isConnected -> Triple(
                                    Icons.Default.CloudDownload,
                                    MaterialTheme.colorScheme.primary,
                                    stringResource(R.string.remote_connected),
                                )
                                else -> Triple(
                                    Icons.Default.CloudOff,
                                    MaterialTheme.colorScheme.error,
                                    stringResource(R.string.remote_disconnected),
                                )
                            }
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = color,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = color,
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                        StatusRow(
                            stringResource(R.string.remote_last_pull), lastPullTime)
                        StatusRow(
                            stringResource(R.string.remote_templates),
                            "${stringResource(R.string.remote_log_entries, templateCount)}")
                        StatusRow(
                            stringResource(R.string.remote_debug_logs),
                            "${stringResource(R.string.remote_log_entries, logCount)}")
                        if (isRetryingStatus) {
                            StatusRow(
                                stringResource(R.string.remote_retry),
                                stringResource(R.string.remote_retry_active),
                            )
                        }
                        if (isSubscribed) {
                            StatusRow(
                                stringResource(R.string.remote_subscription),
                                stringResource(R.string.remote_subscription_active),
                            )
                        }
                        if (errorMsg != null) {
                            StatusRow(
                                stringResource(R.string.remote_error), errorMsg, isError = true)
                        }

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Button(
                                onClick = { triggerPull() },
                                enabled = !isPulling,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    when {
                                        isPulling -> stringResource(R.string.remote_pulling)
                                        else -> stringResource(R.string.remote_pull_now)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // ========== 远程模板列表 ==========
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader(
                        title = stringResource(R.string.remote_templates_section),
                        supporting = stringResource(
                            R.string.remote_templates_pulled, remoteTemplates.size),
                    )
                    if (remoteTemplates.isEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                        ) {
                            Text(
                                text = if (isConnected) stringResource(R.string.remote_no_templates)
                                        else stringResource(R.string.remote_no_connection),
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        remoteTemplates.forEach { template ->
                            RemoteTemplateCard(template)
                        }
                    }
                }
            }

            // ========== 调试日志 ==========
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SectionHeader(
                            title = stringResource(R.string.remote_debug_log),
                            supporting = stringResource(R.string.remote_log_entries, logs.size),
                        )
                        if (logs.isNotEmpty()) {
                            IconButton(onClick = {
                                val ctx = context
                                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val label = ctx.getString(R.string.remote_debug_log)
                                val text = logs.joinToString("\n")
                                clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
                                Toast.makeText(ctx, R.string.remote_logs_copied, Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = stringResource(R.string.remote_copy_logs),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF1A1A2E),
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                                .height(200.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            if (logs.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.remote_no_logs),
                                    color = Color.Gray,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                )
                            } else {
                                logs.takeLast(80).forEach { line ->
                                    Text(
                                        text = line,
                                        color = Color(0xFF00FF88),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(
    label: String,
    value: String,
    isError: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (isError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun RemoteTemplateCard(template: Template) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = template.templateName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            if (template.enableSandbox) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                    Text(
                        text = stringResource(R.string.remote_template_sandbox),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            if (!template.hookOperation.isNullOrEmpty()) {
                Text(
                    text = stringResource(R.string.remote_template_operations,
                        template.hookOperation.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!template.applyToApp.isNullOrEmpty()) {
                val appNames = remember(template.applyToApp) {
                    val pm = context.packageManager
                    template.applyToApp.map { pkg ->
                        if (pkg == "*") {
                            context.getString(R.string.remote_template_all_apps)
                        } else {
                            try {
                                val ai = pm.getApplicationInfo(pkg, 0)
                                pm.getApplicationLabel(ai).toString()
                            } catch (_: Exception) {
                                pkg // 解析失败则回退显示包名
                            }
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.remote_template_apps,
                        appNames.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!template.filterPath.isNullOrEmpty()) {
                Text(
                    text = stringResource(R.string.remote_template_filter_paths,
                        template.filterPath.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!template.readOnlyPaths.isNullOrEmpty()) {
                Text(
                    text = stringResource(R.string.remote_template_readonly_paths,
                        template.readOnlyPaths.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!template.allowPaths.isNullOrEmpty()) {
                Text(
                    text = stringResource(R.string.remote_template_allow_paths,
                        template.allowPaths.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!template.redirectRules.isNullOrEmpty()) {
                Text(
                    text = stringResource(R.string.remote_template_redirects,
                        template.redirectRules.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                template.redirectRules.forEach { rule ->
                    Text(
                        text = stringResource(R.string.remote_template_redirect_item,
                            rule.source, rule.target),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.remote_template_redirect_resolved,
                            Template.resolveDisplayPath(rule.source)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.remote_template_redirect_resolved,
                            Template.resolveDisplayPath(rule.target)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
