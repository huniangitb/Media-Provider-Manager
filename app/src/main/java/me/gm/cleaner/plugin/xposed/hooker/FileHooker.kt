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

import android.app.AndroidAppHelper
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
        
        // 1. 获取当前调用者的包名
        val callingPackage = AndroidAppHelper.currentPackageName()
        
        // 2. 获取该应用适用的重定向规则
        // 过滤出：应用匹配 且 配置了 redirectPath 的模板
        val templates = service.ruleSp.templates.values.filter {
            it.applyToApp?.contains(callingPackage) == true && !it.redirectPath.isNullOrBlank()
        }

        // 3. 尝试匹配路径并执行重定向
        for (template in templates) {
            val filterPaths = template.filterPath ?: continue
            for (filter in filterPaths) {
                if (FileUtils.contains(filter, path)) {
                    // 计算重定向后的目标路径
                    val redirectedPath = path.replaceFirst(filter, template.redirectPath!!)
                    val redirectedFile = File(redirectedPath)
                    
                    try {
                        // 执行实际的创建操作（重定向到新位置）
                        if (param.method.name == "mkdir") {
                            param.result = redirectedFile.mkdir()
                        } else {
                            param.result = redirectedFile.mkdirs()
                        }
                        XposedBridge.log("MPM_FileRedirect: $callingPackage redirected $path -> $redirectedPath")
                    } catch (e: Throwable) {
                        XposedBridge.log("MPM_FileRedirect_Error: ${e.message}")
                        param.result = false
                    }
                    // 一旦匹配并处理，直接返回，不再执行后续逻辑
                    return
                }
            }
        }

        // 4. 兜底逻辑：如果不在重定向规则内，执行原有的标准目录保护逻辑
        if (FileUtils.contains(FileUtils.externalStorageDirPath, file) &&
            standardParents.none { FileUtils.contains(it, file) }
        ) {
            XposedBridge.log("MPM_Rejected ${param.method.name}: $path (Package: $callingPackage)")
            param.result = false
        }
    }
}