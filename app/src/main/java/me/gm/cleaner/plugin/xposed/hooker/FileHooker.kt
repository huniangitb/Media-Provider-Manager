package me.gm.cleaner.plugin.xposed.hooker

import android.os.Binder
import android.os.Environment
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import me.gm.cleaner.plugin.xposed.ManagerService
import me.gm.cleaner.plugin.xposed.util.FileUtils
import java.io.File

class FileHooker(private val service: ManagerService) : XC_MethodHook() {
    
    private val standardParents: List<File> =
        FileUtils.standardDirs.map { type -> Environment.getExternalStoragePublicDirectory(type) } +
                FileUtils.androidDir

    @Throws(Throwable::class)
    override fun beforeHookedMethod(param: MethodHookParam) {
        val file = param.thisObject as File
        val path = file.absolutePath
        val uid = Binder.getCallingUid()

        // 策略：如果不是应用直接调用，而是 MediaProvider 内部代劳 (UID = Process.myUid)
        // 我们依然要检查路径是否命中重定向规则。
        // 如果命中，说明 MediaProvider 正在尝试在“错误”的原始位置创建空文件夹。
        
        val templates = service.ruleSp.templates.values.filter { it.redirectPath != null }

        for (template in templates) {
            for (filter in template.filterPath ?: emptyList()) {
                if (FileUtils.contains(filter, path)) {
                    // 无论谁调用的，只要路径是我们要重定向的原始路径，就不允许在这里创建
                    val redirectedPath = path.replaceFirst(filter, template.redirectPath!!)
                    
                    // 默默重定向创建请求到新位置
                    val redirectedFile = File(redirectedPath)
                    if (!redirectedFile.exists()) {
                        redirectedFile.mkdirs()
                    }
                    
                    // 阻止原位置创建
                    param.result = true 
                    XposedBridge.log("MPM_FileGuard: Blocked mkdir at origin, moved to $redirectedPath (UID: $uid)")
                    return
                }
            }
        }

        // 原有的标准目录保护逻辑
        if (FileUtils.contains(FileUtils.externalStorageDirPath, file) &&
            standardParents.none { FileUtils.contains(it, file) }
        ) {
            XposedBridge.log("rejected ${param.method.name}: $file")
            param.result = false
        }
    }
}