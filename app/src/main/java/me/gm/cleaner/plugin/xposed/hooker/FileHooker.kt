/*
 * Copyright 2021 Green Mushroom
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *     required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.gm.cleaner.plugin.xposed.hooker

import android.os.Environment
import de.robv.android.xposed.XC_MethodHook
import me.gm.cleaner.plugin.util.L
import me.gm.cleaner.plugin.xposed.ManagerService
import me.gm.cleaner.plugin.xposed.util.FileUtils
import java.io.File

class FileHooker(private val service: ManagerService?) : XC_MethodHook() {
    companion object {
        private val reentryGuard = ThreadLocal<Boolean>()
    }

    private val standardParents: List<File> =
        FileUtils.standardDirs.map { type -> Environment.getExternalStoragePublicDirectory(type) } +
                FileUtils.androidDir

    @Throws(Throwable::class)
    override fun beforeHookedMethod(param: MethodHookParam) {
        // 防止递归：重定向路径的 mkdir 触发同一个 Xposed hook
        if (reentryGuard.get() == true) return
        reentryGuard.set(true)
        try {
            val file = param.thisObject as File
            val filePath = file.absolutePath

            // 1) 原有限制：非标准目录拒绝
            if (FileUtils.contains(FileUtils.externalStorageDirPath, file) &&
                standardParents.none { FileUtils.contains(it, file) }
            ) {
                L.d("rejected ${param.method.name}: $file")
                param.result = false
                return
            }

            // 2) 只读路径检查
            val templates = service?.ruleSp?.templates ?: return
            try {
                if (templates.isReadOnlyPath(filePath)) {
                    param.result = false
                    return
                }
            } catch (_: Exception) {}

            // 3) 重定向：若匹配 redirect_rules.source，则在 target 创建，返回 true
            try {
                val redirectData = templates.resolveRedirect(filePath)
                if (redirectData != filePath) {
                    val targetFile = File(redirectData)
                    param.result = when (param.method.name) {
                        "mkdir" -> targetFile.mkdir()
                        "mkdirs" -> targetFile.mkdirs()
                        else -> null
                    }
                }
            } catch (_: Exception) {}
        } finally {
            reentryGuard.set(null)
        }
    }
}