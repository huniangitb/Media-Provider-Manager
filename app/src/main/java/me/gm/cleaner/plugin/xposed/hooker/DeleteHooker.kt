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

import android.app.RecoverableSecurityException
import android.content.ContentResolver.QUERY_ARG_SQL_SELECTION
import android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.MediaStore.Files.FileColumns
import io.github.libxposed.api.XposedInterface
import me.gm.cleaner.plugin.R
import me.gm.cleaner.plugin.dao.MediaProviderOperation.Companion.OP_DELETE
import me.gm.cleaner.plugin.dao.MediaProviderRecord
import me.gm.cleaner.plugin.util.L
import me.gm.cleaner.plugin.xposed.ManagerService
import me.gm.cleaner.plugin.xposed.util.MimeUtils
import java.io.File
import java.lang.reflect.InvocationTargetException

class DeleteHooker(private val service: ManagerService) : XposedInterface.Hooker, MediaProviderHooker {
    @Throws(Throwable::class)
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (chain.isFuseThread || chain.isSystemCallingPackage) {
            return chain.proceed()
        }
        /** ARGUMENTS */
        val uri = chain.getArg(0) as Uri
        val extras = chain.getArgs().getOrNull(1) as? Bundle ?: Bundle.EMPTY
        dlog("deleteInternal called: uri=$uri, callingPackage=${chain.callingPackage}")
        val userWhere: String? = try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> extras?.getString(
                    QUERY_ARG_SQL_SELECTION
                )

                Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> chain.getArg(1) as? String
                else -> throw UnsupportedOperationException()
            }
        } catch (t: Throwable) {
            dlog("Error getting userWhere: $t")
            null
        }
        val userWhereArgs: Array<String>? = try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> extras?.getStringArray(
                    QUERY_ARG_SQL_SELECTION_ARGS
                )

                Build.VERSION.SDK_INT == Build.VERSION_CODES.Q ->
                    (chain.getArgs().getOrNull(2) as? Array<*>)?.mapNotNull { it as? String }?.toTypedArray()
                else -> throw UnsupportedOperationException()
            }
        } catch (t: Throwable) {
            dlog("Error getting userWhereArgs: $t")
            null
        }

        /** PARSE */
        val thisObj = chain.thisObject ?: return chain.proceed()
        val match = try {
            chain.matchUri(uri, chain.isCallingPackageAllowedHidden)
        } catch (t: Throwable) {
            dlog("Error matching URI: $t")
            return chain.proceed()
        }
        dlog("Matched table: $match")
        val data = mutableListOf<String>()
        val mimeType = mutableListOf<String>()
        when (match) {
            MediaTables.AUDIO_MEDIA_ID, MediaTables.VIDEO_MEDIA_ID, MediaTables.IMAGES_MEDIA_ID -> {
                try {
                    when {
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                            val enforceCallingPermission = thisObj.javaClass
                                .getDeclaredMethod("enforceCallingPermission", Uri::class.java, Bundle::class.java, Boolean::class.javaPrimitiveType)
                            enforceCallingPermission.isAccessible = true
                            enforceCallingPermission.invoke(thisObj, uri, extras, true)
                        }

                        Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> {
                            val enforceCallingPermission = thisObj.javaClass
                                .getDeclaredMethod("enforceCallingPermission", Uri::class.java, Boolean::class.javaPrimitiveType)
                            enforceCallingPermission.isAccessible = true
                            enforceCallingPermission.invoke(thisObj, uri, true)
                        }
                    }
                } catch (e: InvocationTargetException) {
                    if (e.targetException is RecoverableSecurityException) {
                        // Give callers interacting with a specific media item a chance to
                        // escalate access if they don't already have it
                        return chain.proceed()
                    }
                }

                val qb = callGetQueryBuilderDelete(thisObj, TYPE_DELETE, match, uri, extras)
                if (qb == null) return chain.proceed()
                val helper = try {
                    val getDbForUri = thisObj.javaClass.getDeclaredMethod("getDatabaseForUri", Uri::class.java)
                    getDbForUri.isAccessible = true
                    getDbForUri.invoke(thisObj, uri)
                } catch (t: Throwable) {
                    dlog("Error calling getDatabaseForUri in DeleteHooker: $t")
                    null
                }
                if (helper == null) return chain.proceed()
                val projection = arrayOf(
                    FileColumns.MEDIA_TYPE,
                    FileColumns.DATA,
                    FileColumns._ID,
                    FileColumns.IS_DOWNLOAD,
                    FileColumns.MIME_TYPE,
                )

                val c = try {
                    when {
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                            // On R+, QB.query uses Bundle-based signature: (DatabaseHelper, String[], Bundle, CancellationSignal)
                            val deleteQueryArgs = Bundle().apply {
                                userWhere?.let { putString(QUERY_ARG_SQL_SELECTION, it) }
                                userWhereArgs?.let { putStringArray(QUERY_ARG_SQL_SELECTION_ARGS, it) }
                            }
                            val qbQuery = qb.javaClass.getDeclaredMethod("query", Any::class.java, Array<String>::class.java, Bundle::class.java, CancellationSignal::class.java)
                            qbQuery.isAccessible = true
                            qbQuery.invoke(qb, helper, projection, deleteQueryArgs, null)
                        }

                        Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> {
                            val getWritableDb = helper.javaClass.getDeclaredMethod("getWritableDatabase")
                            getWritableDb.isAccessible = true
                            val db = getWritableDb.invoke(helper)
                            val qbQuery = qb.javaClass.getDeclaredMethod("query", Any::class.java, Array<String>::class.java, String::class.java, Array<String>::class.java, String::class.java, String::class.java, String::class.java, String::class.java, CancellationSignal::class.java)
                            qbQuery.isAccessible = true
                            qbQuery.invoke(qb, db, projection, userWhere, userWhereArgs, null, null, null, null, null)
                        }

                        else -> throw UnsupportedOperationException()
                    } as Cursor
                } catch (t: Throwable) {
                    dlog("Error in qb.query (DeleteHooker): $t")
                    return chain.proceed()
                }
                try {
                    if (c.count == 0) {
                        return chain.proceed()
                    }
                    while (c.moveToNext()) {
                        data += c.getString(1)
                        mimeType += c.getString(4)
                    }
                } finally {
                    c.close()
                }
            }

            MediaTables.FILES -> if (userWhereArgs != null) {
                data += userWhereArgs
                data.mapTo(mimeType) { MimeUtils.resolveMimeType(File(it)) }
            }

            else -> return chain.proceed() // We don't care about these data, just ignore.
        }

        // 只读路径检查：阻止删除 readOnlyPaths 中的文件
        try {
            val hasReadOnly = data.any { d ->
                service.ruleSp.templates.isReadOnlyPath(d, chain.callingPackage)
            }
            if (hasReadOnly) {
                return 0
            }
        } catch (e: Exception) {
            L.e("DeleteHooker", "isReadOnlyPath failed", e)
        }

        // There is a system confirm dialog before deletion, thus we don't intercept delete operation.

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
                    OP_DELETE,
                    data,
                    mimeType,
                    MutableList(data.size) { false }
                )
            )
        }

        return chain.proceed()
    }

    private val TYPE_DELETE: Int = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> 3
        Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> 2
        else -> throw UnsupportedOperationException()
    }
}