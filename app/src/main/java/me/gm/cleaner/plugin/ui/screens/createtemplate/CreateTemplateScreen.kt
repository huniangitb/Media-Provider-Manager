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

package me.gm.cleaner.plugin.ui.screens.createtemplate

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.MediaStore.Files.FileColumns
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.gm.cleaner.plugin.R
import me.gm.cleaner.plugin.model.SpIdentifiers.TEMPLATE_PREFERENCES
import me.gm.cleaner.plugin.model.Template
import me.gm.cleaner.plugin.model.Templates
import me.gm.cleaner.plugin.ui.components.PreferenceGroup
import me.gm.cleaner.plugin.ui.components.SecondaryTopBar
import me.gm.cleaner.plugin.ui.components.SectionHeader
import me.gm.cleaner.plugin.ui.module.BinderViewModel
import me.gm.cleaner.plugin.ui.screens.templating.hookOperationLabel
import me.gm.cleaner.plugin.ui.screens.templating.mediaTypeLabel

private const val MEDIA_TYPE_PLAYLIST = 4

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateTemplateScreen(
    templateName: String?,
    hookOperation: List<String>?,
    packageNames: List<String>?,
    permittedMediaTypes: List<String>?,
    filterPaths: List<String>?,
    redirectRules: String? = null,
    readOnlyPaths: List<String>? = null,
    allowPaths: List<String>? = null,
    enableSandbox: Boolean = false,
    onNavigateBack: () -> Unit,
    onSave: () -> Unit,
    binderViewModel: BinderViewModel,
) {
    val context = LocalContext.current
    val isEditing = templateName != null

    // Form state
    var name by remember { mutableStateOf(templateName ?: "") }
    var selectedOperations by remember {
        mutableStateOf(hookOperation?.toSet() ?: setOf("query", "insert"))
    }
    var selectedMediaTypes by remember {
        mutableStateOf(permittedMediaTypes?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet<Int>())
    }
    var selectedFilterPaths by remember { mutableStateOf(filterPaths?.distinct().orEmpty()) }
    var selectedReadOnlyPaths by remember { mutableStateOf(readOnlyPaths?.distinct().orEmpty()) }
    var selectedAllowPaths by remember { mutableStateOf(allowPaths?.distinct().orEmpty()) }
    var sandboxEnabled by remember { mutableStateOf(enableSandbox) }
    // Preserve original applyToApp when editing
    val originalPackageNames = packageNames
    var hasLoaded by remember { mutableStateOf(false) }

    // Redirect rules state (editable via dialog) — keyed on redirectRules,
    // rebuilt from scratch when navigating to a different template.
    val editableRedirectRules = remember(redirectRules) {
        mutableStateListOf<Template.RedirectRule>().apply {
            if (!redirectRules.isNullOrBlank()) {
                try {
                    addAll(Template.GSON.fromJson(redirectRules, Array<Template.RedirectRule>::class.java))
                } catch (_: Exception) {}
            }
        }
    }
    var showRedirectDialog by remember { mutableStateOf(false) }
    var dialogSource by remember { mutableStateOf("") }
    var dialogTarget by remember { mutableStateOf("") }

    val hookOperations = listOf("query", "insert")
    val mediaTypes = listOf(
        MEDIA_TYPE_PLAYLIST,
        FileColumns.MEDIA_TYPE_SUBTITLE,
        FileColumns.MEDIA_TYPE_AUDIO,
        FileColumns.MEDIA_TYPE_VIDEO,
        FileColumns.MEDIA_TYPE_IMAGE,
        FileColumns.MEDIA_TYPE_DOCUMENT,
        FileColumns.MEDIA_TYPE_NONE,
    )
    val openDocumentTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val target = uri?.let { treeUriToFile(it, context) }
        val path = target?.path ?: return@rememberLauncherForActivityResult
        selectedFilterPaths = (selectedFilterPaths + path).distinct().sorted()
    }
    val openReadOnlyTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val target = uri?.let { treeUriToFile(it, context) }
        val path = target?.path ?: return@rememberLauncherForActivityResult
        selectedReadOnlyPaths = (selectedReadOnlyPaths + path).distinct().sorted()
    }
    val openAllowTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val target = uri?.let { treeUriToFile(it, context) }
        val path = target?.path ?: return@rememberLauncherForActivityResult
        selectedAllowPaths = (selectedAllowPaths + path).distinct().sorted()
    }

    // Auto-save function
    fun saveTemplate() {
        if (name.isBlank() || selectedOperations.isEmpty()) return

        val existingTemplates = Templates(binderViewModel.readTemplateSp()).values
        val nameConflict = existingTemplates.any {
            it.templateName == name && it.templateName != templateName
        }
        if (nameConflict) return

        val template = Template(
            templateName = name,
            hookOperation = selectedOperations.toList(),
            applyToApp = originalPackageNames,
            permittedMediaTypes = selectedMediaTypes.toList().ifEmpty { null },
            filterPath = selectedFilterPaths.ifEmpty { null },
            readOnlyPaths = selectedReadOnlyPaths.ifEmpty { null },
            allowPaths = selectedAllowPaths.ifEmpty { null },
            enableSandbox = sandboxEnabled,
            redirectRules = editableRedirectRules.toList().ifEmpty { null },
        )

        val json = Template.GSON.toJson(
            existingTemplates.filterNot { it.templateName == templateName } + template
        )
        binderViewModel.writeSp(TEMPLATE_PREFERENCES, json)
    }

    // Load existing template data on first render
    LaunchedEffect(Unit) {
        hasLoaded = true
    }

    // Auto-save when state changes (after initial load)
    LaunchedEffect(selectedOperations, selectedMediaTypes, selectedFilterPaths, selectedReadOnlyPaths, selectedAllowPaths, sandboxEnabled, editableRedirectRules.size) {
        if (hasLoaded) {
            saveTemplate()
        }
    }

    Scaffold(
        topBar = {
            SecondaryTopBar(
                title = if (isEditing) {
                    stringResource(R.string.edit_template_title)
                } else {
                    stringResource(R.string.create_template_title)
                },
                onNavigateBack = {
                    saveTemplate()
                    onNavigateBack()
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(title = stringResource(R.string.template_name_title))
                PreferenceGroup(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.template_name_title)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(title = stringResource(R.string.hook_operation_title))
                PreferenceGroup {
                    hookOperations.forEachIndexed { index, operation ->
                        TemplateToggleRow(
                            checked = operation in selectedOperations,
                            label = hookOperationLabel(context, operation),
                            onClick = {
                                selectedOperations = if (operation in selectedOperations) {
                                    selectedOperations - operation
                                } else {
                                    selectedOperations + operation
                                }
                            },
                        )
                        if (index != hookOperations.lastIndex) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(title = stringResource(R.string.filter_path_title))
                PreferenceGroup {
                    PathPickerRow(
                        title = stringResource(R.string.add_path),
                        onClick = { openDocumentTreeLauncher.launch(null) },
                    )
                    if (selectedFilterPaths.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                    selectedFilterPaths.forEachIndexed { index, path ->
                        FilterPathRow(
                            path = path,
                            onRemove = {
                                selectedFilterPaths = selectedFilterPaths.filterNot { it == path }
                            },
                        )
                        if (index != selectedFilterPaths.lastIndex) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }

            // 沙盒模式开关 + 允许的媒体类型（子级）
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(title = stringResource(R.string.enable_sandbox_title))
                PreferenceGroup {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = if (sandboxEnabled) MaterialTheme.colorScheme.tertiary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.enable_sandbox_title),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = stringResource(R.string.enable_sandbox_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = sandboxEnabled,
                            onCheckedChange = { sandboxEnabled = it },
                        )
                    }
                    if (!sandboxEnabled) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        Text(
                            text = stringResource(R.string.permitted_media_types_title),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                        )
                        mediaTypes.forEachIndexed { index, value ->
                            TemplateToggleRow(
                                checked = value in selectedMediaTypes,
                                label = mediaTypeLabel(context, value),
                                onClick = {
                                    selectedMediaTypes = if (value in selectedMediaTypes) {
                                        selectedMediaTypes - value
                                    } else {
                                        selectedMediaTypes + value
                                    }
                                },
                            )
                            if (index != mediaTypes.lastIndex) {
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            }
                        }
                    }
                }
            }

            // 只读路径
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(title = stringResource(R.string.read_only_path_title))
                PreferenceGroup {
                    PathPickerRow(
                        title = stringResource(R.string.add_path),
                        onClick = { openReadOnlyTreeLauncher.launch(null) },
                    )
                    if (selectedReadOnlyPaths.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                    selectedReadOnlyPaths.forEachIndexed { index, path ->
                        FilterPathRow(
                            path = path,
                            onRemove = {
                                selectedReadOnlyPaths = selectedReadOnlyPaths.filterNot { it == path }
                            },
                        )
                        if (index != selectedReadOnlyPaths.lastIndex) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }

            // 放行路径
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(title = stringResource(R.string.allow_path_title))
                Text(
                    text = stringResource(R.string.allow_path_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp),
                )
                PreferenceGroup {
                    PathPickerRow(
                        title = stringResource(R.string.add_path),
                        onClick = { openAllowTreeLauncher.launch(null) },
                    )
                    if (selectedAllowPaths.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                    selectedAllowPaths.forEachIndexed { index, path ->
                        FilterPathRow(
                            path = path,
                            onRemove = {
                                selectedAllowPaths = selectedAllowPaths.filterNot { it == path }
                            },
                        )
                        if (index != selectedAllowPaths.lastIndex) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }

            // 重定向规则（可编辑）
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(title = stringResource(R.string.redirect_rules_title))
                PreferenceGroup {
                    PathPickerRow(
                        title = stringResource(R.string.add_redirect_rule),
                        onClick = {
                            dialogSource = ""
                            dialogTarget = ""
                            showRedirectDialog = true
                        },
                    )
                    if (editableRedirectRules.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                    editableRedirectRules.forEachIndexed { index, rule ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${rule.source} → ${rule.target}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "↳ ${Template.resolveDisplayPath(rule.source)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "↳ ${Template.resolveDisplayPath(rule.target)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = {
                                editableRedirectRules.removeAt(index)
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.delete),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        if (index != editableRedirectRules.lastIndex) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }

            // 添加重定向规则对话框
            if (showRedirectDialog) {
                AlertDialog(
                    onDismissRequest = { showRedirectDialog = false },
                    title = { Text(stringResource(R.string.add_redirect_rule)) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = dialogSource,
                                onValueChange = { dialogSource = it },
                                label = { Text("Source") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            if (dialogSource.isNotBlank()) {
                                Text(
                                    text = "↳ ${Template.resolveDisplayPath(dialogSource)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                            OutlinedTextField(
                                value = dialogTarget,
                                onValueChange = { dialogTarget = it },
                                label = { Text("Target") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            if (dialogTarget.isNotBlank()) {
                                Text(
                                    text = "↳ ${Template.resolveDisplayPath(dialogTarget)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            if (dialogSource.isNotBlank() && dialogTarget.isNotBlank()) {
                                editableRedirectRules.add(
                                    Template.RedirectRule(source = dialogSource, target = dialogTarget)
                                )
                                showRedirectDialog = false
                            }
                        }) {
                            Text(stringResource(android.R.string.ok))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showRedirectDialog = false }) {
                            Text(stringResource(android.R.string.cancel))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PathPickerRow(
    title: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun FilterPathRow(
    path: String,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = path,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.delete),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun TemplateToggleRow(
    checked: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
