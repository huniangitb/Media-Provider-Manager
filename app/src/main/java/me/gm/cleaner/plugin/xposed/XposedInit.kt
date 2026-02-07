package me.gm.cleaner.plugin.xposed

import android.content.ContentProvider
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.ProviderInfo
import android.content.res.AssetManager
import android.content.res.Resources
import android.provider.MediaStore
import de.robv.android.xposed.*
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import me.gm.cleaner.plugin.xposed.hooker.DeleteHooker
import me.gm.cleaner.plugin.xposed.hooker.FileHooker
import me.gm.cleaner.plugin.xposed.hooker.InsertHooker
import me.gm.cleaner.plugin.xposed.hooker.QueryHooker
import java.io.File

class XposedInit : ManagerService(), IXposedHookLoadPackage, IXposedHookZygoteInit {
    @Throws(Throwable::class)
    private fun onMediaProviderLoaded(lpparam: LoadPackageParam, context: Context) {
        val mediaProvider = try {
            XposedHelpers.findClass(
                "com.android.providers.media.MediaProvider", lpparam.classLoader
            )
        } catch (e: XposedHelpers.ClassNotFoundError) {
            return
        }
        classLoader = lpparam.classLoader
        onCreate(context)
        XposedBridge.hookAllMethods(
            mediaProvider, "queryInternal", QueryHooker(this@XposedInit)
        )
        XposedBridge.hookAllMethods(
            mediaProvider, "insertFile", InsertHooker(this@XposedInit)
        )
        XposedBridge.hookAllMethods(
            mediaProvider, "deleteInternal", DeleteHooker(this@XposedInit)
        )
    }

    @Throws(Throwable::class)
    private fun onDownloadManagerLoaded(lpparam: LoadPackageParam, context: Context) {
        // 修改：传入 DownloadManager 的包名
        val hooker = FileHooker(this@XposedInit, "com.android.providers.downloads")
        XposedHelpers.findAndHookMethod(File::class.java, "mkdir", hooker)
        XposedHelpers.findAndHookMethod(File::class.java, "mkdirs", hooker)
    }

    // 新增：外部存储加载时的逻辑
    @Throws(Throwable::class)
    private fun onExternalStorageLoaded(lpparam: LoadPackageParam, context: Context) {
        // 修改：传入 ExternalStorage 的包名
        val hooker = FileHooker(this@XposedInit, "com.android.externalstorage")
        XposedHelpers.findAndHookMethod(File::class.java, "mkdir", hooker)
        XposedHelpers.findAndHookMethod(File::class.java, "mkdirs", hooker)
    }

    @Throws(Throwable::class)
    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.appInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0) {
            return
        }
        XposedHelpers.findAndHookMethod(
            ContentProvider::class.java, "attachInfo",
            Context::class.java, ProviderInfo::class.java, Boolean::class.java,
            object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val context = param.args[0] as Context
                    val providerInfo = param.args[1] as ProviderInfo
                    when (providerInfo.authority) {
                        MediaStore.AUTHORITY -> onMediaProviderLoaded(lpparam, context)
                        Downloads_Impl_AUTHORITY -> onDownloadManagerLoaded(lpparam, context)
                        EXTERNAL_STORAGE_AUTHORITY -> onExternalStorageLoaded(lpparam, context) // 新增匹配
                    }
                }
            }
        )
    }

    @Throws(Throwable::class)
    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        val assetManager = AssetManager::class.java.newInstance()
        XposedHelpers.callMethod(assetManager, "addAssetPath", startupParam.modulePath)
        resources = Resources(assetManager, null, null)
    }

    companion object {
        const val Downloads_Impl_AUTHORITY = "downloads"
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents" // 新增常量
    }
}