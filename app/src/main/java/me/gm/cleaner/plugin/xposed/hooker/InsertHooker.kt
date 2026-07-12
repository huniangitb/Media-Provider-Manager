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

import android.content.ClipDescription
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.FileUtils
import android.provider.MediaStore
import android.text.TextUtils
import io.github.libxposed.api.XposedInterface
import me.gm.cleaner.plugin.R
import me.gm.cleaner.plugin.dao.MediaProviderOperation.Companion.OP_INSERT
import me.gm.cleaner.plugin.dao.MediaProviderRecord
import me.gm.cleaner.plugin.xposed.ManagerService
import me.gm.cleaner.plugin.xposed.util.MimeUtils
import java.io.File
import java.util.*

class InsertHooker(private val service: ManagerService) : XposedInterface.Hooker, MediaProviderHooker {
    @Throws(Throwable::class)
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (chain.isFuseThread || chain.isSystemCallingPackage) {
            return chain.proceed()
        }
        /** ARGUMENTS */
        dlog("insertFile called. Args size: ${chain.args.size}")
        chain.args.forEachIndexed { index, arg ->
            dlog("arg[$index]: ${arg?.javaClass?.name} = $arg")
        }

        val match = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) chain.getArg(2) else chain.getArg(1)
        } catch (t: Throwable) {
            dlog("Error getting match arg: $t")
            null
        } as? Int ?: return chain.proceed()

        val uri = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) chain.getArg(3) else chain.getArg(2)
        } catch (t: Throwable) {
            dlog("Error getting uri arg: $t")
            null
        } as? Uri ?: return chain.proceed()

        val extras = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) chain.getArg(4) else Bundle.EMPTY
        } catch (t: Throwable) {
            dlog("Error getting extras arg: $t")
            Bundle.EMPTY
        } as Bundle

        val values = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) chain.getArg(5) else chain.getArg(3)
        } catch (t: Throwable) {
            dlog("Error getting values arg: $t")
            null
        } as? ContentValues ?: return chain.proceed()

        val mediaType = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) chain.getArg(6) else chain.getArg(4)
        } catch (t: Throwable) {
            dlog("Error getting mediaType arg: $t")
            return chain.proceed()
        } as Int

        // Android 16 compatibility: Skip if this is an Android/data directory operation
        val relativePath = values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH)
        if (isAndroidDataOperation(relativePath)) {
            dlog("Skipping Android/data directory operation: $relativePath")
            return chain.proceed()
        }

        /** PARSE */
        var mimeType = values.getAsString(MediaStore.MediaColumns.MIME_TYPE)
        val wasPathEmpty = wasPathEmpty(values)
        val thisObj = chain.thisObject ?: return chain.proceed()
        if (wasPathEmpty) {
            // Generate path when undefined
            ensureUniqueFileColumns(thisObj, match, uri, values, mimeType)
        }
        val data = values.getAsString(MediaStore.MediaColumns.DATA)
        if (mimeType.isNullOrEmpty()) {
            mimeType = values.getAsString(MediaStore.MediaColumns.MIME_TYPE)
            // Restore to support apps not targeting sdk R or higher
            values.remove(MediaStore.MediaColumns.MIME_TYPE)
        }
        if (wasPathEmpty) {
            // Restore to allow mkdir
            values.remove(MediaStore.MediaColumns.DATA)
        }

        /** INTERCEPT */
        val filteredTemplates = service.ruleSp.templates.getFilteredTemplates(javaClass, chain.callingPackage)
        val shouldIntercept = service.ruleSp.templates
            .applyTemplates(filteredTemplates, listOf(data), listOf(mimeType)).first()
        if (shouldIntercept) {
            return null
        }

        // 只读路径检查：若 data 在 readOnlyPaths 中则拒绝写入
        if (!data.isNullOrEmpty()) {
            try {
                if (service.ruleSp.templates.isReadOnlyPath(data, chain.callingPackage)) {
                    return null
                }
            } catch (_: Exception) {}

            // 重定向：若 data 匹配 redirect_rules.source，改写 values[DATA] 为 target
            try {
                val redirectData = service.ruleSp.templates.resolveRedirect(data, chain.callingPackage)
                if (redirectData != data) {
                    values.put(MediaStore.MediaColumns.DATA, redirectData)
                }
            } catch (_: Exception) {}
        }

        /** RECORD - use async insert */
        if (service.rootSp.getBoolean(
                service.resources.getString(R.string.usage_record_key), true
            )
        ) {
            service.insertRecordAsync(
                MediaProviderRecord(
                    0,
                    System.currentTimeMillis(),
                    chain.callingPackage,
                    match,
                    OP_INSERT,
                    listOf(data),
                    listOf(mimeType),
                    listOf(shouldIntercept)
                )
            )
        }

        return chain.proceed()
    }

    private fun wasPathEmpty(values: ContentValues) =
        !values.containsKey(MediaStore.MediaColumns.DATA)
                || values.getAsString(MediaStore.MediaColumns.DATA).isEmpty()

    private fun isAndroidDataOperation(relativePath: String?): Boolean {
        if (relativePath.isNullOrEmpty()) return false
        val normalizedPath = relativePath.lowercase().trimEnd('/')
        return normalizedPath.startsWith("android/data/") ||
               normalizedPath.startsWith("android/obb/") ||
               normalizedPath == "android/data" ||
               normalizedPath == "android/obb"
    }

    private fun ensureUniqueFileColumns(
        thisObject: Any, match: Int, uri: Uri, values: ContentValues, mimeType: String?
    ) {
        var defaultMimeType = ClipDescription.MIMETYPE_UNKNOWN
        var defaultPrimary = Environment.DIRECTORY_DOWNLOADS
        var defaultSecondary: String? = null
        when (match) {
            MediaTables.AUDIO_MEDIA, MediaTables.AUDIO_MEDIA_ID -> {
                defaultMimeType = "audio/mpeg"
                defaultPrimary = Environment.DIRECTORY_MUSIC
            }

            MediaTables.VIDEO_MEDIA, MediaTables.VIDEO_MEDIA_ID -> {
                defaultMimeType = "video/mp4"
                defaultPrimary = Environment.DIRECTORY_MOVIES
            }

            MediaTables.IMAGES_MEDIA, MediaTables.IMAGES_MEDIA_ID -> {
                defaultMimeType = "image/jpeg"
                defaultPrimary = Environment.DIRECTORY_PICTURES
            }

            MediaTables.AUDIO_ALBUMART, MediaTables.AUDIO_ALBUMART_ID -> {
                defaultMimeType = "image/jpeg"
                defaultPrimary = Environment.DIRECTORY_MUSIC
                defaultSecondary = DIRECTORY_THUMBNAILS
            }

            MediaTables.VIDEO_THUMBNAILS, MediaTables.VIDEO_THUMBNAILS_ID -> {
                defaultMimeType = "image/jpeg"
                defaultPrimary = Environment.DIRECTORY_MOVIES
                defaultSecondary = DIRECTORY_THUMBNAILS
            }

            MediaTables.IMAGES_THUMBNAILS, MediaTables.IMAGES_THUMBNAILS_ID -> {
                defaultMimeType = "image/jpeg"
                defaultPrimary = Environment.DIRECTORY_PICTURES
                defaultSecondary = DIRECTORY_THUMBNAILS
            }

            MediaTables.AUDIO_PLAYLISTS, MediaTables.AUDIO_PLAYLISTS_ID -> {
                defaultMimeType = "audio/mpegurl"
                defaultPrimary = Environment.DIRECTORY_MUSIC
            }

            MediaTables.DOWNLOADS, MediaTables.DOWNLOADS_ID -> {
                defaultPrimary = Environment.DIRECTORY_DOWNLOADS
            }
        }
        // Give ourselves reasonable defaults when missing
        if (TextUtils.isEmpty(values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))) {
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, System.currentTimeMillis().toString())
        }
        // Use default directories when missing
        if (TextUtils.isEmpty(values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))) {
            if (defaultSecondary != null) {
                values.put(
                    MediaStore.MediaColumns.RELATIVE_PATH, "$defaultPrimary/$defaultSecondary/"
                )
            } else {
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, "$defaultPrimary/")
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val resolveVolumeName = findMethodUp(thisObject.javaClass, "resolveVolumeName", Uri::class.java)
                    ?: return
                val resolvedVolumeName = resolveVolumeName.invoke(thisObject, uri) as String

                val getVolumePath = findMethodUp(thisObject.javaClass, "getVolumePath", String::class.java)
                    ?: return
                val volumePath = getVolumePath.invoke(thisObject, resolvedVolumeName) as File

                val fileUtilsClass = Class.forName(
                    "com.android.providers.media.util.FileUtils", false, service.classLoader
                )
                val isFuseThread = findMethodUp(thisObject.javaClass, "isFuseThread")
                    ?: return
                val isFuse = isFuseThread.invoke(thisObject) as Boolean

                val sanitizeValues = findMethodUp(fileUtilsClass, "sanitizeValues", ContentValues::class.java, java.lang.Boolean.TYPE)
                    ?: return
                sanitizeValues.invoke(null, values, !isFuse)

                val computeDataFromValues = findMethodUp(fileUtilsClass, "computeDataFromValues", ContentValues::class.java, File::class.java, java.lang.Boolean.TYPE)
                    ?: return
                computeDataFromValues.invoke(null, values, volumePath, isFuse)

                var res = File(values.getAsString(MediaStore.MediaColumns.DATA))

                val buildUniqueFile = findMethodUp(fileUtilsClass, "buildUniqueFile", File::class.java, String::class.java, String::class.java)
                    ?: return
                res = buildUniqueFile.invoke(null, res.parentFile, mimeType, res.name) as File

                values.put(MediaStore.MediaColumns.DATA, res.absolutePath)
            } catch (t: Throwable) {
                // Android 16 compatibility: If internal methods fail or behave differently,
                // return early to let the original implementation handle it
                dlog("ensureUniqueFileColumns failed, letting original implementation handle: $t")
                return
            }
        } else {
            val resolveVolumeName = findMethodUp(thisObject.javaClass, "resolveVolumeName", Uri::class.java)
                ?: return
            val resolvedVolumeName = resolveVolumeName.invoke(thisObject, uri) as String

            val sanitizePath = findMethodUp(thisObject.javaClass, "sanitizePath", String::class.java)
                ?: return
            val relativePath = sanitizePath.invoke(thisObject,
                values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH)
            )

            val sanitizeDisplayName = findMethodUp(thisObject.javaClass, "sanitizeDisplayName", String::class.java)
                ?: return
            val displayName = sanitizeDisplayName.invoke(thisObject,
                values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)
            )

            val getVolumePath = findMethodUp(thisObject.javaClass, "getVolumePath", String::class.java)
                ?: return
            var res = getVolumePath.invoke(thisObject, resolvedVolumeName) as File

            val buildPath = findMethodUp(Environment::class.java, "buildPath", File::class.java, Array<String>::class.java)
                ?: return
            res = buildPath.invoke(null, res, arrayOf(relativePath as? String ?: "")) as File

            val buildUniqueFile = findMethodUp(FileUtils::class.java, "buildUniqueFile", File::class.java, String::class.java, String::class.java)
                ?: return
            res = buildUniqueFile.invoke(null, res, mimeType, displayName) as File

            values.put(MediaStore.MediaColumns.DATA, res.absolutePath)
        }

        val displayName = values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)
        val mimeTypeFromExt = if (TextUtils.isEmpty(displayName)) null
        else MimeUtils.resolveMimeType(File(displayName))
        if (TextUtils.isEmpty(values.getAsString(MediaStore.MediaColumns.MIME_TYPE))) {
            if (mimeTypeFromExt != null) {
                values.put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeFromExt)
            } else {
                values.put(MediaStore.MediaColumns.MIME_TYPE, defaultMimeType)
            }
        }
    }

    companion object {
        private const val DIRECTORY_THUMBNAILS = ".thumbnails"
    }
}