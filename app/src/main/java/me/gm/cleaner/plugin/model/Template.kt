package me.gm.cleaner.plugin.model

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import me.gm.cleaner.plugin.xposed.hooker.InsertHooker
import me.gm.cleaner.plugin.xposed.hooker.QueryHooker
import me.gm.cleaner.plugin.xposed.util.FileUtils
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
    @field:SerializedName("filter_path") val filterPath: List<String>?, // 仅用于隐藏/拦截
    @field:SerializedName("redirect_rules") val redirectRules: List<RedirectRule>? = null, // 新增：多重定向规则
    @Deprecated("Use redirectRules instead")
    @field:SerializedName("redirect_path") val redirectPath: String? = null, //以此保持兼容性，但在逻辑中不再主要使用
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
            when (cls) {
                QueryHooker::class.java -> template.hookOperation.contains("query")
                InsertHooker::class.java -> template.hookOperation.contains("insert")
                else -> throw IllegalArgumentException()
            } && template.applyToApp?.contains(packageName) == true
        }
        return this
    }

    fun applyTemplates(dataList: List<String>, mimeTypeList: List<String>): List<Boolean> =
        dataList.zip(mimeTypeList).map { (data, mimeType) ->
            (if (::matchingTemplates.isInitialized) matchingTemplates else _values)
                .any { template ->
                    // 检查是否在过滤列表中 (用于拦截/隐藏)
                    val isFiltered = template.filterPath?.any { FileUtils.contains(it, data) } == true
                    
                    // 检查媒体类型是否允许
                    val isMediaTypeNotPermitted = MimeUtils.resolveMediaType(mimeType) !in
                            (template.permittedMediaTypes ?: emptyList())

                    isMediaTypeNotPermitted || isFiltered
                }
        }
}