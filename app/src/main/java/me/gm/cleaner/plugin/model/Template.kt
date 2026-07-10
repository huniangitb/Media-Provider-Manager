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

package me.gm.cleaner.plugin.model

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import me.gm.cleaner.plugin.xposed.hooker.InsertHooker
import me.gm.cleaner.plugin.xposed.hooker.QueryHooker
import me.gm.cleaner.plugin.xposed.util.FileUtils
import me.gm.cleaner.plugin.xposed.util.MimeUtils
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

data class Template(
    @field:SerializedName("template_name") val templateName: String,
    @field:SerializedName("hook_operation") val hookOperation: List<String>,
    @field:SerializedName("apply_to_app") val applyToApp: List<String>?,
    @field:SerializedName("permitted_media_types") val permittedMediaTypes: List<Int>?,
    @field:SerializedName("filter_path") val filterPath: List<String>?,
    // 沙盒占位符：字段存在但无实际功能
    @field:SerializedName("enable_sandbox") val enableSandbox: Boolean = false,
    // 只读路径列表，匹配的文件/目录不可写入（远程配置专用）
    @field:SerializedName("read_only_path") val readOnlyPaths: List<String>? = null,
    // 放行路径列表，此路径下的文件不受 permittedMediaTypes 限制
    @field:SerializedName("allow_paths") val allowPaths: List<String>? = null,
    // 重定向规则：source → target 路径映射
    @field:SerializedName("redirect_rules") val redirectRules: List<RedirectRule>? = null,
    // 是否注入到所有应用（false 时仅注入到有显式模板的应用）
    @field:SerializedName("global_inject") val globalInject: Boolean = true,
    // 配置来源标记："local" 或 "remote"，不参与序列化
    @Transient val source: String = "local",
) {
    companion object {
        val GSON: Gson = Gson()

        /** 供 UI 层使用：将相对路径/通配符路径解析为绝对路径 */
        private val storagePathRegex = Regex("^/storage/emulated/\\d+(/|$)")

        fun resolveDisplayPath(path: String): String {
            if (storagePathRegex.containsMatchIn(path)) return path
            val storageRoot = FileUtils.externalStorageDirPath.takeUnless { it.isNullOrBlank() }
                ?: "/storage/emulated/0"
            val cleaned = path.trimStart('/')
            return if (cleaned == "?" || cleaned.startsWith("?/")) {
                "${storageRoot.trimEnd('/')}/${cleaned.removePrefix("?").trimStart('/')}"
            } else {
                "${storageRoot.trimEnd('/')}/$cleaned"
            }
        }
    }

    data class RedirectRule(
        @field:SerializedName("source") val source: String,
        @field:SerializedName("target") val target: String,
    )
}

class Templates(json: String?, private val remoteValues: List<Template> = emptyList()) {
    private val _values = mutableListOf<Template>()
    val values: List<Template>
        get() = _values

    /** 本地 + 远程（优先级：本地 > 远程，同名去重） */
    private val mergedValues: List<Template> by lazy {
        _values.toMutableList().apply {
            val localNames = _values.map { it.templateName }.toSet()
            for (rt in remoteValues) {
                if (rt.templateName !in localNames) {
                    add(rt.copy(source = "remote"))
                }
            }
        }
    }

    // Thread-safe cache for filtered templates by (operation, packageName)
    // Use LRU-style cache to prevent unbounded memory growth
    private val filteredCache = ConcurrentHashMap<String, List<Template>>()
    private val accessOrderQueue = ConcurrentLinkedQueue<String>()
    
    // Maximum cache size to prevent memory leaks
    companion object {
        private const val MAX_CACHE_SIZE = 200
    }

    init {
        if (!json.isNullOrEmpty()) {
            _values.addAll(
                Template.GSON.fromJson(json, Array<Template>::class.java)
            )
        }
    }
    
    /**
     * Clear the cache. Should be called when templates are updated.
     */
    fun clearCache() {
        filteredCache.clear()
        accessOrderQueue.clear()
    }

    fun getFilteredTemplates(cls: Class<*>, packageName: String): List<Template> {
        val operation = when (cls) {
            QueryHooker::class.java -> "query"
            InsertHooker::class.java -> "insert"
            else -> throw IllegalArgumentException()
        }

        val cacheKey = "$operation:$packageName"
        
        // Get or compute value
        val result = filteredCache.getOrPut(cacheKey) {
            // 先过滤：匹配操作类型 + 剔除无规则的空模板
            val filtered = mergedValues.filter { template ->
                template.hookOperation.contains(operation) &&
                        // 剔除无任何规则的空模板，避免影响规则生效或产生无意义缓存条目
                        !(template.permittedMediaTypes.isNullOrEmpty() &&
                                template.filterPath.isNullOrEmpty() &&
                                template.readOnlyPaths.isNullOrEmpty() &&
                                template.redirectRules.isNullOrEmpty() &&
                                template.allowPaths.isNullOrEmpty() &&
                                !template.enableSandbox)
            }
            // 检查此包是否有显式模板匹配
            val hasExplicit = filtered.any { t ->
                t.applyToApp?.let { packageName in it } == true
            }
            val explicitOrGlobal = mutableListOf<Template>()
            val passiveGlobal = mutableListOf<Template>()
            for (template in filtered) {
                val apps = template.applyToApp ?: emptyList()
                when {
                    // 显式匹配：包名在列表中
                    apps.contains(packageName) -> explicitOrGlobal.add(template)
                    // 传统全局：* 且 globalInject 为 true（默认）
                    "*" in apps && template.globalInject -> explicitOrGlobal.add(template)
                    // 被动全局：* 且 globalInject == false，仅当此包有显式模板时才注入
                    "*" in apps && !template.globalInject && hasExplicit -> passiveGlobal.add(template)
                }
            }
            // 显式/传统全局在前 → 被动全局在后（优先级：被动全局规则作为附加限制，最低）
            explicitOrGlobal + passiveGlobal
        }
        
        // Update access order for LRU eviction
        accessOrderQueue.remove(cacheKey)
        accessOrderQueue.offer(cacheKey)
        
        // Evict oldest entries if cache exceeds max size
        evictOldestIfNeeded()
        
        return result
    }
    
