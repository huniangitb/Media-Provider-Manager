package me.gm.cleaner.plugin.ui.navigation

import android.util.SparseArray
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import me.gm.cleaner.plugin.model.Template
import me.gm.cleaner.plugin.model.Templates
import me.gm.cleaner.plugin.ui.module.BinderViewModel
import me.gm.cleaner.plugin.ui.screens.about.AboutScreen
import me.gm.cleaner.plugin.ui.screens.appdetail.AppDetailScreen
import me.gm.cleaner.plugin.ui.screens.applist.AppListScreen
import me.gm.cleaner.plugin.ui.screens.createtemplate.CreateTemplateScreen
import me.gm.cleaner.plugin.ui.screens.settings.SettingsScreen
import me.gm.cleaner.plugin.ui.screens.remoteconfig.RemoteConfigScreen
import me.gm.cleaner.plugin.ui.screens.templates.TemplatesScreen
import me.gm.cleaner.plugin.ui.screens.usagerecord.UsageRecordScreen
import me.gm.cleaner.plugin.util.collatorComparator

@Composable
fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    startDestination: Any = AppRoute.AppList,
    onOpenDrawer: () -> Unit,
    binderViewModel: BinderViewModel = hiltViewModel(),
) {
    // Non-blocking initialization - load data in background without blocking UI
    LaunchedEffect(binderViewModel) {
        if (binderViewModel.pingBinder()) {
            binderViewModel.readTemplateSp()
            binderViewModel.readRootSp()
        }
    }

    val sparseArray by binderViewModel.remoteSpCacheLiveData.observeAsState(SparseArray())
    val templateJson = sparseArray.get(me.gm.cleaner.plugin.model.SpIdentifiers.TEMPLATE_PREFERENCES)
    val rootSpJson = sparseArray.get(me.gm.cleaner.plugin.model.SpIdentifiers.ROOT_PREFERENCES)

    // 本地模板（来自 rule 文件，不合并远程）
    var localTemplateList by remember { mutableStateOf<List<Template>>(emptyList()) }
    LaunchedEffect(templateJson) {
        val json = withContext(Dispatchers.IO) { binderViewModel.readRuleSp() }
        localTemplateList = runCatching {
            Templates(json).values.sortedWith(collatorComparator { it.templateName })
        }.getOrDefault(emptyList())
    }

    // 远程模板（来自 rule_remote 文件）
    var remoteTemplateList by remember { mutableStateOf<List<Template>>(emptyList()) }
    LaunchedEffect(templateJson) {
        val json = withContext(Dispatchers.IO) { binderViewModel.readRemoteSp() }
        if (!json.isNullOrBlank()) {
            remoteTemplateList = runCatching {
                Templates(json).values.map { it.copy(source = "remote") }
            }.getOrDefault(emptyList())
        }
    }

    // 合并 UI 显示列表：本地优先，远程追加不同名项
    val mergedTemplates: List<Template> = remember(localTemplateList, remoteTemplateList) {
        val localNames = localTemplateList.map { it.templateName }.toSet()
        (localTemplateList + remoteTemplateList.filter { it.templateName !in localNames })
            .sortedWith(collatorComparator { it.templateName })
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable<AppRoute.AppList> {
            AppListScreen(
                binderViewModel = binderViewModel,
                onOpenDrawer = onOpenDrawer,
                onAppClick = { pkg, label ->
                    navController.navigate(AppRoute.AppDetail(packageName = pkg, label = label))
                },
            )
        }
        composable<AppRoute.AppDetail> { backStackEntry ->
            val route = backStackEntry.toRoute<AppRoute.AppDetail>()
            AppDetailScreen(
                packageName = route.packageName,
                label = route.label,
                templates = mergedTemplates,
                onNavigateBack = { navController.popBackStack() },
                onCreateTemplate = {
                    navController.navigate(
                        AppRoute.CreateTemplate(
                            templateName = route.label ?: route.packageName,
                            packageNames = listOf(route.packageName),
                        )
                    )
                },
                onEditTemplate = { template ->
                    navController.navigate(
                        AppRoute.CreateTemplate(
                            templateName = template.templateName,
                            hookOperation = template.hookOperation,
                            packageNames = template.applyToApp,
                            permittedMediaTypes = template.permittedMediaTypes?.map { it.toString() },
                            filterPaths = template.filterPath,
                            redirectRules = if (!template.redirectRules.isNullOrEmpty())
                                Template.GSON.toJson(template.redirectRules) else null,
                            readOnlyPaths = template.readOnlyPaths,
                            allowPaths = template.allowPaths,
                            enableSandbox = template.enableSandbox,
                        )
                    )
                },
                binderViewModel = binderViewModel,
            )
        }
        composable<AppRoute.UsageRecord> {
            UsageRecordScreen(
                binderViewModel = binderViewModel,
                onOpenDrawer = onOpenDrawer,
            )
        }
        composable<AppRoute.Settings> {
            val context = androidx.compose.ui.platform.LocalContext.current
            SettingsScreen(
                rootSpJson = rootSpJson,
                onOpenDrawer = onOpenDrawer,
                onTemplatesClick = { navController.navigate(AppRoute.Templates) },
                onRemoteConfigClick = { navController.navigate(AppRoute.RemoteConfig) },
                onBackup = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("MediaProviderManagerRules", templateJson)
                    clipboard.setPrimaryClip(clip)
                    android.widget.Toast.makeText(context, me.gm.cleaner.plugin.R.string.backup_ok, android.widget.Toast.LENGTH_SHORT).show()
                },
                onRootSettingsChange = { newJson ->
                    binderViewModel.writeRootSp(newJson)
                },
                onTemplateRestore = { newJson ->
                    binderViewModel.writeTemplateSp(newJson)
                },
            )
        }
        composable<AppRoute.Templates> {
            TemplatesScreen(
                templates = localTemplateList,
                onNavigateBack = { navController.popBackStack() },
                onCreateTemplate = { navController.navigate(AppRoute.CreateTemplate()) },
                onDeleteTemplate = { template ->
                    binderViewModel.writeTemplateSp(
                        Template.GSON.toJson(
                            localTemplateList.filterNot { it.templateName == template.templateName }
                        )
                    )
                },
                onEditTemplate = { template ->
                    navController.navigate(
                        AppRoute.CreateTemplate(
                            templateName = template.templateName,
                            hookOperation = template.hookOperation,
                            packageNames = template.applyToApp,
                            permittedMediaTypes = template.permittedMediaTypes?.map { it.toString() },
                            filterPaths = template.filterPath,
                            redirectRules = if (!template.redirectRules.isNullOrEmpty())
                                Template.GSON.toJson(template.redirectRules) else null,
                            readOnlyPaths = template.readOnlyPaths,
                            allowPaths = template.allowPaths,
                            enableSandbox = template.enableSandbox,
                        )
                    )
                },
            )
        }
        composable<AppRoute.CreateTemplate> { backStackEntry ->
            val route = backStackEntry.toRoute<AppRoute.CreateTemplate>()
            CreateTemplateScreen(
                templateName = route.templateName,
                hookOperation = route.hookOperation,
                packageNames = route.packageNames,
                permittedMediaTypes = route.permittedMediaTypes,
                filterPaths = route.filterPaths,
                redirectRules = route.redirectRules,
                readOnlyPaths = route.readOnlyPaths,
                allowPaths = route.allowPaths,
                enableSandbox = route.enableSandbox,
                onNavigateBack = { navController.popBackStack() },
                onSave = { navController.popBackStack() },
                binderViewModel = binderViewModel,
            )
        }
        composable<AppRoute.About> {
            AboutScreen(onOpenDrawer = onOpenDrawer)
        }
        composable<AppRoute.RemoteConfig> {
            RemoteConfigScreen(
                binderViewModel = binderViewModel,
                onNavigateBack = { navController.popBackStack() },
            )
        }
    }
}
