package me.gm.cleaner.plugin.xposed.hooker

import android.content.ClipDescription
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.TextUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import me.gm.cleaner.plugin.R
import me.gm.cleaner.plugin.dao.MediaProviderOperation.Companion.OP_INSERT
import me.gm.cleaner.plugin.dao.MediaProviderRecord
import me.gm.cleaner.plugin.xposed.ManagerService
import me.gm.cleaner.plugin.xposed.util.FileUtils
import me.gm.cleaner.plugin.xposed.util.MimeUtils
import java.io.File

class InsertHooker(private val service: ManagerService) : XC_MethodHook(), MediaProviderHooker {
    @Throws(Throwable::class)
    override fun beforeHookedMethod(param: MethodHookParam) {
        if (param.isFuseThread) {
            return
        }
        /** ARGUMENTS */
        val match = (
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) param.args[2] else param.args[1]
                ) as Int
        val uri = (
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) param.args[3] else param.args[2]
                ) as Uri
        val values = (
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) param.args[5] else param.args[3]
                ) as ContentValues

        /** PARSE */
        var mimeType = values.getAsString(MediaStore.MediaColumns.MIME_TYPE)
        val wasPathEmpty = wasPathEmpty(values)
        if (wasPathEmpty) {
            // 这里的 ensureUniqueFileColumns 会根据 RELATIVE_PATH 生成 DATA 绝对路径
            ensureUniqueFileColumns(param.thisObject, match, uri, values, mimeType)
        }
        val data = values.getAsString(MediaStore.MediaColumns.DATA) ?: ""
        
        var shouldIntercept = false
        val templates = service.ruleSp.templates.filterTemplate(javaClass, param.callingPackage)
        
        // 查找匹配的重定向规则
        val matchingTemplate = templates.values.firstOrNull { template ->
            template.redirectPath != null && template.filterPath?.any { FileUtils.contains(it, data) } == true
        }

        if (matchingTemplate != null) {
            /** REDIRECT LOGIC */
            val filterPath = matchingTemplate.filterPath!!.first { FileUtils.contains(it, data) }
            val targetPath = matchingTemplate.redirectPath!!
            
            // 计算新路径
            val newData = data.replaceFirst(filterPath, targetPath)
            values.put(MediaStore.MediaColumns.DATA, newData)
            
            // 同步更新 RELATIVE_PATH，防止数据库字段冲突
            if (values.containsKey(MediaStore.MediaColumns.RELATIVE_PATH)) {
                val externalPath = Environment.getExternalStorageDirectory().path
                if (newData.startsWith(externalPath)) {
                    val newFile = File(newData)
                    // 提取相对路径：例如 /storage/emulated/0/Redirect/A.jpg -> Redirect/
                    val relative = newFile.parentFile?.absolutePath
                        ?.substringAfter(externalPath)
                        ?.removePrefix("/")
                        ?.let { if (it.isEmpty()) it else "$it/" } ?: ""
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                }
            }
            XposedBridge.log("MPM_Redirect: $data -> $newData")
        } else {
            /** INTERCEPT LOGIC */
            shouldIntercept = templates.applyTemplates(listOf(data), listOf(mimeType)).first()
            if (shouldIntercept) {
                param.result = null
            }
        }

        // 兼容性清理：恢复原始状态以适配 MediaProvider 后续逻辑
        if (mimeType.isNullOrEmpty()) {
            mimeType = values.getAsString(MediaStore.MediaColumns.MIME_TYPE)
            values.remove(MediaStore.MediaColumns.MIME_TYPE)
        }
        if (wasPathEmpty) {
            values.remove(MediaStore.MediaColumns.DATA)
        }

        /** RECORD */
        if (service.rootSp.getBoolean(
                service.resources.getString(R.string.usage_record_key), true
            )
        ) {
            service.dao.insert(
                MediaProviderRecord(
                    0,
                    System.currentTimeMillis(),
                    param.callingPackage,
                    match,
                    OP_INSERT,
                    listOf(data),
                    listOf(mimeType ?: ""),
                    listOf(shouldIntercept)
                )
            )
            service.dispatchMediaChange()
        }
    }

    private fun wasPathEmpty(values: ContentValues) =
        !values.containsKey(MediaStore.MediaColumns.DATA)
                || values.getAsString(MediaStore.MediaColumns.DATA).isNullOrEmpty()

    private fun ensureUniqueFileColumns(
        thisObject: Any, match: Int, uri: Uri, values: ContentValues, mimeType: String?
    ) {
        var defaultMimeType = ClipDescription.MIMETYPE_UNKNOWN
        var defaultPrimary = Environment.DIRECTORY_DOWNLOADS
        var defaultSecondary: String? = null
        when (match) {
            AUDIO_MEDIA, AUDIO_MEDIA_ID -> {
                defaultMimeType = "audio/mpeg"
                defaultPrimary = Environment.DIRECTORY_MUSIC
            }
            VIDEO_MEDIA, VIDEO_MEDIA_ID -> {
                defaultMimeType = "video/mp4"
                defaultPrimary = Environment.DIRECTORY_MOVIES
            }
            IMAGES_MEDIA, IMAGES_MEDIA_ID -> {
                defaultMimeType = "image/jpeg"
                defaultPrimary = Environment.DIRECTORY_PICTURES
            }
            AUDIO_ALBUMART, AUDIO_ALBUMART_ID -> {
                defaultMimeType = "image/jpeg"
                defaultPrimary = Environment.DIRECTORY_MUSIC
                defaultSecondary = DIRECTORY_THUMBNAILS
            }
            VIDEO_THUMBNAILS, VIDEO_THUMBNAILS_ID -> {
                defaultMimeType = "image/jpeg"
                defaultPrimary = Environment.DIRECTORY_MOVIES
                defaultSecondary = DIRECTORY_THUMBNAILS
            }
            IMAGES_THUMBNAILS, IMAGES_THUMBNAILS_ID -> {
                defaultMimeType = "image/jpeg"
                defaultPrimary = Environment.DIRECTORY_PICTURES
                defaultSecondary = DIRECTORY_THUMBNAILS
            }
            AUDIO_PLAYLISTS, AUDIO_PLAYLISTS_ID -> {
                defaultMimeType = "audio/mpegurl"
                defaultPrimary = Environment.DIRECTORY_MUSIC
            }
            DOWNLOADS, DOWNLOADS_ID -> {
                defaultPrimary = Environment.DIRECTORY_DOWNLOADS
            }
        }
        
        if (TextUtils.isEmpty(values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))) {
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, System.currentTimeMillis().toString())
        }
        
        if (TextUtils.isEmpty(values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))) {
            val path = if (defaultSecondary != null) "$defaultPrimary/$defaultSecondary/" else "$defaultPrimary/"
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, path)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val resolvedVolumeName = XposedHelpers.callMethod(thisObject, "resolveVolumeName", uri) as String
            val volumePath = XposedHelpers.callMethod(thisObject, "getVolumePath", resolvedVolumeName) as File
            val fileUtilsClass = XposedHelpers.findClass("com.android.providers.media.util.FileUtils", service.classLoader)
            val isFuseThread = XposedHelpers.callMethod(thisObject, "isFuseThread") as Boolean
            
            XposedHelpers.callStaticMethod(fileUtilsClass, "sanitizeValues", values, !isFuseThread)
            XposedHelpers.callStaticMethod(fileUtilsClass, "computeDataFromValues", values, volumePath, isFuseThread)

            var res = File(values.getAsString(MediaStore.MediaColumns.DATA))
            res = XposedHelpers.callStaticMethod(fileUtilsClass, "buildUniqueFile", res.parentFile, mimeType, res.name) as File
            values.put(MediaStore.MediaColumns.DATA, res.absolutePath)
        } else {
            val resolvedVolumeName = XposedHelpers.callMethod(thisObject, "resolveVolumeName", uri) as String
            val relativePath = XposedHelpers.callMethod(thisObject, "sanitizePath", values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
            val displayName = XposedHelpers.callMethod(thisObject, "sanitizeDisplayName", values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
            var res = XposedHelpers.callMethod(thisObject, "getVolumePath", resolvedVolumeName) as File
            res = XposedHelpers.callStaticMethod(Environment::class.java, "buildPath", res, relativePath) as File
            res = XposedHelpers.callStaticMethod(FileUtils::class.java, "buildUniqueFile", res, mimeType, displayName) as File
            values.put(MediaStore.MediaColumns.DATA, res.absolutePath)
        }

        val displayName = values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)
        val mimeTypeFromExt = if (TextUtils.isEmpty(displayName)) null else MimeUtils.resolveMimeType(File(displayName))
        if (TextUtils.isEmpty(values.getAsString(MediaStore.MediaColumns.MIME_TYPE))) {
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeFromExt ?: defaultMimeType)
        }
    }

    companion object {
        private const val DIRECTORY_THUMBNAILS = ".thumbnails"
    }
}