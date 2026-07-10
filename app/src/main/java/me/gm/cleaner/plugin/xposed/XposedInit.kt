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

import android.app.Application
import android.content.pm.PackageManager
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import me.gm.cleaner.plugin.BuildConfig
import me.gm.cleaner.plugin.util.L
import me.gm.cleaner.plugin.util.ModuleActivationStore
import me.gm.cleaner.plugin.xposed.hooker.DeleteHooker
import me.gm.cleaner.plugin.xposed.hooker.FileHooker
import me.gm.cleaner.plugin.xposed.hooker.InsertHooker
import me.gm.cleaner.plugin.xposed.hooker.QueryHooker
import java.io.File

class XposedInit : XposedModule() {

    private lateinit var service: ManagerService

    @Throws(Throwable::class)
    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        super.onModuleLoaded(param)
        L.d("Module loaded: process=${param.processName}, isSystemServer=${param.isSystemServer}")
        // ManagerService will be created when we have a context (in onPackageReady)
    }

    @Throws(Throwable::class)
    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        super.onPackageReady(param)
        val packageName = param.getPackageName()
        val classLoader = param.getClassLoader()
        L.d("Package ready: $packageName")

        when (packageName) {
            BuildConfig.APPLICATION_ID -> {
                // Called when our own module app process is loaded
                // Mark the framework as active for the UI
                try {
                    markModuleAppLoaded(param)
                } catch (t: Throwable) {
                    L.e("Failed to mark module app as hooked", t)
                }
            }

            "com.android.providers.media.module",
            "com.android.providers.media",
            "com.google.android.providers.media.module" -> {
                onMediaProviderLoaded(classLoader, param)
            }

            "com.android.providers.downloads" -> {
                onDownloadManagerLoaded(classLoader)
            }
        }
    }

    @Throws(Throwable::class)
    private fun markModuleAppLoaded(param: XposedModuleInterface.PackageReadyParam) {
        L.d("Module app package ready: ${param.getPackageName()}")
        val classLoader = param.getClassLoader()
        val activityThread = Class.forName("android.app.ActivityThread", false, classLoader)
        val currentActivityThread = activityThread.getDeclaredMethod("currentActivityThread")
        currentActivityThread.isAccessible = true
        val at = currentActivityThread.invoke(null)
        val getApplication = activityThread.getDeclaredMethod("getApplication")
        getApplication.isAccessible = true
        val app = getApplication.invoke(at) as Application
        ModuleActivationStore.markAppProcessHooked(app)
        L.d("Marked module app process as hooked")
    }

    @Throws(Throwable::class)
    private fun onMediaProviderLoaded(
        classLoader: ClassLoader,
        param: XposedModuleInterface.PackageReadyParam
    ) {
        L.d("MediaProvider loaded: ${param.getPackageName()}")
        val mediaProvider = try {
            Class.forName("com.android.providers.media.MediaProvider", false, classLoader)
        } catch (e: ClassNotFoundException) {
            L.e("MediaProvider class not found!", e)
            return
        }

        // Initialize ManagerService with the package context
        // Note: In libxposed, the module runs in the target process,
        // so we can access the target app's resources directly
        if (!::service.isInitialized) {
            service = ManagerService()
            service.classLoader = classLoader
        }

        // Get the application context if available
        try {
            val activityThread = Class.forName("android.app.ActivityThread", false, classLoader)
            val currentActivityThread = activityThread.getDeclaredMethod("currentActivityThread")
            currentActivityThread.isAccessible = true
            val at = currentActivityThread.invoke(null)
            val getApplication = activityThread.getDeclaredMethod("getApplication")
            getApplication.isAccessible = true
            val app = getApplication.invoke(at) as? Application
            if (app == null) {
                L.e("Could not get Application context (null), hooks may not work properly")
                return
            }
            service.onCreate(app)
            // Use module's own Resources (not target app's) so module R.string IDs resolve correctly
            service.resources = try {
                val moduleAppInfo = getModuleApplicationInfo()
                app.packageManager.getResourcesForApplication(moduleAppInfo)
            } catch (t: Throwable) {
                L.e("Could not get module Resources, trying module package name", t)
                app.packageManager.getResourcesForApplication(BuildConfig.APPLICATION_ID)
            }
            L.d("ManagerService initialized with Application context")
        } catch (t: Throwable) {
            L.e("Could not get Application context, hooks may not work properly", t)
            return
        }

        try {
            // Hook queryInternal - match all overloads
            for (method in mediaProvider.declaredMethods) {
                when (method.name) {
                    "queryInternal" -> {
                        hook(method).intercept(QueryHooker(service))
                        L.d("Hooked queryInternal")
                    }
                    "insertFile" -> {
                        hook(method).intercept(InsertHooker(service))
                        L.d("Hooked insertFile")
                    }
                    "deleteInternal" -> {
                        hook(method).intercept(DeleteHooker(service))
                        L.d("Hooked deleteInternal")
                    }
                }
            }
        } catch (t: Throwable) {
            L.e("Error hooking MediaProvider", t)
        }
    }

    @Throws(Throwable::class)
    private fun onDownloadManagerLoaded(classLoader: ClassLoader) {
        // Hook File.mkdir and mkdirs for download manager.
        // FileHooker accepts ManagerService? (nullable) and handles null gracefully.
        if (!::service.isInitialized) {
            L.d("Download manager loaded before MediaProvider, hooking File operations without service")
        }
        val fileHooker = if (::service.isInitialized) FileHooker(service) else FileHooker(null)
        val fileClass = File::class.java
        try {
            val mkdir = fileClass.getDeclaredMethod("mkdir")
            hook(mkdir).intercept(fileHooker)
            L.d("Hooked File.mkdir")

            val mkdirs = fileClass.getDeclaredMethod("mkdirs")
            hook(mkdirs).intercept(fileHooker)
            L.d("Hooked File.mkdirs")
        } catch (t: Throwable) {
            L.e("Error hooking File operations", t)
        }
    }
}