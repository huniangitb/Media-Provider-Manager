package me.gm.cleaner.plugin.xposed.hooker

import android.os.Binder
import android.os.Process
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import me.gm.cleaner.plugin.xposed.ManagerService
import me.gm.cleaner.plugin.xposed.util.FileUtils
import java.io.File

class FileHooker(private val service: ManagerService) : XC_MethodHook() {
    
    @Throws(Throwable::class)
    override fun beforeHookedMethod(param: MethodHookParam) {
        val file = param.thisObject as File
        val path = file.absolutePath
        val uid = Binder.getCallingUid()
        val myUid = Process.myUid()

        // 尝试解析真实的调用包名（解决归属问题）
        val callingPackage = if (uid != myUid) {
            val packages = service.context.packageManager.getPackagesForUid(uid)
            packages?.firstOrNull() ?: "uid:$uid"
        } else {
            // 如果是内部调用，可能是 DownloadManager 或 MediaScanner，
            // 此时我们没有很好的办法区分具体业务，只能标记为系统
            "com.android.providers.media"
        }

        val templates = service.ruleSp.templates.values.filter { it.redirectPath != null }

        for (template in templates) {
            // 如果规则指定了应用包名，且当前调用者不是该应用（也不是系统内部代劳），则跳过
            // 注意：对于 DownloadManager 下载的文件，UID 往往是系统，所以建议重定向规则尽量配置为"全局"或包含系统包名
            if (!template.applyToApp.isNullOrEmpty() && 
                !template.applyToApp.contains(callingPackage) && 
                uid != myUid // 系统内部操作通常放行检查，单纯看路径
            ) {
                continue
            }

            for (filter in template.filterPath ?: emptyList()) {
                if (FileUtils.contains(filter, path)) {
                    // 命中重定向规则：递归计算新路径
                    val redirectedPath = path.replaceFirst(filter, template.redirectPath!!)
                    
                    XposedBridge.log("MPM_FileGuard: Blocked mkdir at origin by [$callingPackage], moved to $redirectedPath")
                    
                    val redirectedFile = File(redirectedPath)
                    if (!redirectedFile.exists()) {
                        // 触发重定向位置的创建
                        // 注意：为了防止无限递归，确保 redirectedPath 不包含在 filter 中
                        redirectedFile.mkdirs()
                    }
                    
                    // 阻止原始路径创建
                    param.result = true 
                    return
                }
            }
        }
    }
}