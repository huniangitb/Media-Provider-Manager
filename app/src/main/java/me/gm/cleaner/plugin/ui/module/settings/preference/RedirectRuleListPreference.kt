package me.gm.cleaner.plugin.ui.module.settings.preference

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import androidx.core.content.res.TypedArrayUtils
import androidx.preference.DialogPreference
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import me.gm.cleaner.plugin.R
import me.gm.cleaner.plugin.model.RedirectRule

class RedirectRuleListPreference @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    @SuppressLint("RestrictedApi") @AttrRes defStyleAttr: Int = TypedArrayUtils.getAttr(
        context, androidx.preference.R.attr.dialogPreferenceStyle,
        android.R.attr.dialogPreferenceStyle
    ), @StyleRes defStyleRes: Int = 0
) : DialogPreference(context, attrs, defStyleAttr, defStyleRes) {

    private val _rules = mutableListOf<RedirectRule>()
    var rules: List<RedirectRule>
        get() = _rules.toList()
        set(value) {
            _rules.clear()
            _rules.addAll(value)
            // 我们不使用系统默认的 persist，因为 rules 是复杂对象列表
            // 这里依赖 CreateTemplateFragment 的 save() 方法统一保存 Template JSON
            notifyChanged()
        }

    init {
        // 自定义 Summary，显示规则数量
        summaryProvider = SummaryProvider<RedirectRuleListPreference> { preference ->
            if (preference.rules.isEmpty()) {
                preference.context.getString(R.string.not_set) // 需确保 strings.xml 有此定义
            } else {
                "${preference.rules.size} rules configured"
            }
        }
    }

    // 这里复用 path_list_dialog 布局，因为它包含一个列表和一个 FAB，结构通用
    override fun getDialogLayoutResource(): Int = R.layout.path_list_dialog
}