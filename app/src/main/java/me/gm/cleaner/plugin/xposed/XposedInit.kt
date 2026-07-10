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

package me.gm.cleaner.plugin.xposed

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

    @Throws(Throwable::class)
    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        super.onPackageReady(param)
        val packageName = param.getPackageName()
        val classLoader = param.getClassLoader()
        L.d("Package ready: $packageName")

        when (packageName) {
            BuildConfig.APPLICATION_ID -> {
                L.d("Module app package ready")
            }

            "com.android.providers.media.module",
            "com.android.providers.media",
            "com.google.android.providers.media.module" -> {
                onMediaProviderPackageReady(classLoader)
            }

            "com.android.providers.downloads" -> {
                onDownloadManagerPackageReady(classLoader)
            }
        }
    }

    @Throws(Throwable::class)
    private fun onMediaProviderPackageReady(classLoader: ClassLoader) {
        L.d("MediaProvider package ready, hooking attachInfo")
        val mediaProviderClass = try {
            Class.forName("com.android.providers.media.MediaProvider", false, classLoader)
        } catch (e: ClassNotFoundException) {
            L.e("MediaProvider class not found!", e)
            return
        }

        // Hook MediaProvider.attachInfo to get the context and initialize service
        // after the provider is fully initialized
        try {
            val attachInfoMethod = try {
                // Try 3-param version first (Context, ProviderInfo, Boolean)
                mediaProviderClass.getDeclaredMethod(
                    "attachInfo",
                    Context::class.java,
                    ProviderInfo::class.java,
                    Boolean::class.java
                )
            } catch (e: NoSuchMethodException) {
                // Fall back to 2-param version (Context, ProviderInfo)
                mediaProviderClass.getDeclaredMethod(
                    "attachInfo",
                    Context::class.java,
                    ProviderInfo::class.java
                )
            }
            attachInfoMethod.isAccessible = true

            hook(attachInfoMethod).intercept { chain ->
                val context = chain.getArg(0) as Context
                val providerInfo = chain.getArg(1) as ProviderInfo

                // Only proceed for MediaStore authority
                if (providerInfo.authority != MediaStore.AUTHORITY &&
                    providerInfo.authority != "media" &&
                    providerInfo.authority != "com.google.android.providers.media.module"
                ) {
                    return@intercept chain.proceed()
                }

                L.d("MediaProvider.attachInfo called, authority=${providerInfo.authority}")

                // Let the original attachInfo run first
                val result = chain.proceed()

                // Now the provider is fully initialized, install hooks
                try {
                    installMediaProviderHooks(classLoader, context)
                } catch (t: Throwable) {
                    L.e("Error installing MediaProvider hooks", t)
                }

                result
            }
            L.d("Hooked MediaProvider.attachInfo")
        } catch (t: Throwable) {
            L.e("Error hooking MediaProvider.attachInfo", t)
        }
    }

    @Throws(Throwable::class)
    private fun installMediaProviderHooks(classLoader: ClassLoader, context: Context) {
        L.d("Installing MediaProvider hooks")

        val mediaProvider = try {
            Class.forName("com.android.providers.media.MediaProvider", false, classLoader)
        } catch (e: ClassNotFoundException) {
            L.e("MediaProvider class not found during hook installation!", e)
            return
        }

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
        L.d("ManagerService initialized")

        // Hook methods
        try {
            for (method in mediaProvider.declaredMethods) {
                when (method.name) {
                    "queryInternal" -> {
                        hook(method).intercept(QueryHooker(mgr))
                        L.d("Hooked queryInternal")
                    }
                    "insertFile" -> {
                        hook(method).intercept(InsertHooker(mgr))
                        L.d("Hooked insertFile")
                    }
                    "deleteInternal" -> {
                        hook(method).intercept(DeleteHooker(mgr))
                        L.d("Hooked deleteInternal")
                    }
                }
            }
        } catch (t: Throwable) {
            L.e("Error hooking MediaProvider methods", t)
        }
    }

    @Throws(Throwable::class)
    private fun onDownloadManagerPackageReady(classLoader: ClassLoader) {
        L.d("DownloadManager package ready, hooking File.mkdir/mkdirs")
        val fileClass = File::class.java
        try {
            val mkdir = fileClass.getDeclaredMethod("mkdir")
            hook(mkdir).intercept(FileHooker(service?.let {
                // Create a service instance if needed (without context initialization)
                // FileHooker handles null service gracefully
                null
            }))
            L.d("Hooked File.mkdir")

            val mkdirs = fileClass.getDeclaredMethod("mkdirs")
            hook(mkdirs).intercept(FileHooker(service?.let { null }))
            L.d("Hooked File.mkdirs")
        } catch (t: Throwable) {
            L.e("Error hooking File operations", t)
        }
    }
}