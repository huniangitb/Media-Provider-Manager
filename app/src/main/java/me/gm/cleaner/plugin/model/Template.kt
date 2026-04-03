package me.gm.cleaner.plugin.model

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import me.gm.cleaner.plugin.xposed.hooker.InsertHooker
import me.gm.cleaner.plugin.xposed.hooker.QueryHooker
import me.gm.cleaner.plugin.xposed.util.MimeUtils

data class RedirectRule(
    @field:SerializedName("source") val source: String,
    @field:SerializedName("target") val target: String
)

data class Template(
    @field:SerializedName("template_name") val templateName: String,
    @field:SerializedName("hook_operation") val hookOperation: List<String>,
    @field:SerializedName("apply_to_app") val applyToApp: List<String>?,
    @field:SerializedName("permitted_media_types") val permittedMediaTypes: List<Int>?,
    @field:SerializedName("filter_path") val filterPath: List<String>?, 
    @field:SerializedName("read_only_path") val readOnlyPath: List<String>? = null, 
    @field:SerializedName("redirect_rules") val redirectRules: List<RedirectRule>? = null, 
    @Deprecated("Use redirectRules instead")
    @field:SerializedName("redirect_path") val redirectPath: String? = null, 
)

class Templates(json: String?) {
    private val _values = mutableListOf<Template>()
    val values: List<Template>
        get() = _values
    private lateinit var matchingTemplates: List<Template>

    init {
        if (!json.isNullOrEmpty()) {
            _values.addAll(
                Gson().fromJson(json, Array<Template>::class.java)
            )
        }
    }

    fun filterTemplate(cls: Class<*>, packageName: String): Templates {
        matchingTemplates = _values.filter { template ->
            val isOpMatch = when (cls) {
                QueryHooker::class.java -> template.hookOperation.contains("query")
                InsertHooker::class.java -> template.hookOperation.contains("insert")
                else -> throw IllegalArgumentException()
            }
            // applyToApp 为 null 或为空，则视为匹配所有应用（全局规则）
            val isPackageMatch = template.applyToApp.isNullOrEmpty() || template.applyToApp.contains(packageName)
            
            isOpMatch && isPackageMatch
        }.sortedBy { template ->
            // 排序权重：特定应用规则排在前面 (0)，全局规则排在后面 (1) 确保优先级正确
            if (template.applyToApp.isNullOrEmpty()) 1 else 0
        }
        return this
    }

    fun applyTemplates(dataList: List<String>, mimeTypeList: List<String>, isInsert: Boolean = false): List<Boolean> =
        dataList.zip(mimeTypeList).map { (data, mimeType) ->
            val activeTemplates = if (::matchingTemplates.isInitialized) matchingTemplates else _values
            
            var isFiltered = false
            var isReadOnly = false
            var isMediaTypeNotPermitted = false

            for (template in activeTemplates) {
                // 1. 过滤路径拦截
                if (template.filterPath?.any { data.startsWith(it, true) } == true) {
                    isFiltered = true
                }
                // 2. 只读路径拦截 (仅针对插入/写入操作)
                if (isInsert && template.readOnlyPath?.any { data.startsWith(it, true) } == true) {
                    isReadOnly = true
                }
            }

            // 3. 降低媒体类型限制优先级：如果操作已被明确拦截(RO/过滤)，则无需再看媒体类型
            if (!isFiltered && !isReadOnly) {
                // 取优先级最高（特定应用覆盖全局）且设置了媒体类型的模板进行判断
                val effectiveTemplate = activeTemplates.firstOrNull { it.permittedMediaTypes != null }
                if (effectiveTemplate != null) {
                    isMediaTypeNotPermitted = MimeUtils.resolveMediaType(mimeType) !in effectiveTemplate.permittedMediaTypes
                }
            }

            isFiltered || isReadOnly || isMediaTypeNotPermitted
        }
}