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

import android.content.ContentResolver
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import android.util.ArraySet
import androidx.core.os.bundleOf
import io.github.libxposed.api.XposedInterface
import me.gm.cleaner.plugin.BuildConfig
import me.gm.cleaner.plugin.R
import me.gm.cleaner.plugin.util.L
import me.gm.cleaner.plugin.dao.MediaProviderOperation.Companion.OP_QUERY
import me.gm.cleaner.plugin.dao.MediaProviderRecord
import me.gm.cleaner.plugin.xposed.ManagerService
import me.gm.cleaner.plugin.xposed.util.FilteredCursor
import me.gm.cleaner.plugin.xposed.util.RedirectCursorWrapper
import java.util.function.Consumer
import java.util.function.Function

class QueryHooker(private val service: ManagerService) : XposedInterface.Hooker, MediaProviderHooker {
    @Throws(Throwable::class)
    override fun intercept(chain: XposedInterface.Chain): Any? {
        L.e("QueryHooker", "=== intercept called ===")
        val isFuse = try { chain.isFuseThread } catch (t: Throwable) { L.e("QueryHooker", "isFuseThread threw", t); false }
        L.e("QueryHooker", "isFuseThread=$isFuse")
        if (isFuse) {
            L.e("QueryHooker", "SKIP: FUSE thread detected")
            return chain.proceed()
        }
        val callingPkg = try { chain.callingPackage } catch (t: Throwable) { L.e("QueryHooker", "callingPackage threw", t); "" }
        L.e("QueryHooker", "callingPkg='$callingPkg'")
        /** ARGUMENTS */
        val uri = try { chain.getArg(0) as Uri } catch (t: Throwable) { L.e("QueryHooker", "getArg(0) failed", t); return chain.proceed() }
        val projection = (chain.getArgs().getOrNull(1) as? Array<*>)?.mapNotNull { it as? String }?.toTypedArray()
        val queryArgs = chain.getArgs().getOrNull(2) as? Bundle ?: Bundle.EMPTY
        val signal = chain.getArgs().getOrNull(3) as? CancellationSignal
        L.e("QueryHooker", "uri=$uri, projection=${projection?.contentToString()}, callingPkg='$callingPkg'")

        val isSystemMaintenance = callingPkg in MediaTables.SYSTEM_CALLING_PACKAGES &&
                projection != null &&
                projection.none { it.equals(FileColumns.DATA, ignoreCase = true) || it.equals("_data", ignoreCase = true) }

        if (isSystemMaintenance) {
            L.e("QueryHooker", "SKIP: system maintenance query from $callingPkg")
            return chain.proceed()
        }
        L.e("QueryHooker", "isClientQuery check: callingPkg='$callingPkg', uri=$uri")
        if (isClientQuery(callingPkg, uri)) {
            L.e("QueryHooker", "handleClientQuery: callingPkg='$callingPkg', uri=$uri")
            return handleClientQuery(projection, queryArgs)
        }
        L.e("QueryHooker", "matchUri: uri=$uri")
        val table = try {
            chain.matchUri(uri, chain.isCallingPackageAllowedHidden)
        } catch (t: Throwable) {
            L.e("QueryHooker", "matchUri failed for $uri, callingPackage=$callingPkg", t)
            return chain.proceed()
        }
        L.e("QueryHooker", "matchUri result: table=$table")

        /** PARSE */
        val query = Bundle(queryArgs)
        query.remove(INCLUDED_DEFAULT_DIRECTORIES)
        val honoredArgsSet = ArraySet<String>()
        val honoredArgs = Consumer<String> { t ->
            honoredArgsSet.add(t)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val databaseUtilsClass = Class.forName(
                    "com.android.providers.media.util.DatabaseUtils", false, service.classLoader
                )
                val resolveQueryArgs = databaseUtilsClass.getDeclaredMethod(
                    "resolveQueryArgs", Bundle::class.java, Consumer::class.java,
                    Function::class.java
                )
                resolveQueryArgs.isAccessible = true
                val thisObj = chain.thisObject ?: return chain.proceed().also { L.e("QueryHooker", "SKIP: thisObject is null for resolveQueryArgs") }
                resolveQueryArgs.invoke(null, query, honoredArgs,
                    Function<String, String> { t ->
                        val ensureCustomCollator = findMethodUp(thisObj.javaClass, "ensureCustomCollator", String::class.java)
                            ?: return@Function t
                        ensureCustomCollator.invoke(thisObj, t) as String
                    }
                )
            } catch (t: Throwable) {
                L.e("QueryHooker", "Error in resolveQueryArgs: $t")
            }
        }

