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

package me.gm.cleaner.plugin.ui.module.appmanagement

import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.gm.cleaner.plugin.model.Templates
import me.gm.cleaner.plugin.ui.module.BinderViewModel
import java.util.concurrent.atomic.AtomicInteger

class AppListLoader(private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default) {

    private fun fetchRuleCount(templates: Templates): MutableMap<String, Int> {
        val map = mutableMapOf<String, Int>()
        // 第一遍：统计显式分配（非通配符的包名）
        templates.values.forEach { template ->
            template.applyToApp?.forEach { pkg ->
                if (pkg != "*") {
                    map[pkg] = map.getOrDefault(pkg, 0) + 1
                }
            }
        }
        // 第二遍：处理全局模板（仅处理纯 * 的模板，避免与第一遍重复计数）
        val hasExplicitApps = map.keys.toSet()  // 已有显式模板的应用
        templates.values.forEach { template ->
            val apps = template.applyToApp ?: emptyList()
            if ("*" in apps && apps.all { it == "*" }) {  // 仅纯 *，无显式包名
                if (template.globalInject) {
                    // 传统全局：稍后在 load() 中遍历所有已安装应用时 +1
                    // 此处用特殊标记表示"有全局模板"
                    map["__global__"] = map.getOrDefault("__global__", 0) + 1
                } else {
                    // 被动全局：仅对已有显式模板的应用 +1
                    for (pkg in hasExplicitApps) {
                        map[pkg] = map.getOrDefault(pkg, 0) + 1
                    }
                }
            }
        }
        return map
    }

    suspend fun load(
        binderViewModel: BinderViewModel, pm: PackageManager, l: ProgressListener?
    ) = withContext(defaultDispatcher) {
        val packageNameToRuleCount =
            fetchRuleCount(Templates(binderViewModel.readTemplateSp()))
        val globalCount = packageNameToRuleCount.remove("__global__") ?: 0
        val installedPackages = binderViewModel.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        val size = installedPackages.size
        val count = AtomicInteger(0)
        installedPackages.map { pi ->
            ensureActive()
            l?.onProgress(100 * count.incrementAndGet() / size)
            AppListModel(
                pi,
                pi.applicationInfo?.let { pm.getApplicationLabel(it).toString() } ?: pi.packageName,
                packageNameToRuleCount.getOrDefault(pi.packageName, 0) + globalCount,
            )
        }
    }

    suspend fun update(old: List<AppListModel>, binderViewModel: BinderViewModel) =
        withContext(defaultDispatcher) {
            val packageNameToRuleCount =
                fetchRuleCount(Templates(binderViewModel.readTemplateSp()))
            val globalCount = packageNameToRuleCount.remove("__global__") ?: 0
            old.map {
                it.copy(
                    ruleCount = packageNameToRuleCount.getOrDefault(it.packageInfo.packageName, 0)
                            + globalCount,
                )
            }
        }

    interface ProgressListener {
        fun onProgress(progress: Int)
    }
}
