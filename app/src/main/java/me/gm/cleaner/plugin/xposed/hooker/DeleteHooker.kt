===== File: main/java/me/gm/cleaner/plugin/xposed/hooker/DeleteHooker.kt =====
package me.gm.cleaner.plugin.xposed.hooker

import android.app.RecoverableSecurityException
import android.content.ContentResolver.QUERY_ARG_SQL_SELECTION
import android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore.Files.FileColumns
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import me.gm.cleaner.plugin.R
import me.gm.cleaner.plugin.dao.MediaProviderOperation.Companion.OP_DELETE
import me.gm.cleaner.plugin.dao.MediaProviderRecord
import me.gm.cleaner.plugin.xposed.ManagerService
import me.gm.cleaner.plugin.xposed.util.MimeUtils
import java.io.File

class DeleteHooker(private val service: ManagerService) : XC_MethodHook(), MediaProviderHooker {
    @Throws(Throwable::class)
    override fun beforeHookedMethod(param: MethodHookParam) {
        if (param.isFuseThread) {
            return
        }
        
        val uri = param.args[0] as Uri
        val extras = param.args[1] as? Bundle ?: Bundle.EMPTY
        val userWhere: String? = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> extras?.getString(
                QUERY_ARG_SQL_SELECTION
            )

            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> param.args[1] as? String
            else -> throw UnsupportedOperationException()
        }
        val userWhereArgs: Array<String>? = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> extras?.getStringArray(
                QUERY_ARG_SQL_SELECTION_ARGS
            )

            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> param.args[2] as? Array<String>
            else -> throw UnsupportedOperationException()
        }

        
        val match = param.matchUri(uri, param.isCallingPackageAllowedHidden)
        val data = mutableListOf<String>()
        val mimeType = mutableListOf<String>()
        when (match) {
            AUDIO_MEDIA_ID, VIDEO_MEDIA_ID, IMAGES_MEDIA_ID -> {
                try {
                    when {
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> XposedHelpers.callMethod(
                            param.thisObject, "enforceCallingPermission", uri, extras, true
                        )

                        Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> XposedHelpers.callMethod(
                            param.thisObject, "enforceCallingPermission", uri, true
                        )
                    }
                } catch (e: XposedHelpers.InvocationTargetError) {
                    if (e.cause is RecoverableSecurityException) {
                        
                        
                        return
                    }
                }

                val qb = when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> XposedHelpers.callMethod(
                        param.thisObject, "getQueryBuilder", TYPE_DELETE, match, uri,
                        extras, null
                    )

                    Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> XposedHelpers.callMethod(
                        param.thisObject, "getQueryBuilder", TYPE_DELETE, uri, match, null
                    )

                    else -> throw UnsupportedOperationException()
                }
                val helper = XposedHelpers.callMethod(param.thisObject, "getDatabaseForUri", uri)
                val projection = arrayOf(
                    FileColumns.MEDIA_TYPE,
                    FileColumns.DATA,
                    FileColumns._ID,
                    FileColumns.IS_DOWNLOAD,
                    FileColumns.MIME_TYPE,
                )

                val c = when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> XposedHelpers.callMethod(
                        qb, "query", helper, projection, userWhere, userWhereArgs,
                        null, null, null, null, null
                    )

                    Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> XposedHelpers.callMethod(
                        qb, "query", XposedHelpers.callMethod(helper, "getWritableDatabase"),
                        projection, userWhere, userWhereArgs, null, null, null, null, null
                    )

                    else -> throw UnsupportedOperationException()
                } as Cursor
                if (c.count == 0) {
                    
                    c.close()
                    return
                }
                while (c.moveToNext()) {
                    data += c.getString(1)
                    mimeType += c.getString(4)
                }
                c.close()
            }

            FILES -> if (userWhereArgs != null) {
                data += userWhereArgs
                data.mapTo(mimeType) { MimeUtils.resolveMimeType(File(it)) }
            }

            else -> return 
        }

        

        
        if (service.rootSp.getBoolean(
                service.resources.getString(R.string.usage_record_key), true
            )
        ) {
            service.recordUsage(
                MediaProviderRecord(
                    0,
                    System.currentTimeMillis(),
                    param.callingPackage,
                    match,
                    OP_DELETE,
                    data,
                    mimeType,
                    MutableList(data.size) { false }
                )
            )
            service.dispatchMediaChange()
        }
    }

    private val TYPE_DELETE: Int = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> 3
        Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> 2
        else -> throw UnsupportedOperationException()
    }
}