package me.gm.cleaner.plugin.ui.module.settings.preference

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.os.bundleOf
import androidx.preference.DialogPreference
import androidx.preference.PreferenceDialogFragmentCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputLayout
import me.gm.cleaner.plugin.R
import me.gm.cleaner.plugin.ktx.dpToPx
import me.gm.cleaner.plugin.ktx.overScrollIfContentScrollsPersistent
import me.gm.cleaner.plugin.model.RedirectRule
import rikka.recyclerview.fixEdgeEffect

class RedirectRuleListPreferenceFragmentCompat : PreferenceDialogFragmentCompat() {
    private val ruleListPreference by lazy { preference as RedirectRuleListPreference }
    private lateinit var adapter: RedirectRuleAdapter
    
    // 暂存当前的规则列表
    var currentRules = mutableListOf<RedirectRule>()

    // 用于处理文件选择器的回调
    private var pendingTargetInputLayout: TextInputLayout? = null
    val openDocumentTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val targetFile = treeUriToFile(uri, requireContext())
            if (targetFile != null) {
                pendingTargetInputLayout?.editText?.setText(targetFile.path)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.AppTheme_FullScreenDialog)
        adapter = RedirectRuleAdapter(this)
        
        // 初始化数据
        if (savedInstanceState == null) {
            currentRules = ruleListPreference.rules.toMutableList()
        } else {
            // 恢复状态逻辑可在此添加
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?) =
        object : Dialog(requireContext(), theme) {
            override fun onBackPressed() {
                onDismiss(requireDialog())
            }
        }.apply {
            val contentView = onCreateDialogView(context)
            if (contentView != null) {
                onBindDialogView(contentView)
                setContentView(contentView)
            }
            window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

    @SuppressLint("RestrictedApi")
    override fun onBindDialogView(view: View) {
        super.onBindDialogView(view)
        
        // 设置 Toolbar
        view.findViewById<Toolbar>(R.id.toolbar).apply {
            setNavigationOnClickListener { dismiss() }
            setNavigationIcon(R.drawable.ic_outline_close_24) // 确保资源存在
            title = ruleListPreference.dialogTitle ?: "Redirect Rules"
            inflateMenu(R.menu.toolbar_save) // 复用保存菜单
            setOnMenuItemClickListener { item ->
                if (item.itemId == R.id.menu_save) {
                    saveAndExit()
                    true
                } else {
                    false
                }
            }
        }

        // 设置列表
        val list = view.findViewById<RecyclerView>(R.id.list)
        list.adapter = adapter
        list.layoutManager = GridLayoutManager(requireContext(), 1)
        list.fixEdgeEffect(false)
        list.overScrollIfContentScrollsPersistent()
        
        adapter.submitList(currentRules.toList())

        // 设置 FAB 添加按钮
        view.findViewById<FloatingActionButton>(R.id.fab).setOnClickListener {
            showEditDialog(null)
        }
    }

    // 显示添加或编辑规则的对话框
    fun showEditDialog(existingRule: RedirectRule?) {
        val context = requireContext()
        val builder = MaterialAlertDialogBuilder(context)
        builder.setTitle(if (existingRule == null) "Add Rule" else "Edit Rule")

        // 动态构建视图 (两个输入框)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = context.dpToPx(16)
            setPadding(padding, padding, padding, 0)
        }

        val sourceInput = TextInputLayout(context).apply {
            hint = "Source Path (Original)"
            addView(EditText(context).apply { setText(existingRule?.source) })
        }
        val targetInput = TextInputLayout(context).apply {
            hint = "Target Path (Actual)"
            setEndIconMode(TextInputLayout.END_ICON_CUSTOM)
            setEndIconDrawable(R.drawable.ic_outline_folder_open_24) // 假设有文件夹图标
            setEndIconOnClickListener {
                pendingTargetInputLayout = this
                openDocumentTreeLauncher.launch(null)
            }
            addView(EditText(context).apply { setText(existingRule?.target) })
        }

        container.addView(sourceInput)
        container.addView(targetInput) // 简单的垂直布局，稍微加点间距

        builder.setView(container)
        builder.setPositiveButton(android.R.string.ok) { _, _ ->
            val s = sourceInput.editText?.text.toString().trim()
            val t = targetInput.editText?.text.toString().trim()
            if (s.isNotEmpty() && t.isNotEmpty()) {
                val newRule = RedirectRule(s, t)
                if (existingRule != null) {
                    val index = currentRules.indexOf(existingRule)
                    if (index != -1) currentRules[index] = newRule
                } else {
                    currentRules.add(newRule)
                }
                adapter.submitList(currentRules.toList())
            }
        }
        builder.setNegativeButton(android.R.string.cancel, null)
        builder.show()
    }

    fun removeRule(rule: RedirectRule) {
        currentRules.remove(rule)
        adapter.submitList(currentRules.toList())
    }

    private fun saveAndExit() {
        ruleListPreference.rules = currentRules
        // 触发 PreferenceChangeListener 以便 CreateTemplateFragment 感知变化
        ruleListPreference.callChangeListener(currentRules) 
        dismiss()
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        // 逻辑主要在 saveAndExit 处理
    }

    companion object {
        fun newInstance(key: String?) =
            RedirectRuleListPreferenceFragmentCompat().apply {
                arguments = bundleOf(ARG_KEY to key)
            }
    }
}