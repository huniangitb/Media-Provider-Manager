package me.gm.cleaner.plugin.ui.screens.templating

import android.content.Context
import me.gm.cleaner.plugin.R
import me.gm.cleaner.plugin.model.Template

fun hookOperationLabel(context: Context, value: String): String = when (value) {
    "query" -> context.getString(R.string.hook_operation_query_label)
    "insert" -> context.getString(R.string.hook_operation_insert_label)
    else -> value
}

fun mediaTypeLabel(context: Context, value: Int): String = when (value) {
    4 -> context.getString(R.string.media_type_playlist_label)
    5 -> context.getString(R.string.media_type_subtitle_label)
    2 -> context.getString(R.string.audio)
    3 -> context.getString(R.string.video)
    1 -> context.getString(R.string.image)
    6 -> context.getString(R.string.media_type_document_label)
    0 -> context.getString(R.string.media_type_none_label)
    else -> value.toString()
}

fun templateOperationSummary(context: Context, template: Template): String =
    context.getString(
        R.string.info_item,
        context.getString(R.string.hook_operation_title),
        template.hookOperation.joinToString(" / ") { hookOperationLabel(context, it) },
    )

fun templateMediaTypeSummary(context: Context, template: Template): String? =
    template.permittedMediaTypes
        ?.takeIf { it.isNotEmpty() }
        ?.joinToString(" / ") { mediaTypeLabel(context, it) }
        ?.let {
            context.getString(
                R.string.info_item,
                context.getString(R.string.permitted_media_types_title),
                it,
            )
        }

fun templateFilterPathSummary(context: Context, template: Template): String? =
    template.filterPath
        ?.takeIf { it.isNotEmpty() }
        ?.let {
            context.getString(
                R.string.info_item,
                context.getString(R.string.filter_path_title),
                it.joinToString(" / "),
            )
        }

fun templateAllowPathSummary(context: Context, template: Template): String? =
    template.allowPaths
        ?.takeIf { it.isNotEmpty() }
        ?.let {
            context.getString(
                R.string.info_item,
                context.getString(R.string.allow_path_title),
                it.joinToString(" / "),
            )
        }

/** 模板的规则总数（用于列表展示，不显示具体规则以节约空间） */
fun templateRuleCount(template: Template): Int {
    var count = 0
    if (template.enableSandbox) count++
    if (!template.permittedMediaTypes.isNullOrEmpty()) count++
    count += template.filterPath?.size ?: 0
    count += template.readOnlyPaths?.size ?: 0
    count += template.allowPaths?.size ?: 0
    count += template.redirectRules?.size ?: 0
    return count
}

/** 模板是否全局范围（apply_to_app 包含 "*"） */
fun templateIsGlobal(template: Template): Boolean =
    template.applyToApp?.contains("*") == true

/** 全局模板的作用域标签：traditional global → "全局", passive global → "被动" */
fun templateScopeLabel(context: Context, template: Template): String? {
    if (!templateIsGlobal(template)) return null
    return if (template.globalInject) {
        context.getString(R.string.template_scope_global)
    } else {
        context.getString(R.string.template_scope_passive)
    }
}
