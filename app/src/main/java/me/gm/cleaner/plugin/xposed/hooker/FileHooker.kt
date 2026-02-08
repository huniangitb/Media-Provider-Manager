package me.gm.cleaner.plugin.xposed.hooker

import android.os.Binder
import android.os.Process
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import me.gm.cleaner.plugin.xposed.ManagerService
import me.gm.cleaner.plugin.xposed.util.FileUtils
import java.io.File

class FileHooker(
    private val service: ManagerService,
    private val hostPackageName: String
) : XC_MethodHook() {
    
    // 路径标准化辅助函数
    private fun normalizePath(path: String): String {
        // 将 /data/media/0 替换为标准 /storage/emulated/0 以便匹配规则
        if (path.startsWith("/data/media/0")) {
            return path.replaceFirst("/data/media/0", "/storage/emulated/0")
        }
        return path
    }

    @Throws(Throwable::class)
    override fun beforeHookedMethod(param: MethodHookParam) {
        val file = param.thisObject as File
        // 使用标准化路径进行匹配
        val rawPath = file.absolutePath
        val path = normalizePath(rawPath)
        
        val uid = Binder.getCallingUid()
        val myUid = Process.myUid()
        
        val callingPackage = if (uid != myUid) {
            val packages = service.context.packageManager.getPackagesForUid(uid)
            packages?.firstOrNull() ?: "uid:$uid"
        } else {
            hostPackageName
        }

        val templates = service.ruleSp.templates.values.filter { it.redirectRules?.isNotEmpty() == true }
        
        for (template in templates) {
            if (!template.applyToApp.isNullOrEmpty() && 
                !template.applyToApp.contains(callingPackage) && 
                uid != myUid 
            ) {
                continue
            }
            
            template.redirectRules?.forEach { rule ->
                // 使用标准化后的 path 进行匹配
                if (FileUtils.contains(rule.source, path)) {
                    // 计算重定向路径
                    val redirectedPathStandard = path.replaceFirst(rule.source, rule.target)
                    
                    // 如果原始路径是 /data/media/0，我们需要把重定向后的路径也还原回 /data/media/0 
                    // 以便底层 Linux 系统调用能正确执行 (因为 ExternalStorageProvider 可能没有 /storage/ 挂载点的权限，只有底层权限)
                    val finalRedirectedPath = if (rawPath.startsWith("/data/media/0")) {
                        redirectedPathStandard.replaceFirst("/storage/emulated/0", "/data/media/0")
                    } else {
                        redirectedPathStandard
                    }

                    XposedBridge.log("MPM_FileGuard: Redirecting mkdir [$callingPackage] in [$hostPackageName]: $rawPath -> $finalRedirectedPath")
                    
                    val redirectedFile = File(finalRedirectedPath)
                    if (!redirectedFile.exists()) {
                        redirectedFile.mkdirs()
                    }
                    param.result = true 
                    return
                }
            }
        }
    }
}