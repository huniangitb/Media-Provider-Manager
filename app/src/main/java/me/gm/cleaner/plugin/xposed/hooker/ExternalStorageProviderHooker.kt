package me.gm.cleaner.plugin.xposed.hooker

import android.os.Build
import android.os.Bundle
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import me.gm.cleaner.plugin.xposed.ManagerService
import java.io.File

class ExternalStorageProviderHooker(private val service: ManagerService) : XC_MethodHook() {

    // 缓存 Document ID 的前缀，通常是 "primary:"
    private val ROOT_ID_PRIMARY = "primary"

    @Throws(Throwable::class)
    override fun beforeHookedMethod(param: MethodHookParam) {
        // 方法签名: String createDocument(String parentDocumentId, String mimeType, String displayName)
        val parentDocumentId = param.args[0] as String
        val mimeType = param.args[1] as String
        val displayName = param.args[2] as String

        // 1. 解析原始路径
        // ExternalStorageProvider 的 ID 格式通常是 "root:path/to/file"
        // 例如 "primary:Tencent/QQ" 对应 "/storage/emulated/0/Tencent/QQ"
        val splitIndex = parentDocumentId.indexOf(':')
        if (splitIndex == -1) return

        val rootId = parentDocumentId.substring(0, splitIndex)
        val relativePath = parentDocumentId.substring(splitIndex + 1)
        
        // 简单处理 primary 卷，其他卷逻辑类似
        if (rootId != ROOT_ID_PRIMARY) return

        val fullParentPath = "/storage/emulated/0/$relativePath".trimEnd('/')
        val targetFullPath = "$fullParentPath/$displayName"

        // 2. 查找匹配的重定向规则
        // 注意：SAF 只能重定向“显示名”(displayName) 或者需要非常复杂的逻辑去改变父目录 ID
        // 这里我们主要处理：如果目标路径在重定向源中，我们尝试修改文件名或拦截
        
        val templates = service.ruleSp.templates.values
        
        // 查找是否有规则匹配当前要把文件创建到的位置
        val activeRule = templates
            .flatMap { it.redirectRules ?: emptyList() }
            .filter { rule -> targetFullPath.startsWith(rule.source) }
            .maxByOrNull { it.source.length }

        if (activeRule != null) {
            XposedBridge.log("MPM_SAF_Hook: Detected creation at $targetFullPath, matching rule: ${activeRule.source} -> ${activeRule.target}")

            // 计算重定向后的路径
            val redirectedPath = targetFullPath.replaceFirst(activeRule.source, activeRule.target)
            val redirectedFile = File(redirectedPath)

            // 策略 A: 也就是最简单的策略，如果父目录没变，只是改名
            // 比如 source: .../AD  target: .../MPM_AD
            // 用户在 .../ 创建 AD，我们改成创建 MPM_AD
            if (File(activeRule.source).parent == File(activeRule.target).parent) {
                // 修改 displayName 参数 (args[2])
                // 注意：如果规则是重定向整个文件夹，这里需要提取重定向后文件名的最后一部分
                val newName = redirectedFile.name
                param.args[2] = newName
                XposedBridge.log("MPM_SAF_Hook: Redirecting SAF displayName to $newName")
            } else {
                // 策略 B: 跨目录重定向 (比较复杂)
                // 我们需要修改 parentDocumentId (args[0])
                // 这要求我们反向计算出 target 目录对应的 Document ID
                // 这需要确保 target 目录已存在，并且我们需要知道它的 ID 格式
                
                // 尝试构建新的 Parent ID
                val newParentPath = redirectedFile.parentFile?.absolutePath ?: return
                // 假设 newParentPath 也是在 /storage/emulated/0/ 下
                if (newParentPath.startsWith("/storage/emulated/0/")) {
                    val newRelativeParent = newParentPath.substring("/storage/emulated/0/".length)
                    val newParentId = "$ROOT_ID_PRIMARY:$newRelativeParent"
                    
                    // 确保物理目录存在
                    redirectedFile.parentFile?.mkdirs()
                    
                    param.args[0] = newParentId
                    param.args[2] = redirectedFile.name // 名字也可能变了
                    XposedBridge.log("MPM_SAF_Hook: Redirecting SAF parent to $newParentId")
                }
            }
        }
    }
}