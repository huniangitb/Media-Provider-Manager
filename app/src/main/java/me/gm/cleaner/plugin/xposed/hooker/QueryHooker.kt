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
        if (chain.isFuseThread) {
            L.d("QueryHooker", "Skipped: FUSE thread detected")
            return chain.proceed()
        }
        if (chain.isSystemCallingPackage) {
            dlog("Skipping system query from ${chain.callingPackage}")
            return chain.proceed()
        }
        /** ARGUMENTS */
        val uri = chain.getArg(0) as Uri
        val projection = (chain.getArgs().getOrNull(1) as? Array<*>)?.mapNotNull { it as? String }?.toTypedArray()
        val queryArgs = chain.getArgs().getOrNull(2) as? Bundle ?: Bundle.EMPTY
        val signal = chain.getArgs().getOrNull(3) as? CancellationSignal

        val callingPkg = chain.callingPackage
        L.d("QueryHooker", "intercept: uri=$uri, callingPkg='$callingPkg', isFuse=false")
        val isSystemMaintenance = callingPkg in MediaTables.SYSTEM_CALLING_PACKAGES &&
                projection != null &&
                projection.none { it.equals(FileColumns.DATA, ignoreCase = true) || it.equals("_data", ignoreCase = true) }

        if (isSystemMaintenance) {
            dlog("Skipping system maintenance query from $callingPkg")
            return chain.proceed()
        }
        dlog("queryInternal: uri=$uri, projection=${projection?.contentToString()}, callingPackage=$callingPkg")

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
                val thisObj = chain.thisObject ?: return chain.proceed()
                resolveQueryArgs.invoke(null, query, honoredArgs,
                    Function<String, String> { t ->
                        val ensureCustomCollator = thisObj.javaClass.getDeclaredMethod("ensureCustomCollator", String::class.java)
                        ensureCustomCollator.isAccessible = true
                        ensureCustomCollator.invoke(thisObj, t) as String
                    }
                )
            } catch (t: Throwable) {
                dlog("Error in resolveQueryArgs: $t")
            }
        }
        L.d("QueryHooker", "About to check isClientQuery: callingPkg='$callingPkg', uri=$uri")
        if (isClientQuery(callingPkg, uri)) {
            L.d("QueryHooker", "isClientQuery returned TRUE, handling client query")
            return handleClientQuery(projection, query)
        }
        L.d("QueryHooker", "isClientQuery returned FALSE, proceeding with normal hook")
        val table = try {
            chain.matchUri(uri, chain.isCallingPackageAllowedHidden)
        } catch (t: Throwable) {
            dlog("Skipping query hook because matchUri failed for $uri: $t")
            return chain.proceed()
        }
        dlog("Matched table: $table")
        val dataProjection = when {
            projection == null -> null
            table in setOf(MediaTables.IMAGES_THUMBNAILS, MediaTables.VIDEO_THUMBNAILS) -> projection + FileColumns.DATA
            else -> projection + arrayOf(FileColumns.DATA, FileColumns.MIME_TYPE)
        }
        val thisObj = chain.thisObject ?: return chain.proceed()
        val helper = try {
            val getDbForUri = thisObj.javaClass.getDeclaredMethod("getDatabaseForUri", Uri::class.java)
            getDbForUri.isAccessible = true
            getDbForUri.invoke(thisObj, uri)
        } catch (t: Throwable) {
            dlog("Error calling getDatabaseForUri: $t")
            null
        }
        val qb = callGetQueryBuilder(thisObj, TYPE_QUERY, table, uri, query, honoredArgs)

        if (qb == null) {
            dlog("QueryBuilder is null, skipping hook logic")
            return chain.proceed()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val getTargetSdk = thisObj.javaClass.getDeclaredMethod("getCallingPackageTargetSdkVersion")
                getTargetSdk.isAccessible = true
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
            } catch (t: Throwable) {
                dlog("Error in targetSdkVersion processing: $t")
            }
        }

        val c = try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                    val qbQuery = qb.javaClass.getDeclaredMethod("query", Any::class.java, Array<String>::class.java, Bundle::class.java, CancellationSignal::class.java)
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
                                val createSqlSortClause = ContentResolver::class.java.getDeclaredMethod("createSqlSortClause", Bundle::class.java)
                                createSqlSortClause.isAccessible = true
                                createSqlSortClause.invoke(null, query) as String?
                            } else {
                                null
                            }
                        }
                    val groupBy = if (table == MediaTables.AUDIO_ARTISTS_ID_ALBUMS) "audio.album_id"
                    else null
                    val having = null
                    val limit = uri.getQueryParameter("limit")

                    val getWritableDb = helper?.javaClass?.getDeclaredMethod("getWritableDatabase")
                    getWritableDb?.isAccessible = true
                    val db = getWritableDb?.invoke(helper)

                    val qbQuery = qb.javaClass.getDeclaredMethod("query", Any::class.java, Array<String>::class.java, String::class.java, Array<String>::class.java, String::class.java, String::class.java, String::class.java, String::class.java, CancellationSignal::class.java)
                    qbQuery.isAccessible = true
                    qbQuery.invoke(qb, db, dataProjection, selection, selectionArgs, groupBy, having, sortOrder, limit, signal)
                }

                else -> throw UnsupportedOperationException()
            } as Cursor
        } catch (t: Throwable) {
            dlog("Error in qb.query: $t")
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