package me.gm.cleaner.plugin.xposed

import android.content.ContentProvider
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.ProviderInfo
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Build
import android.provider.MediaStore
import de.robv.android.xposed.*
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import me.gm.cleaner.plugin.xposed.hooker.DeleteHooker
import me.gm.cleaner.plugin.xposed.hooker.FileHooker
import me.gm.cleaner.plugin.xposed.hooker.InsertHooker
import me.gm.cleaner.plugin.xposed.hooker.QueryHooker
import me.gm.cleaner.plugin.xposed.hooker.ExternalStorageProviderHooker
import java.io.File

class XposedInit : ManagerService(), IXposedHookLoadPackage, IXposedHookZygoteInit {

    /**
     * 当媒体存储进程加载时调用
     * 处理对 MediaStore 数据库的直接查询、插入和删除
     */
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
        
        // 挂钩数据库操作
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

    /**
     * 当下载管理器进程加载时调用
     * 某些应用会通过 DownloadManager 绕过直接的文件读写限制
     */
    @Throws(Throwable::class)
    private fun onDownloadManagerLoaded(lpparam: LoadPackageParam, context: Context) {
        classLoader = lpparam.classLoader
        onCreate(context)
        
        // 挂钩底层目录创建操作
        val hooker = FileHooker(this@XposedInit, "com.android.providers.downloads")
        XposedHelpers.findAndHookMethod(File::class.java, "mkdir", hooker)
        XposedHelpers.findAndHookMethod(File::class.java, "mkdirs", hooker)
    }

    /**
     * 当外部存储提供者加载时调用 (核心：处理 SAF 绕过)
     * 对应包名：com.android.externalstorage
     * 作用：拦截 DocumentTree/SAF 创建文件操作
     */
    @Throws(Throwable::class)
    private fun onExternalStorageLoaded(lpparam: LoadPackageParam, context: Context) {
        classLoader = lpparam.classLoader
        onCreate(context)

        // 1. 挂钩底层文件 API
        // ExternalStorageProvider 内部会直接操作 Java File 
        val fileHooker = FileHooker(this@XposedInit, "com.android.externalstorage")
        XposedHelpers.findAndHookMethod(File::class.java, "mkdir", fileHooker)
        XposedHelpers.findAndHookMethod(File::class.java, "mkdirs", fileHooker)

        // 2. 挂钩 SAF 通信层 (DocumentsProvider)
        // 很多应用获取了 /storage/emulated/0 的 SAF 权限后，会通过此接口创建文件
        try {
            val providerClass = XposedHelpers.findClass(
                "com.android.externalstorage.ExternalStorageProvider", 
                lpparam.classLoader
            )
            val safHooker = ExternalStorageProviderHooker(this@XposedInit)
            
            // 拦截 createDocument 方法
            XposedHelpers.findAndHookMethod(
                providerClass, 
                "createDocument", 
                String::class.java, // parentDocumentId
                String::class.java, // mimeType
                String::class.java, // displayName
                safHooker
            )
        } catch (e: Throwable) {
            XposedBridge.log("MPM_XposedInit: Failed to hook ExternalStorageProvider: ${e.message}")
        }
    }

    @Throws(Throwable::class)
    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        // 仅处理系统级存储应用
        if (lpparam.appInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0) {
            return
        }

        // 通过拦截 ContentProvider.attachInfo 来获取 Context 并初始化逻辑
        XposedHelpers.findAndHookMethod(
            ContentProvider::class.java, "attachInfo",
            Context::class.java, ProviderInfo::class.java, Boolean::class.java,
            object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val context = param.args[0] as Context
                    val providerInfo = param.args[1] as ProviderInfo
                    
                    when (providerInfo.authority) {
                        MediaStore.AUTHORITY -> {
                            onMediaProviderLoaded(lpparam, context)
                        }
                        Downloads_Impl_AUTHORITY -> {
                            onDownloadManagerLoaded(lpparam, context)
                        }
                        EXTERNAL_STORAGE_AUTHORITY -> {
                            onExternalStorageLoaded(lpparam, context)
                        }
                    }
                }
            }
        )
    }

    /**
     * 初始 Zygote 启动时获取模块资源，用于在 Hook 逻辑中访问 strings.xml 等
     */
    @Throws(Throwable::class)
    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        val assetManager = AssetManager::class.java.newInstance()
        XposedHelpers.callMethod(assetManager, "addAssetPath", startupParam.modulePath)
        resources = Resources(assetManager, null, null)
    }

    companion object {
        const val Downloads_Impl_AUTHORITY = "downloads"
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    }
}