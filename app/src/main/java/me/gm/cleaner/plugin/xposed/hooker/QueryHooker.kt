/*
 * Copyright 2021 Green Mushroom
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.gm.cleaner.plugin.xposed.hooker

import android.content.ContentResolver
import android.database.Cursor
import android.database.CursorWrapper
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import android.util.ArraySet
import androidx.core.os.bundleOf
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import me.gm.cleaner.plugin.BuildConfig
import me.gm.cleaner.plugin.R
import me.gm.cleaner.plugin.dao.MediaProviderOperation.Companion.OP_QUERY
import me.gm.cleaner.plugin.dao.MediaProviderRecord
import me.gm.cleaner.plugin.xposed.ManagerService
import me.gm.cleaner.plugin.xposed.util.FilteredCursor
import java.util.Optional
import java.util.function.Consumer
import java.util.function.Function

class QueryHooker(private val service: ManagerService) : XC_MethodHook(), MediaProviderHooker {
    @Throws(Throwable::class)
    override fun beforeHookedMethod(param: MethodHookParam) {
        if (param.isFuseThread) {
            return
        }
        /** ARGUMENTS */
        val uri = param.args[0] as Uri
        val projection = param.args[1] as? Array<String>?
        val queryArgs = param.args[2] as? Bundle ?: Bundle.EMPTY
        val signal = param.args[3] as? CancellationSignal

        if (param.callingPackage in
            setOf("com.android.providers.media", "com.android.providers.media.module")
        ) {
            // Scanning files and internal queries.
            return
        }

        /** PARSE */
        val query = Bundle(queryArgs)
        query.remove(INCLUDED_DEFAULT_DIRECTORIES)
        val honoredArgs = ArraySet<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val databaseUtilsClass = XposedHelpers.findClass(
                "com.android.providers.media.util.DatabaseUtils", service.classLoader
            )
            XposedHelpers.callStaticMethod(
                databaseUtilsClass, "resolveQueryArgs", query, object : Consumer<String> {
                    override fun accept(t: String) {
                        honoredArgs.add(t)
                    }
                }, object : Function<String, String> {
                    override fun apply(t: String) = XposedHelpers.callMethod(
                        param.thisObject, "ensureCustomCollator", t
                    ) as String
                }
            )
        }
        if (isClientQuery(param.callingPackage, uri)) {
            param.result = handleClientQuery(projection, query)
            return
        }
        val table = param.matchUri(uri, param.isCallingPackageAllowedHidden)
        val dataProjection = when {
            projection == null -> null
            table in setOf(IMAGES_THUMBNAILS, VIDEO_THUMBNAILS) -> projection + FileColumns.DATA
            else -> projection + arrayOf(FileColumns.DATA, FileColumns.MIME_TYPE)
        }
        val helper = XposedHelpers.callMethod(param.thisObject, "getDatabaseForUri", uri)
        val qb = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> XposedHelpers.callMethod(
                param.thisObject, "getQueryBuilder", TYPE_QUERY, table, uri, query,
                object : Consumer<String> {
                    override fun accept(t: String) {
                        honoredArgs.add(t)
                    }
                },
                Optional.empty<Any>()
            )

            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> XposedHelpers.callMethod(
                param.thisObject, "getQueryBuilder", TYPE_QUERY, uri, table, query
            )

            else -> throw UnsupportedOperationException()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val targetSdkVersion = XposedHelpers.callMethod(
                param.thisObject, "getCallingPackageTargetSdkVersion"
            ) as Int
            val databaseUtilsClass = XposedHelpers.findClass(
                "com.android.providers.media.util.DatabaseUtils", service.classLoader
            )
            if (targetSdkVersion < Build.VERSION_CODES.R) {
                XposedHelpers.callStaticMethod(databaseUtilsClass, "recoverAbusiveSortOrder", query)
                XposedHelpers.callStaticMethod(
                    databaseUtilsClass, "recoverAbusiveLimit", uri, query
                )
            }
            if (targetSdkVersion < Build.VERSION_CODES.Q) {
                XposedHelpers.callStaticMethod(databaseUtilsClass, "recoverAbusiveSelection", query)
            }
        }

        val c = try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> XposedHelpers.callMethod(
                    qb, "query", helper, dataProjection, query, signal
                )

                Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> {
                    val selection = query.getString(ContentResolver.QUERY_ARG_SQL_SELECTION)
                    val selectionArgs =
                        query.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS)
                    val sortOrder =
                        query.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER) ?: let {
                            if (query.containsKey(ContentResolver.QUERY_ARG_SORT_COLUMNS)) {
                                XposedHelpers.callStaticMethod(
                                    ContentResolver::class.java, "createSqlSortClause", query
                                ) as String?
                            } else {
                                null
                            }
                        }
                    val groupBy = if (table == AUDIO_ARTISTS_ID_ALBUMS) "audio.album_id"
                    else null
                    val having = null
                    val limit = uri.getQueryParameter("limit")

                    XposedHelpers.callMethod(
                        qb, "query", XposedHelpers.callMethod(helper, "getWritableDatabase"),
                        dataProjection, selection, selectionArgs, groupBy, having, sortOrder, limit,
                        signal
                    )
                }

                else -> throw UnsupportedOperationException()
            } as Cursor
        } catch (e: XposedHelpers.InvocationTargetError) {
            return
        }
        if (c.count == 0) {
            c.close()
            return
        }

        /** 获取重定向和拦截规则 */
        val templates = service.ruleSp.templates.filterTemplate(javaClass, param.callingPackage)
        
        // 1. 提取数据用于过滤和日志记录
        val dataColumn = c.getColumnIndexOrThrow(FileColumns.DATA)
        val mimeTypeColumn = c.getColumnIndex(FileColumns.MIME_TYPE)
        val dataList = mutableListOf<String>()
        val mimeTypeList = mutableListOf<String>()
        while (c.moveToNext()) {
            dataList += c.getString(dataColumn)
            mimeTypeList += c.getString(mimeTypeColumn)
        }

        // 2. 处理重定向 (路径还原)
        val redirectionMap = mutableMapOf<String, String>()
        templates.values.forEach { template ->
            val redirectPath = template.redirectPath
            if (!redirectPath.isNullOrEmpty()) {
                template.filterPath?.forEach { originalPath ->
                    // 建立 真实路径 -> 原始路径 的映射
                    redirectionMap[redirectPath] = originalPath
                }
            }
        }

        var wrappedCursor: Cursor = c
        if (redirectionMap.isNotEmpty()) {
            wrappedCursor = RedirectedCursor(c, redirectionMap)
        }

        // 3. 处理拦截 (过滤)
        val shouldIntercept = templates.applyTemplates(dataList, mimeTypeList)
        if (shouldIntercept.any { it }) {
            wrappedCursor.moveToFirst()
            val filter = shouldIntercept
                .mapIndexedNotNull { index, b -> if (!b) index else null }
                .toIntArray()
            param.result = FilteredCursor.createUsingFilter(wrappedCursor, filter)
        } else {
            param.result = wrappedCursor
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
                    table,
                    OP_QUERY,
                    if (dataList.size < MAX_SIZE) dataList else dataList.subList(0, MAX_SIZE),
                    mimeTypeList,
                    shouldIntercept
                )
            )
            service.dispatchMediaChange()
        }
    }

    /**
     * 自定义 CursorWrapper，用于在应用读取时将重定向后的真实路径还原为原始路径。
     */
    private class RedirectedCursor(cursor: Cursor, private val redirectMap: Map<String, String>) : CursorWrapper(cursor) {
        private val dataColumnIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)

        override fun getString(columnIndex: Int): String? {
            val value = super.getString(columnIndex)
            if (columnIndex == dataColumnIndex && value != null) {
                for ((realPath, originalPath) in redirectMap) {
                    if (value.startsWith(realPath)) {
                        return value.replaceFirst(realPath, originalPath)
                    }
                }
            }
            return value
        }
    }

    private fun isClientQuery(callingPackage: String, uri: Uri) =
        callingPackage == BuildConfig.APPLICATION_ID && uri == MediaStore.Images.Media.INTERNAL_CONTENT_URI

    private fun handleClientQuery(table: Array<String>?, queryArgs: Bundle): Cursor {
        if (table == null || queryArgs.isEmpty) {
            return MatrixCursor(arrayOf("binder")).apply {
                extras = bundleOf("me.gm.cleaner.plugin.cursor.extra.BINDER" to service)
            }
        }
        val start = queryArgs.getString(ContentResolver.QUERY_ARG_SQL_SELECTION)!!.toLong()
        val end = queryArgs.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER)!!.toLong()
        val packageNames = queryArgs.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS)
        return service.dao.loadForTimeMillis(start, end, *table.map { it.toInt() }.toIntArray())
    }

    companion object {
        private const val INCLUDED_DEFAULT_DIRECTORIES = "android:included-default-directories"
        private const val TYPE_QUERY = 0
        private const val MAX_SIZE = 1000
    }
}