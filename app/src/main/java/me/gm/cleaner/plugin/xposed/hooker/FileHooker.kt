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
import io.github.libxposed.api.XposedInterface
import me.gm.cleaner.plugin.util.L
import me.gm.cleaner.plugin.xposed.ManagerService
import me.gm.cleaner.plugin.xposed.util.FileUtils
import java.io.File

class FileHooker(private val service: ManagerService?) : XposedInterface.Hooker {
    companion object {
        private val reentryGuard = ThreadLocal.withInitial { false }
    }

    private val standardParents: List<File> =
        FileUtils.standardDirs.map { type -> Environment.getExternalStoragePublicDirectory(type) } +
                FileUtils.androidDir

    @Throws(Throwable::class)
    override fun intercept(chain: XposedInterface.Chain): Any? {
        // 防止递归：重定向路径的 mkdir 触发同一个 Xposed hook
        if (reentryGuard.get() == true) return chain.proceed()
        try {
            reentryGuard.set(true)
            val file = chain.thisObject as? File ?: return chain.proceed()
            val filePath = file.absolutePath

            // 1) 原有限制：非标准目录拒绝
            if (FileUtils.contains(FileUtils.externalStorageDirPath, file) &&
                standardParents.none { FileUtils.contains(it, file) }
            ) {
                L.d("rejected ${chain.executable.name}: $file")
                return false
            }

            // 2) 只读路径检查
            val templates = service?.ruleSp?.templates
            if (templates != null) {
                try {
                    if (templates.isReadOnlyPath(filePath)) {
                        return false
                    }
                } catch (_: Exception) {}
            }

            // 3) 重定向：若匹配 redirect_rules.source，则在 target 创建，返回 true
            if (templates != null) {
                try {
                    val redirectData = templates.resolveRedirect(filePath)
                    if (redirectData != filePath) {
                        val targetFile = File(redirectData)
                        return when (chain.executable.name) {
                            "mkdir" -> targetFile.mkdir()
                            "mkdirs" -> targetFile.mkdirs()
                            else -> null
                        }
                    }
                } catch (_: Exception) {}
            }

            return chain.proceed()
        } finally {
            reentryGuard.set(false)
        }
    }
}