        val dataProjection = when {
            projection == null -> null
            table in setOf(MediaTables.IMAGES_THUMBNAILS, MediaTables.VIDEO_THUMBNAILS) -> projection + FileColumns.DATA
            else -> projection + arrayOf(FileColumns.DATA, FileColumns.MIME_TYPE)
        }
        val thisObj = chain.thisObject ?: return chain.proceed().also { L.e("QueryHooker", "SKIP: thisObject is null") }
        val helper = try {
            val getDbForUri = findMethodUp(thisObj.javaClass, "getDatabaseForUri", Uri::class.java)
            if (getDbForUri != null) getDbForUri.invoke(thisObj, uri) else null
        } catch (t: Throwable) {
            L.e("QueryHooker", "getDatabaseForUri failed: $t")
            null
        }
        L.e("QueryHooker", "getDatabaseForUri result: helper=${helper != null}")
        val qb = callGetQueryBuilder(thisObj, TYPE_QUERY, table, uri, query, honoredArgs)
        L.e("QueryHooker", "callGetQueryBuilder result: qb=${qb != null}")

        if (qb == null) {
            L.e("QueryHooker", "QueryBuilder is null (getQueryBuilder reflection failed), skipping hook logic and recording. uri=$uri, callingPackage=$callingPkg")
            return chain.proceed()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val getTargetSdk = findMethodUp(thisObj.javaClass, "getCallingPackageTargetSdkVersion")
                if (getTargetSdk != null) {
                    val targetSdkVersion = getTargetSdk.invoke(thisObj) as Int
                    val databaseUtilsClass = Class.forName(
                        "com.android.providers.media.util.DatabaseUtils", false, service.classLoader
                    )
                    if (targetSdkVersion < Build.VERSION_CODES.R) {
                        val recoverAbusiveSortOrder = databaseUtilsClass.getDeclaredMethod("recoverAbusiveSortOrder", Bundle::class.java)
                        recoverAbusiveSortOrder.isAccessible = true
                        recoverAbusiveSortOrder.invoke(null, query)

                        val recoverAbusiveLimit = databaseUtilsClass.getDeclaredMethod("recoverAbusiveLimit", Uri::class.java, Bundle::class.java)
                        recoverAbusiveLimit.isAccessible = true
                        recoverAbusiveLimit.invoke(null, uri, query)
                    }
                    if (targetSdkVersion < Build.VERSION_CODES.Q) {
                        val recoverAbusiveSelection = databaseUtilsClass.getDeclaredMethod("recoverAbusiveSelection", Bundle::class.java)
                        recoverAbusiveSelection.isAccessible = true
                        recoverAbusiveSelection.invoke(null, query)
                    }
                }
            } catch (t: Throwable) {
                dlog("Error in targetSdkVersion processing: $t")
            }
        }

        val c = try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                    // 不能直接用 getDeclaredMethod 精确匹配参数类型，因为 QB 的 query 方法
                    // 参数类型在不同 Android 版本上可能不同（如 DatabaseHelper/SQLiteDatabase）。
                    // 用 findMethodsUp 按名查找 + 参数个数匹配，还原 XposedHelpers.callMethod 行为
                    val qbQuery = findMethodsUp(qb.javaClass, "query").firstOrNull { m ->
                        m.parameterCount == 4
                    }
                    if (qbQuery == null) {
                        L.e("QueryHooker", "qb.query(4 params) not found on ${qb.javaClass.name}")
                        return chain.proceed()
                    }
                    qbQuery.isAccessible = true
                    qbQuery.invoke(qb, helper, dataProjection, query, signal)
                }

                Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> {
                    val selection = query.getString(ContentResolver.QUERY_ARG_SQL_SELECTION)
                    val selectionArgs =
                        query.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS)
                    val sortOrder =
                        query.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER) ?: let {
                            if (query.containsKey(ContentResolver.QUERY_ARG_SORT_COLUMNS)) {
                                val createSqlSortClause = findMethodsUp(ContentResolver::class.java, "createSqlSortClause").firstOrNull { m ->
                                    m.parameterCount == 1
                                }
                                if (createSqlSortClause != null) {
                                    createSqlSortClause.isAccessible = true
                                    createSqlSortClause.invoke(null, query) as String?
                                } else null
                            } else {
                                null
                            }
                        }
                    val groupBy = if (table == MediaTables.AUDIO_ARTISTS_ID_ALBUMS) "audio.album_id"
                    else null
                    val having = null
                    val limit = uri.getQueryParameter("limit")

                    val getWritableDb = findMethodsUp(helper?.javaClass ?: return chain.proceed(), "getWritableDatabase").firstOrNull()
                    val db = getWritableDb?.let { it.isAccessible = true; it.invoke(helper) }

                    val qbQuery = findMethodsUp(qb.javaClass, "query").firstOrNull { m ->
                        m.parameterCount == 9
                    }
                    if (qbQuery == null) {
                        L.e("QueryHooker", "qb.query(9 params) not found on ${qb.javaClass.name}")
                        return chain.proceed()
                    }
                    qbQuery.isAccessible = true
                    qbQuery.invoke(qb, db, dataProjection, selection, selectionArgs, groupBy, having, sortOrder, limit, signal)
                }

                else -> throw UnsupportedOperationException()
            } as Cursor
        } catch (t: Throwable) {
                        L.e("QueryHooker", "Error in qb.query: $t")
                        return chain.proceed()
                    }

        // Track if cursor was handed off to avoid double-close
        var cursorHandled = false
        try {
            if (c.count == 0) {
                // querying nothing.
                return chain.proceed()
            }
            dlog("Query returned ${c.count} items")
            val dataColumn = c.getColumnIndex(FileColumns.DATA)
            val mimeTypeColumn = c.getColumnIndex(FileColumns.MIME_TYPE)

            // Single pass: traverse cursor and collect data
            val data = mutableListOf<String>()
            val mimeType = mutableListOf<String>()
            while (c.moveToNext()) {
                data += if (dataColumn >= 0) c.getString(dataColumn) else ""
                mimeType += if (mimeTypeColumn >= 0) c.getString(mimeTypeColumn) else ""
            }

            // Batch apply templates once for all items
            val filteredTemplates = service.ruleSp.templates.getFilteredTemplates(javaClass, chain.callingPackage)
            val shouldIntercept = service.ruleSp.templates.applyTemplates(filteredTemplates, data, mimeType)

            // Compute filter indices (items NOT intercepted)
            val filterIndices = shouldIntercept.mapIndexedNotNull { index, intercepted ->
                if (!intercepted) index else null
            }.toIntArray()

            /** INTERCEPT */
            if (filterIndices.isEmpty()) {
                cursorHandled = true
                // 重定向：若有 redirect_rules 匹配，包裹结果游标做路径重写
                val hasRedirect = filteredTemplates.any { t -> !t.redirectRules.isNullOrEmpty() }
                val result = if (hasRedirect) {
                    RedirectCursorWrapper(FilteredCursor.createUsingFilter(c, intArrayOf()), service.ruleSp.templates)
                } else {
                    FilteredCursor.createUsingFilter(c, intArrayOf())
                }
                recordQueryUsage(chain, table, data, mimeType, shouldIntercept)
                return result
            } else {
                c.moveToFirst()
                cursorHandled = true
                val resultCursor = FilteredCursor.createUsingFilter(c, filterIndices)
                // 重定向：若有 redirect_rules 匹配，包裹结果游标做路径重写
                val hasRedirect = filteredTemplates.any { t -> !t.redirectRules.isNullOrEmpty() }
                val result = if (hasRedirect) {
                    RedirectCursorWrapper(resultCursor, service.ruleSp.templates)
                } else {
                    resultCursor
                }
                recordQueryUsage(chain, table, data, mimeType, shouldIntercept)
                return result
            }
        } finally {
            if (!cursorHandled) {
                c.close()
            }
        }
    }

    @Throws(Throwable::class)
    private fun recordQueryUsage(
        chain: XposedInterface.Chain,
        table: Int,
        data: List<String>,
        mimeType: List<String>,
        shouldIntercept: List<Boolean>
    ) {
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
                    table,
                    OP_QUERY,
                    if (data.size < MAX_SIZE) data else data.subList(0, MAX_SIZE),
                    mimeType,
                    shouldIntercept
                )
            )
        }
    }

    private fun isClientQuery(callingPackage: String, uri: Uri): Boolean {
        val pkgMatch = callingPackage == BuildConfig.APPLICATION_ID
        val uriMatch = uri == MediaStore.Images.Media.INTERNAL_CONTENT_URI
        L.d("QueryHooker", "isClientQuery check: callingPackage='$callingPackage' (expected='${BuildConfig.APPLICATION_ID}', match=$pkgMatch), uri=$uri (expected=${MediaStore.Images.Media.INTERNAL_CONTENT_URI}, match=$uriMatch)")

        if (!pkgMatch || !uriMatch) {
            L.d("QueryHooker", "isClientQuery FAILED: pkgMatch=$pkgMatch, uriMatch=$uriMatch")
            return false
        }
        return true
    }

    /**
     * This function handles queries from the client. It takes effect when calling package is
     * [BuildConfig.APPLICATION_ID] and query Uri is [MediaStore.Images.Media.INTERNAL_CONTENT_URI].
     */
    private fun handleClientQuery(table: Array<String>?, queryArgs: Bundle): Cursor {
        if (table == null || queryArgs.isEmpty) {
            return MatrixCursor(arrayOf("binder")).apply {
                extras = bundleOf("me.gm.cleaner.plugin.cursor.extra.BINDER" to service)
            }
        }
        val start = queryArgs.getString(ContentResolver.QUERY_ARG_SQL_SELECTION)!!.toLong()
        val end = queryArgs.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER)!!.toLong()
        val packageNames = queryArgs.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS)
        return service.dao.loadForTimeMillis(start, end, table.map { it.toInt() }.toIntArray())
    }

    companion object {
        private const val INCLUDED_DEFAULT_DIRECTORIES = "android:included-default-directories"
        private const val TYPE_QUERY = 0

        private const val MAX_SIZE = 1000
    }

}