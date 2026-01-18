/*
 * Copyright 2021 Green Mushroom
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.gm.cleaner.plugin.xposed.hooker

import android.os.Binder
import android.os.Environment
import android.os.Process
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
        
        // 1. 获取真实的调用者包名
        val callingPackage = getRealCallingPackage()

        // 2. 检查重定向规则
        val templates = service.ruleSp.templates.values.filter { 
            it.applyToApp?.contains(callingPackage) == true && it.redirectPath != null 
        }

        for (template in templates) {
            for (filter in template.filterPath ?: emptyList()) {
                if (FileUtils.contains(filter, path)) {
                    // 命中重定向规则
                    val targetPath = template.redirectPath!!
                    val redirectedPath = path.replaceFirst(filter, targetPath)
                    
                    XposedBridge.log("MPM_FileHooker: [$callingPackage] mkdir blocked/redirected: $path -> $redirectedPath")
                    
                    // 策略：不让它在原位置创建
                    // 尝试在重定向位置创建 (FUSE补充逻辑)
                    val redirectedFile = File(redirectedPath)
                    if (!redirectedFile.exists()) {
                        // 递归调用 mkdirs 会触发第二次 Hook，但因为路径已变，不会死循环
                        param.result = redirectedFile.mkdirs()
                    } else {
                        param.result = true
                    }
                    return
                }
            }
        }

        // 3. 原有的标准目录保护逻辑
        if (FileUtils.contains(FileUtils.externalStorageDirPath, file) &&
            standardParents.none { FileUtils.contains(it, file) }
        ) {
            XposedBridge.log("MPM_FileHooker: rejected standard dir restriction: $file")
            param.result = false
        }
    }

    private fun getRealCallingPackage(): String {
        val uid = Binder.getCallingUid()
        // 如果调用者 UID 和当前进程 UID (MediaProvider) 相同，说明是内部调用
        // 但这里我们主要关注"应用通过 MediaProvider 间接调用"的情况
        // MediaProvider 通常通过 Binder 响应请求
        
        if (uid == Process.myUid()) {
            // 如果是 MediaProvider 自己调用，尝试获取当前应用上下文
            // 注意：当 InsertHooker 工作时，callingPackage 已经在 MethodHookParam 里获取到了
            // 但 File.mkdir 没有这个参数，只能依赖 AndroidAppHelper (在非 MP 进程) 或者 Binder (在 MP 进程)
            
            // 简单的回退：如果是内部操作，可能是之前 InsertHooker 已经修改过路径了
            // 这里返回包名可能意义不大，或者是 "android.media"
            return "com.android.providers.media" 
        }

        // 如果是 IPC 调用，解析 UID 为包名
        val pm = service.context.packageManager
        val packages = pm.getPackagesForUid(uid)
        return if (!packages.isNullOrEmpty()) {
            packages[0] // 返回第一个包名，通常足够识别应用
        } else {
            "unknown($uid)"
        }
    }
}