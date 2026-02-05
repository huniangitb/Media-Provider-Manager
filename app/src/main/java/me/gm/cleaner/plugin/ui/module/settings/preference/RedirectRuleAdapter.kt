package me.gm.cleaner.plugin.ui.module.settings.preference

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.forEach
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import me.gm.cleaner.plugin.R
import me.gm.cleaner.plugin.databinding.PathListItemBinding
import me.gm.cleaner.plugin.model.RedirectRule
import java.io.File

class RedirectRuleAdapter(
    private val fragment: RedirectRuleListPreferenceFragmentCompat
) : ListAdapter<RedirectRule, RedirectRuleAdapter.ViewHolder>(CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(PathListItemBinding.inflate(LayoutInflater.from(parent.context)))

    @SuppressLint("RestrictedApi")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val binding = holder.binding
        val rule = getItem(position)
        // 显示格式：Source -> Target
        binding.title.text = "${rule.source} \n➜ ${rule.target}"
        
        binding.root.setOnClickListener {
            // 点击编辑 (复用添加对话框)
            fragment.showEditDialog(rule)
        }

        binding.root.setOnCreateContextMenuListener { menu, _, _ ->
            fragment.requireActivity().menuInflater.inflate(R.menu.item_delete, menu)
            menu.setHeaderTitle("Rule Action")
            menu.forEach {
                it.setOnMenuItemClickListener { item ->
                    if (item.itemId == R.id.menu_delete) {
                        fragment.removeRule(rule)
                        true
                    } else {
                        false
                    }
                }
            }
        }
    }

    class ViewHolder(val binding: PathListItemBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        private val CALLBACK = object : DiffUtil.ItemCallback<RedirectRule>() {
            override fun areItemsTheSame(oldItem: RedirectRule, newItem: RedirectRule): Boolean =
                oldItem == newItem
            override fun areContentsTheSame(oldItem: RedirectRule, newItem: RedirectRule): Boolean =
                oldItem == newItem
        }
    }
}