package me.gm.cleaner.plugin.xposed.hooker

import android.os.Build
import android.os.Bundle
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import me.gm.cleaner.plugin.xposed.ManagerService
import java.io.File

class ExternalStorageProviderHooker(private val service: ManagerService) : XC_MethodHook() {

    private val ROOT_ID_PRIMARY = "primary"

    @Throws(Throwable::class)
    override fun beforeHookedMethod(param: MethodHookParam) {
        val parentDocumentId = param.args[0] as String
        val mimeType = param.args[1] as String
        val displayName = param.args[2] as String

        val splitIndex = parentDocumentId.indexOf(':')
        if (splitIndex == -1) return

        val rootId = parentDocumentId.substring(0, splitIndex)
        val relativePath = parentDocumentId.substring(splitIndex + 1)
        
        if (rootId != ROOT_ID_PRIMARY) return

        val fullParentPath = "/storage/emulated/0/$relativePath".trimEnd('/')
        val targetFullPath = "$fullParentPath/$displayName"

        val templates = service.ruleSp.templates.values
        
        // 解析重定向规则：附带优先级标签，应用级别优先于全局级别
        val activeRule = templates
            .flatMap { template ->
                val isGlobal = template.applyToApp.isNullOrEmpty()
                template.redirectRules?.map { rule -> Pair(isGlobal, rule) } ?: emptyList()
            }
            .filter { (_, rule) -> targetFullPath.startsWith(rule.source) }
            .minWithOrNull(compareBy({ it.first }, { -it.second.source.length }))
            ?.second

        if (activeRule != null) {
            XposedBridge.log("MPM_SAF_Hook: Detected creation at $targetFullPath, matching rule: ${activeRule.source} -> ${activeRule.target}")

            val redirectedPath = targetFullPath.replaceFirst(activeRule.source, activeRule.target)
            val redirectedFile = File(redirectedPath)

            if (File(activeRule.source).parent == File(activeRule.target).parent) {
                val newName = redirectedFile.name
                param.args[2] = newName
                XposedBridge.log("MPM_SAF_Hook: Redirecting SAF displayName to $newName")
            } else {
                val newParentPath = redirectedFile.parentFile?.absolutePath ?: return
                
                if (newParentPath.startsWith("/storage/emulated/0/")) {
                    val newRelativeParent = newParentPath.substring("/storage/emulated/0/".length)
                    val newParentId = "$ROOT_ID_PRIMARY:$newRelativeParent"
                    
                    redirectedFile.parentFile?.mkdirs()
                    
                    param.args[0] = newParentId
                    param.args[2] = redirectedFile.name 
                    XposedBridge.log("MPM_SAF_Hook: Redirecting SAF parent to $newParentId")
                }
            }
        }
    }
}