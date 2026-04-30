package me.gm.cleaner.plugin.ui.module.appmanagement

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.forEach
import androidx.core.view.isVisible
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.transition.platform.Hold
import com.google.gson.Gson
import me.gm.cleaner.plugin.R
import me.gm.cleaner.plugin.databinding.AppHeaderBinding
import me.gm.cleaner.plugin.databinding.TemplatesHeaderBinding
import me.gm.cleaner.plugin.databinding.TemplatesItemBinding
import me.gm.cleaner.plugin.ktx.DividerViewHolder
import me.gm.cleaner.plugin.ktx.mediumAnimTime
import me.gm.cleaner.plugin.model.Template
import me.gm.cleaner.plugin.model.Templates
import me.gm.cleaner.plugin.ui.module.settings.CreateTemplateFragment

class AppHeaderAdapter(private val fragment: AppFragment) :
    RecyclerView.Adapter<AppHeaderAdapter.ViewHolder>() {
    private val args: AppFragmentArgs by fragment.navArgs()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(AppHeaderBinding.inflate(LayoutInflater.from(parent.context)))

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val binding = holder.binding
        Glide.with(fragment)
            .load(args.packageInfo)
            .into(binding.icon)
        binding.labelVersion.text = "${args.label} ${args.packageInfo.versionName}"
        binding.packageName.text = args.packageInfo.packageName
        binding.sdk.text = "SDK ${args.packageInfo.applicationInfo?.targetSdkVersion ?: 0}"
        binding.sdk.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", args.packageInfo.packageName, null)
            }
            fragment.startActivity(intent)
        }
        binding.usageTimes.isVisible = false
    }

    override fun getItemCount(): Int = 1

    class ViewHolder(val binding: AppHeaderBinding) : DividerViewHolder(binding.root) {
        init {
            isDividerAllowedBelow = true
        }
    }
}

class TemplatesAdapter(private val fragment: AppFragment) :
    ListAdapter<Template, TemplatesAdapter.ViewHolder>(CALLBACK) {
    private val activity = fragment.requireActivity() as AppCompatActivity

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(TemplatesItemBinding.inflate(LayoutInflater.from(parent.context)))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val binding = holder.binding
        val item = getItem(position)
        val templateName = item.templateName
        binding.title.text = templateName
        binding.summary.text = fragment.getString(
            R.string.applied_app_count, item.applyToApp?.size ?: 0
        )
        binding.root.transitionName = templateName
        binding.root.setOnClickListener {
            val navController = fragment.findNavController()
            if (navController.currentDestination?.id != R.id.app_fragment) {
                return@setOnClickListener
            }
            fragment.lastTemplateName = templateName
            fragment.exitTransition = Hold().apply {
                duration = fragment.requireContext().mediumAnimTime
            }
            fragment.setExitSharedElementCallback(null)

            val direction = AppFragmentDirections.actionAppToCreateTemplate(templateName)
            val extras = FragmentNavigatorExtras(it to it.transitionName)
            navController.navigate(direction, extras)
        }
        binding.root.setOnCreateContextMenuListener { menu, _, _ ->
            activity.menuInflater.inflate(R.menu.app_item, menu)
            menu.setHeaderTitle(templateName)
            menu.forEach {
                it.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.menu_remove_from_template -> {
                            val modified =
                                Templates(fragment.binderViewModel.readSp(R.xml.template_preferences))
                                    .values.toMutableList()
                            val oldTemplateIndex =
                                modified.indexOfFirst { it.templateName == templateName }
                            val oldTemplate = modified[oldTemplateIndex]
                            modified[oldTemplateIndex] = oldTemplate.copy(
                                applyToApp = (oldTemplate.applyToApp ?: emptyList()) -
                                        fragment.args.packageInfo.packageName
                            )
                            fragment.binderViewModel.writeSp(
                                R.xml.template_preferences, Gson().toJson(modified)
                            )
                            true
                        }

                        R.id.menu_delete -> {
                            val modified =
                                Templates(fragment.binderViewModel.readSp(R.xml.template_preferences))
                                    .values.filterNot { it.templateName == templateName }
                            fragment.binderViewModel.writeSp(
                                R.xml.template_preferences, Gson().toJson(modified)
                            )
                            true
                        }

                        else -> {
                            false
                        }
                    }
                }
            }
        }
        holder.isDividerAllowedBelow = position == itemCount - 1

        if (fragment.lastTemplateName == templateName) {
            fragment.startPostponedEnterTransition()
        }
    }

    class ViewHolder(val binding: TemplatesItemBinding) : DividerViewHolder(binding.root)

    companion object {
        private val CALLBACK = object : DiffUtil.ItemCallback<Template>() {
            override fun areItemsTheSame(oldItem: Template, newItem: Template): Boolean =
                oldItem.templateName == newItem.templateName

            override fun areContentsTheSame(oldItem: Template, newItem: Template): Boolean =
                oldItem == newItem
        }
    }
}

