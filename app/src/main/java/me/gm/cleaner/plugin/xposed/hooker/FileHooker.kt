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
        val callingPackage = if (uid != myUid) {
            val packages = service.context.packageManager.getPackagesForUid(uid)
            packages?.firstOrNull() ?: "uid:$uid"
        } else {
            "com.android.providers.media"
        }

        val templates = service.ruleSp.templates.values // 获取所有模板，后续可筛选
        
        for (template in templates) {
            // 检查应用匹配
            if (!template.applyToApp.isNullOrEmpty() && 
                !template.applyToApp.contains(callingPackage) && 
                uid != myUid 
            ) {
                continue
            }

            // 检查重定向规则
            template.redirectRules?.forEach { rule ->
                if (FileUtils.contains(rule.source, path)) {
                    val redirectedPath = path.replaceFirst(rule.source, rule.target)
                    XposedBridge.log("MPM_FileGuard: Redirecting mkdir [$callingPackage]: $path -> $redirectedPath")
                    
                    val redirectedFile = File(redirectedPath)
                    if (!redirectedFile.exists()) {
                        redirectedFile.mkdirs()
                    }
                    // 阻止原始路径创建，假装成功
                    param.result = true 
                    return
                }
            }
        }
    }
}