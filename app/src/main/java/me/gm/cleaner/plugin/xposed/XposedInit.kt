/*
 * Copyright 2021 Green Mushroom
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.gm.cleaner.plugin.xposed

import android.content.ContentProvider
import android.content.Context
import android.content.pm.ProviderInfo
import android.provider.MediaStore
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import me.gm.cleaner.plugin.BuildConfig
import me.gm.cleaner.plugin.util.L
import me.gm.cleaner.plugin.xposed.hooker.DeleteHooker
import me.gm.cleaner.plugin.xposed.hooker.FileHooker
import me.gm.cleaner.plugin.xposed.hooker.InsertHooker
import me.gm.cleaner.plugin.xposed.hooker.QueryHooker
import java.io.File

class XposedInit : XposedModule() {

    private var service: ManagerService? = null

    @Throws(Throwable::class)
    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        super.onModuleLoaded(param)
        L.d("Module loaded: process=${param.processName}, isSystemServer=${param.isSystemServer}")
    }

    /**
     * onPackageLoaded — 早于 installContentProviders，此时 ContentProvider 尚未实例化。
     * 在此 hook ContentProvider.attachInfo 基类，确保在 attachInfo 被调用前 hook 已就绪。
     *
     * onPackageReady 在 AppComponentFactory 实例化类加载器后被调用，此时
     * installContentProviders 已经执行完毕，ContentProvider.attachInfo 已调用过，
     * 再 hook 已经太晚。
     */
    @Throws(Throwable::class)
    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        super.onPackageLoaded(param)
        val packageName = param.getPackageName()
        L.d("Package loaded: $packageName")

        when (packageName) {
            // MediaProvider 相关包：在 installContentProviders 之前 hook attachInfo
            "com.android.providers.media.module",
            "com.android.providers.media",
            "com.google.android.providers.media.module" -> {
                hookContentProviderAttachInfo()
            }
        }
    }

    @Throws(Throwable::class)
    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        super.onPackageReady(param)
        val packageName = param.getPackageName()
        val classLoader = param.getClassLoader()
        L.d("Package ready: $packageName")

        // onPackageReady 在 installContentProviders 之后调用，不用于 hook attachInfo
        // 仅用于 DownloadManager 的 File.mkdir/mkdirs hook（不依赖 ContentProvider 时序）
        when (packageName) {
            BuildConfig.APPLICATION_ID -> {
                L.d("Module app package ready")
            }

            "com.android.providers.media.module",
            "com.android.providers.media",
            "com.google.android.providers.media.module" -> {
                L.d("MediaProvider package ready (attachInfo hook already installed in onPackageLoaded)")
            }

            "com.android.providers.downloads" -> {
                onDownloadManagerPackageReady(classLoader)
            }
        }
    }

    /**
     * Hook ContentProvider.attachInfo（基类，系统类，始终可用）。
     * 在 attachInfo 调用时（before阶段），MediaProvider 类已加载到 classLoader，
     * 此时可以安全地用 Class.forName 查找并 hook 其业务方法。
     *
     * 这是对迁移前 handleLoadPackage 中 hook ContentProvider.attachInfo beforeHookedMethod 的还原。
     */
    @Throws(Throwable::class)
    private fun hookContentProviderAttachInfo() {
        L.d("Hooking ContentProvider.attachInfo for MediaProvider")
        try {
            // attachInfo 有两个重载：3参(Context, ProviderInfo, boolean) 和 2参(Context, ProviderInfo)
            // 优先 3参版本，找不到再回退 2参
            val attachInfoMethod = try {
                ContentProvider::class.java.getDeclaredMethod(
                    "attachInfo",
                    Context::class.java,
                    ProviderInfo::class.java,
                    java.lang.Boolean.TYPE
                )
            } catch (e: NoSuchMethodException) {
                ContentProvider::class.java.getDeclaredMethod(
                    "attachInfo",
                    Context::class.java,
                    ProviderInfo::class.java
                )
            }
            attachInfoMethod.isAccessible = true

            hook(attachInfoMethod).intercept { chain ->
                val providerInfo = chain.getArg(1) as ProviderInfo

                // 按 authority 分流：MediaStore authority → 安装 MediaProvider 业务 hook
                // downloads authority → 安装 DownloadManager File.mkdir hook
                when (providerInfo.authority) {
                    MediaStore.AUTHORITY,
                    "media",
                    "com.android.providers.media.module",
                    "com.google.android.providers.media.module" -> {
                        val thisObj = chain.getThisObject()
                        if (thisObj == null) {
                            L.e("attachInfo hook: thisObject is null, cannot install hooks")
                            return@intercept chain.proceed()
                        }
                        val context = chain.getArg(0) as Context
                        L.e("MediaProvider.attachInfo called, authority=${providerInfo.authority}, installing hooks")
                        try {
                            // 用 chain.getThisObject().javaClass 获取 MediaProvider.class，
                            // 不需要 Class.forName，避免 classLoader 问题
                            onMediaProviderLoaded(thisObj.javaClass, context)
                        } catch (t: Throwable) {
                            L.e("Error installing MediaProvider hooks", t)
                        }
                    }

                    "downloads" -> {
                        L.d("DownloadManager.attachInfo called, authority=downloads")
                    }

                    else -> {
                        // 其他 provider 放行
                    }
                }

                // 执行原始 attachInfo
                chain.proceed()
            }
            L.d("Hooked ContentProvider.attachInfo")
        } catch (t: Throwable) {
            L.e("Error hooking ContentProvider.attachInfo", t)
        }
    }

    /**
     * 安装 MediaProvider 业务 hook（queryInternal / insertFile / deleteInternal）。
     *
     * 对应迁移前 onMediaProviderLoaded 的核心逻辑：
     * - 此时 classLoader 已完整初始化（attachInfo 调用时类加载器已就绪）
     * - Class.forName("...MediaProvider", false, classLoader) 必然成功
     * - 遍历 declaredMethods 按方法名 hook（等价于 XposedBridge.hookAllMethods 按名 hook 所有重载）
     *   含父类继承链查找，确保 hook 到从父类继承的方法
     */
    @Throws(Throwable::class)
    private fun onMediaProviderLoaded(mediaProvider: Class<*>, context: Context) {
        L.e("MediaProviderLoaded: loaded ${mediaProvider.name}")

        // 用 MediaProvider 类自身的 classLoader
        val classLoader = mediaProvider.classLoader

        // Initialize ManagerService
        val mgr = ManagerService()
        mgr.classLoader = classLoader
        mgr.onCreate(context)
        mgr.resources = try {
            val moduleAppInfo = getModuleApplicationInfo()
            context.packageManager.getResourcesForApplication(moduleAppInfo)
        } catch (t: Throwable) {
            L.e("Could not get module Resources, trying module package name", t)
            context.packageManager.getResourcesForApplication(BuildConfig.APPLICATION_ID)
        }
        service = mgr
        L.e("MediaProviderLoaded", "ManagerService initialized")

        // hook 业务方法：遍历类继承链每层 declaredMethods，按名匹配
        // 等价于迁移前 XposedBridge.hookAllMethods(mediaProvider, "queryInternal", ...)
        try {
            var hookedQuery = 0
            var hookedInsert = 0
            var hookedDelete = 0
            var c: Class<*>? = mediaProvider
            while (c != null && c != Any::class.java && c != Object::class.java) {
                for (method in c.declaredMethods) {
                    when (method.name) {
                        "queryInternal" -> {
                            hook(method).intercept(QueryHooker(mgr))
                            hookedQuery++
                            L.e("MediaProviderLoaded", "Hooked queryInternal: ${method.toGenericString()}")
                        }
                        "insertFile" -> {
                            hook(method).intercept(InsertHooker(mgr))
                            hookedInsert++
                            L.e("MediaProviderLoaded", "Hooked insertFile: ${method.toGenericString()}")
                        }
                        "deleteInternal" -> {
                            hook(method).intercept(DeleteHooker(mgr))
                            hookedDelete++
                            L.e("MediaProviderLoaded", "Hooked deleteInternal: ${method.toGenericString()}")
                        }
                    }
                }
                c = c.superclass
            }
            L.e("MediaProviderLoaded", "Hook installation summary: queryInternal=$hookedQuery, insertFile=$hookedInsert, deleteInternal=$hookedDelete")
            if (hookedQuery == 0) {
                L.e("MediaProviderLoaded", "queryInternal NOT FOUND on ${mediaProvider.name} — query records will be missing")
                dumpMediaProviderMethods(mediaProvider)
            }
            if (hookedInsert == 0) {
                L.e("MediaProviderLoaded", "insertFile NOT FOUND on ${mediaProvider.name}")
            }
            if (hookedDelete == 0) {
                L.e("MediaProviderLoaded", "deleteInternal NOT FOUND on ${mediaProvider.name}")
            }
        } catch (t: Throwable) {
            L.e("Error hooking MediaProvider methods", t)
        }
    }

    @Throws(Throwable::class)
    private fun dumpMediaProviderMethods(mediaProvider: Class<*>) {
        val lines = mutableListOf<String>()
        var c: Class<*>? = mediaProvider
        while (c != null) {
            val methods = c.declaredMethods.map { it.name }.distinct().sorted()
            lines.add("${c.name} declaredMethods: ${methods.joinToString(", ")}")
            c = c.superclass
        }
        L.e("MediaProviderLoaded", "MediaProvider method dump:\n  ${lines.joinToString("\n  ")}")
    }

    @Throws(Throwable::class)
    private fun onDownloadManagerPackageReady(classLoader: ClassLoader) {
        L.d("DownloadManager package ready, hooking File.mkdir/mkdirs")
        val fileClass = File::class.java
        try {
            val mkdir = fileClass.getDeclaredMethod("mkdir")
            hook(mkdir).intercept(FileHooker(null))
            L.d("Hooked File.mkdir")

            val mkdirs = fileClass.getDeclaredMethod("mkdirs")
            hook(mkdirs).intercept(FileHooker(null))
            L.d("Hooked File.mkdirs")
        } catch (t: Throwable) {
            L.e("Error hooking File operations", t)
        }
    }
}