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
    
    private fun normalizePath(path: String): String {
        if (path.startsWith("/data/media/0")) {
            return path.replaceFirst("/data/media/0", "/storage/emulated/0")
        }
        return path
    }

    @Throws(Throwable::class)
    override fun beforeHookedMethod(param: MethodHookParam) {
        val file = param.thisObject as File
        
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

        // 获取并按优先级排序模板：特定应用在前，全局在后
        val sortedTemplates = service.ruleSp.templates.values
            .filter { it.redirectRules?.isNotEmpty() == true }
            .sortedBy { if (it.applyToApp.isNullOrEmpty()) 1 else 0 }
        
        for (template in sortedTemplates) {
            val isGlobal = template.applyToApp.isNullOrEmpty()
            // 如果既不是全局规则，又不在匹配名单中，并且非本进程操作，跳过
            if (!isGlobal && !template.applyToApp!!.contains(callingPackage) && uid != myUid) {
                continue
            }
            
            template.redirectRules?.forEach { rule ->
                if (FileUtils.contains(rule.source, path)) {
                    val redirectedPathStandard = path.replaceFirst(rule.source, rule.target)
                    
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