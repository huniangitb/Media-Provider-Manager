package me.gm.cleaner.plugin.ui.module.settings.preference

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.core.os.bundleOf
import androidx.preference.PreferenceDialogFragmentCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import me.gm.cleaner.plugin.R
import me.gm.cleaner.plugin.databinding.DialogEditRedirectRuleBinding
import me.gm.cleaner.plugin.ktx.overScrollIfContentScrollsPersistent
import me.gm.cleaner.plugin.model.RedirectRule
import rikka.recyclerview.fixEdgeEffect

class RedirectRuleListPreferenceFragmentCompat : PreferenceDialogFragmentCompat() {
    private val ruleListPreference by lazy { preference as RedirectRuleListPreference }
    private lateinit var adapter: RedirectRuleAdapter
    
    var currentRules = mutableListOf<RedirectRule>()
    
    // 暂存当前正在编辑的 Binding，用于文件选择器回调更新 UI
    private var editingBinding: DialogEditRedirectRuleBinding? = null

    private val openDocumentTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            val file = treeUriToFile(it, requireContext())
            editingBinding?.targetEditText?.setText(file?.path)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 全屏对话框样式
        setStyle(STYLE_NORMAL, R.style.AppTheme_FullScreenDialog)
        adapter = RedirectRuleAdapter(this)
        currentRules = ruleListPreference.rules.toMutableList()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?) =
        object : Dialog(requireContext(), theme) {
            override fun onBackPressed() {
                dismiss()
            }
        }.apply {
            val contentView = onCreateDialogView(context)
            if (contentView != null) {
                onBindDialogView(contentView)
                setContentView(contentView)
            }
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

    @SuppressLint("RestrictedApi")
    override fun onBindDialogView(view: View) {
        super.onBindDialogView(view)
        
        view.findViewById<Toolbar>(R.id.toolbar).apply {
            setNavigationOnClickListener { dismiss() }
            setNavigationIcon(R.drawable.ic_outline_close_24)
            title = ruleListPreference.dialogTitle
            inflateMenu(R.menu.toolbar_save)
            setOnMenuItemClickListener { item ->
                if (item.itemId == R.id.menu_save) {
                    saveAndExit()
                    true
                } else false
            }
        }

        val list = view.findViewById<RecyclerView>(R.id.list)
        list.adapter = adapter
        list.layoutManager = GridLayoutManager(requireContext(), 1)
        list.fixEdgeEffect(false)
        list.overScrollIfContentScrollsPersistent()
        
        adapter.submitList(currentRules.toList())

        view.findViewById<FloatingActionButton>(R.id.fab).setOnClickListener {
            showEditDialog(null)
        }
    }

    fun showEditDialog(existingRule: RedirectRule?) {
        val context = requireContext()
        val binding = DialogEditRedirectRuleBinding.inflate(LayoutInflater.from(context))
        editingBinding = binding

        existingRule?.let {
            binding.sourceEditText.setText(it.source)
            binding.targetEditText.setText(it.target)
        }

        binding.targetInputLayout.setEndIconOnClickListener {
            openDocumentTreeLauncher.launch(null)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(if (existingRule == null) R.string.add_redirect_rule else R.string.edit_redirect_rule)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val s = binding.sourceEditText.text.toString().trim()
                val t = binding.targetEditText.text.toString().trim()
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
            .setNegativeButton(android.R.string.cancel) { _, _ -> editingBinding = null }
            .setOnDismissListener { editingBinding = null }
            .show()
    }

    fun removeRule(rule: RedirectRule) {
        currentRules.remove(rule)
        adapter.submitList(currentRules.toList())
    }

    private fun saveAndExit() {
        ruleListPreference.rules = currentRules
        ruleListPreference.callChangeListener(currentRules) 
        dismiss()
    }

    override fun onDialogClosed(positiveResult: Boolean) {}

    companion object {
        fun newInstance(key: String?) =
            RedirectRuleListPreferenceFragmentCompat().apply {
                arguments = bundleOf(ARG_KEY to key)
            }
    }
}