    /**
     * Evict oldest entries when cache exceeds max size.
     * Uses LRU (Least Recently Used) eviction policy.
     */
    private fun evictOldestIfNeeded() {
        while (filteredCache.size > MAX_CACHE_SIZE) {
            val oldestKey = accessOrderQueue.poll()
            if (oldestKey != null) {
                filteredCache.remove(oldestKey)
            } else {
                break
            }
        }
    }

    /**
     * 将远程配置中的相对路径解析为绝对路径。
     * 远程配置的路径是相对于存储根目录的（如 /DataBackup），
     * 需要转换为 /storage/emulated/0/DataBackup 才能匹配系统操作路径。
     * 支持 /storage/emulated/?/ 任意用户 ID 前缀。
     */
    private val storagePathRegex = Regex("^/storage/emulated/\\d+(/|$)")

    private fun resolvePath(path: String): String {
        // 已包含 /storage/emulated/{userId}/ 前缀 → 已经是绝对路径
        if (storagePathRegex.containsMatchIn(path)) return path
        val storageRoot = FileUtils.externalStorageDirPath.takeUnless { it.isNullOrBlank() }
            ?: "/storage/emulated/0"
        val cleaned = path.trimStart('/')
        // 通配符 ? → 当前用户的存储根目录（支持多用户）
        return if (cleaned == "?" || cleaned.startsWith("?/")) {
            "${storageRoot.trimEnd('/')}/${cleaned.removePrefix("?").trimStart('/')}"
        } else {
            "${storageRoot.trimEnd('/')}/$cleaned"
        }
    }

    fun applyTemplates(
        templates: List<Template>, dataList: List<String>, mimeTypeList: List<String>
    ): List<Boolean> =
        dataList.zip(mimeTypeList).map { (data, mimeType) ->
            // 全局放行检查：任一模板的 allowPaths 匹配 → 此路径不受任何限制
            if (templates.any { t ->
                    t.allowPaths?.any { FileUtils.contains(resolvePath(it), data) } == true
                }) return@map false
            // 拒绝检查：任一模板拒绝则拦截
            templates.any { template ->
                // 沙盒开启 → 拒绝所有媒体类型
                if (template.enableSandbox) {
                    return@any true
                }
                // 有限拒绝：permittedMediaTypes 设置且当前类型不在其中
                (template.permittedMediaTypes != null && template.permittedMediaTypes.isNotEmpty() &&
                        MimeUtils.resolveMediaType(mimeType) !in template.permittedMediaTypes) ||
                        template.filterPath?.any { FileUtils.contains(resolvePath(it), data) } == true
            }
        }

    /**
     * 检查 [path] 是否在任一模板的 readOnlyPaths 中。
     * 仅检查 readOnlyPaths 非空的模板。
     */
    fun isReadOnlyPath(path: String, packageName: String = ""): Boolean {
        return mergedValues.any { t ->
            if (shouldSkipForPackage(t, packageName)) return@any false
            t.readOnlyPaths?.any { FileUtils.contains(resolvePath(it), path) } == true
        }
    }

    /**
     * 解析路径重定向：如果 [path] 匹配任一模板 redirectRules 的 source，
     * 返回替换后的 target 路径；否则返回原路径。
     * source/target 均为相对路径，需解析为绝对路径后再匹配。
     */
    fun resolveRedirect(path: String, packageName: String = ""): String {
        for (t in mergedValues) {
            if (shouldSkipForPackage(t, packageName)) continue
            val rules = t.redirectRules ?: continue
            for (rule in rules) {
                val absSource = resolvePath(rule.source)
                if (path.startsWith(absSource)) {
                    return resolvePath(rule.target) + path.substring(absSource.length)
                }
            }
        }
        return path
    }

    /**
     * 反转重定向：将 [path] 中匹配 target 前缀的部分替换为 source 前缀。
     * 用于查询结果中 _data 列的路径重写，让应用看到的是 source 路径。
     */
    fun reverseRedirect(path: String, packageName: String = ""): String {
        for (t in mergedValues) {
            if (shouldSkipForPackage(t, packageName)) continue
            val rules = t.redirectRules ?: continue
            for (rule in rules) {
                val absTarget = resolvePath(rule.target)
                if (path.startsWith(absTarget)) {
                    return resolvePath(rule.source) + path.substring(absTarget.length)
                }
            }
        }
        return path
    }

    /** 判断被动全局模板是否应对此包跳过 */
    private fun shouldSkipForPackage(template: Template, packageName: String): Boolean {
        val apps = template.applyToApp ?: return false
        if ("*" !in apps || template.globalInject) return false
        if (packageName.isEmpty()) return false  // 未知包名时不跳过（向后兼容）
        // 被动全局：仅当包有显式模板时才匹配
        return mergedValues.none { other ->
            other.templateName != template.templateName &&
            other.applyToApp?.contains(packageName) == true
        }
    }
}
