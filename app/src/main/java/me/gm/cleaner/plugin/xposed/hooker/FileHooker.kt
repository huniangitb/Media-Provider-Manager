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
    private val hostPackageName: String // 新增：传入宿主包名
) : XC_MethodHook() {
    @Throws(Throwable::class)
    override fun beforeHookedMethod(param: MethodHookParam) {
        val file = param.thisObject as File
        val path = file.absolutePath
        val uid = Binder.getCallingUid()
        val myUid = Process.myUid()
        
        // 修改：如果 uid == myUid，说明是模块宿主（如 ExternalStorage 或 DownloadManager）自己在操作
        // 此时使用传入的 hostPackageName，而不是硬编码
        val callingPackage = if (uid != myUid) {
            val packages = service.context.packageManager.getPackagesForUid(uid)
            packages?.firstOrNull() ?: "uid:$uid"
        } else {
            hostPackageName
        }

        val templates = service.ruleSp.templates.values.filter { it.redirectRules?.isNotEmpty() == true } // 适配新版模型
        
        for (template in templates) {
            if (!template.applyToApp.isNullOrEmpty() && 
                !template.applyToApp.contains(callingPackage) && 
                uid != myUid 
            ) {
                continue
            }
            
            // 适配新的 RedirectRule 逻辑
            template.redirectRules?.forEach { rule ->
                if (FileUtils.contains(rule.source, path)) {
                    val redirectedPath = path.replaceFirst(rule.source, rule.target)
                    XposedBridge.log("MPM_FileGuard: Redirecting mkdir [$callingPackage] in [$hostPackageName]: $path -> $redirectedPath")
                    
                    val redirectedFile = File(redirectedPath)
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