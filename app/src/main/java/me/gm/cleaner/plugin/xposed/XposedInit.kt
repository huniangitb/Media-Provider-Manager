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
        classLoader = lpparam.classLoader
        onCreate(context)
        
        val hooker = FileHooker(this@XposedInit, "com.android.providers.downloads")
        XposedHelpers.findAndHookMethod(File::class.java, "mkdir", hooker)
        XposedHelpers.findAndHookMethod(File::class.java, "mkdirs", hooker)
    }

    @Throws(Throwable::class)
    private fun onExternalStorageLoaded(lpparam: LoadPackageParam, context: Context) {
        classLoader = lpparam.classLoader
        onCreate(context)

        val fileHooker = FileHooker(this@XposedInit, "com.android.externalstorage")
        XposedHelpers.findAndHookMethod(File::class.java, "mkdir", fileHooker)
        XposedHelpers.findAndHookMethod(File::class.java, "mkdirs", fileHooker)

        try {
            val providerClass = XposedHelpers.findClass(
                "com.android.externalstorage.ExternalStorageProvider", 
                lpparam.classLoader
            )
            val safHooker = ExternalStorageProviderHooker(this@XposedInit)
            
            XposedHelpers.findAndHookMethod(
                providerClass, 
                "createDocument", 
                String::class.java, 
                String::class.java, 
                String::class.java, 
                safHooker
            )
        } catch (e: Throwable) {
            XposedBridge.log("MPM_XposedInit: Failed to hook ExternalStorageProvider: ${e.message}")
        }
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
                    // 设备在加密状态（Direct Boot / FBE 未解锁）或系统初始化早期时，可能会传入 null 的 ProviderInfo。
                    // 此时如果强转会导致 NullPointerException，必须增加安全空校验。
                    val context = param.args[0] as? Context ?: return
                    val providerInfo = param.args[1] as? ProviderInfo ?: return
                    
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