class CreateTemplateAdapter(private val fragment: AppFragment) :
    RecyclerView.Adapter<CreateTemplateAdapter.ViewHolder>() {
    private val args: AppFragmentArgs by fragment.navArgs()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(TemplatesHeaderBinding.inflate(LayoutInflater.from(parent.context)))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val binding = holder.binding
        binding.root.transitionName = args.label
        binding.root.setOnClickListener {
            val navController = fragment.findNavController()
            if (navController.currentDestination?.id != R.id.app_fragment) {
                return@setOnClickListener
            }
            fragment.lastTemplateName = args.label
            fragment.exitTransition = Hold().apply {
                duration = fragment.requireContext().mediumAnimTime
            }
            fragment.setExitSharedElementCallback(null)

            val direction = AppFragmentDirections.actionAppToCreateTemplate(
                templateName = args.label,
                packageNames = arrayOf(args.packageInfo.packageName),
            )
            val extras = FragmentNavigatorExtras(it to it.transitionName)
            navController.navigate(direction, extras)
        }

        if (fragment.lastTemplateName == CreateTemplateFragment.NULL_TEMPLATE_NAME) {
            fragment.startPostponedEnterTransition()
        }
    }

    override fun getItemCount(): Int = 1

    class ViewHolder(val binding: TemplatesHeaderBinding) : DividerViewHolder(binding.root) {
        init {
            isDividerAllowedAbove = true
        }
    }
}

class AddToExistingTemplateAdapter(private val fragment: AppFragment) :
    ListAdapter<Template, AddToExistingTemplateAdapter.ViewHolder>(CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(TemplatesHeaderBinding.inflate(LayoutInflater.from(parent.context)))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val binding = holder.binding
        val item = getItem(position)
        val templateName = item.templateName
        binding.title.text = fragment.getString(
            R.string.add_to_existing_template_title, templateName
        )
        binding.root.transitionName = templateName
        binding.root.setOnClickListener {
            val modified = Templates(fragment.binderViewModel.readSp(R.xml.template_preferences))
                .values.toMutableList()
            val oldTemplateIndex = modified.indexOfFirst { it.templateName == templateName }
            val oldTemplate = modified[oldTemplateIndex]
            modified[oldTemplateIndex] = oldTemplate.copy(
                applyToApp = mutableListOf(fragment.args.packageInfo.packageName) +
                        (oldTemplate.applyToApp ?: emptyList())
            )
            fragment.binderViewModel.writeSp(
                R.xml.template_preferences, Gson().toJson(modified)
            )
        }

        if (fragment.lastTemplateName == templateName) {
            fragment.startPostponedEnterTransition()
        }
    }

    class ViewHolder(val binding: TemplatesHeaderBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        private val CALLBACK = object : DiffUtil.ItemCallback<Template>() {
            override fun areItemsTheSame(oldItem: Template, newItem: Template): Boolean =
                oldItem.templateName == newItem.templateName

            override fun areContentsTheSame(oldItem: Template, newItem: Template): Boolean =
                oldItem == newItem
        }
    }
}