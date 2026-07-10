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

import android.net.Uri
import android.os.Bundle
import io.github.libxposed.api.XposedInterface
import me.gm.cleaner.plugin.util.L
import java.lang.reflect.Method
import java.util.Optional
import java.util.concurrent.atomic.AtomicReference

interface MediaProviderHooker {
    companion object {
        private val isQueryBuilderResolved = AtomicReference<Boolean>(false)

        @Volatile
        var queryBuilderMethodInstance: Method? = null
            private set
    }

    fun dlog(message: String) = L.dlog(message)

    private fun resolveQueryBuilderMethod(thisObject: Any) {
        if (isQueryBuilderResolved.get()) return
        synchronized(MediaProviderHooker::class.java) {
            if (isQueryBuilderResolved.get()) return
            val clazz = thisObject.javaClass
            val methods = clazz.declaredMethods.filter { it.name == "getQueryBuilder" }

            val method = methods.find { m ->
                val params = m.parameterTypes
                params.size == 6 && params[2] == Uri::class.java && params[3] == Bundle::class.java
            } ?: methods.find { m ->
                val params = m.parameterTypes
                params.size == 5 && params[2] == Uri::class.java && params[3] == Bundle::class.java
            } ?: methods.find { m ->
                val params = m.parameterTypes
                params.size == 4 && (params[1] == Uri::class.java || params[2] == Uri::class.java)
            }

            method?.isAccessible = true
            queryBuilderMethodInstance = method
            dlog(if (method != null) "Resolved getQueryBuilder: $method" else "Failed to resolve getQueryBuilder")
            isQueryBuilderResolved.set(true)
        }
    }

    fun callGetQueryBuilder(
        thisObject: Any, type: Int, table: Int, uri: Uri, query: Bundle,
        honoredArgs: java.util.function.Consumer<String>
    ): Any? {
        resolveQueryBuilderMethod(thisObject)
        val m = queryBuilderMethodInstance ?: return null

        return try {
            val params = m.parameterTypes
            when (params.size) {
                6 -> {
                    val lastParam = if (params[5].name == "java.util.Optional") Optional.empty<Any>() else null
                    m.invoke(thisObject, type, table, uri, query, honoredArgs, lastParam)
                }
                5 -> m.invoke(thisObject, type, table, uri, query, honoredArgs)
                4 -> if (params[1] == Uri::class.java) m.invoke(thisObject, type, uri, table, query)
                     else m.invoke(thisObject, type, table, uri, query)
                else -> null
            }
        } catch (t: Throwable) {
            val cause = if (t is java.lang.reflect.InvocationTargetException) t.targetException else t
            dlog("Error invoking getQueryBuilder: $cause")
            null
        }
    }

    fun callGetQueryBuilderDelete(
        thisObject: Any, type: Int, match: Int, uri: Uri, extras: Bundle
    ): Any? {
        resolveQueryBuilderMethod(thisObject)
        val m = queryBuilderMethodInstance ?: return null

        return try {
            val params = m.parameterTypes
            when (params.size) {
                6 -> {
                    val lastParam = if (params[5].name == "java.util.Optional") Optional.empty<Any>() else null
                    m.invoke(thisObject, type, match, uri, extras, null, lastParam)
                }
                5 -> m.invoke(thisObject, type, match, uri, extras, null)
                4 -> if (params[1] == Uri::class.java) m.invoke(thisObject, type, uri, match, null)
                     else m.invoke(thisObject, type, match, uri, null)
                else -> null
            }
        } catch (t: Throwable) {
            val cause = if (t is java.lang.reflect.InvocationTargetException) t.targetException else t
            dlog("Error invoking getQueryBuilder (Delete): $cause")
            null
        }
    }

    fun XposedInterface.Chain.ensureMediaProvider() {
        require(executable.declaringClass.name == "com.android.providers.media.MediaProvider")
    }

    val XposedInterface.Chain.isFuseThread: Boolean
        get() = try {
            val fuseDaemonCls = Class.forName(
                "com.android.providers.media.fuse.FuseDaemon",
                false, thisObject?.javaClass?.classLoader
            )
            val nativeIsFuseThread = fuseDaemonCls.getDeclaredMethod("native_is_fuse_thread")
            nativeIsFuseThread.isAccessible = true
            nativeIsFuseThread.invoke(null) as Boolean
        } catch (e: ClassNotFoundException) {
            // Android 16+ may have changed FUSE architecture
            // Try to detect via alternative method on MediaProvider itself
            try {
                val thisObj = thisObject ?: return false
                val isFuseThread = thisObj.javaClass.getDeclaredMethod("isFuseThread")
                isFuseThread.isAccessible = true
                isFuseThread.invoke(thisObj) as Boolean
            } catch (e2: Throwable) {
                // If we cannot determine, default to false to avoid blocking legitimate queries
                dlog("Cannot determine FUSE thread status, assuming NOT FUSE thread: $e2")
                false
            }
        } catch (e: Throwable) {
            dlog("Unexpected error checking FUSE thread: $e")
            false  // Default to false to avoid blocking legitimate queries
        }

    val XposedInterface.Chain.isSystemCallingPackage: Boolean
        get() {
            val pkg = callingPackage
            return pkg in MediaTables.SYSTEM_CALLING_PACKAGES
        }

    val XposedInterface.Chain.callingPackage: String
        get() {
            ensureMediaProvider()
            val thisObj = thisObject ?: return ""
            return try {
                val mCallingIdentityField = thisObj.javaClass
                    .getDeclaredField("mCallingIdentity")
                mCallingIdentityField.isAccessible = true
                val threadLocal = mCallingIdentityField.get(thisObj) as ThreadLocal<*>
                val identity = threadLocal.get()
                if (identity == null) {
                    L.e("QueryHooker", "mCallingIdentity ThreadLocal.get() returned null")
                    ""
                } else {
                    val getPackageName = identity.javaClass.getMethod("getPackageName")
                    val pkg = getPackageName.invoke(identity) as String
                    dlog("callingPackage resolved: $pkg")
                    pkg
                }
            } catch (e: NoSuchFieldError) {
                L.e("QueryHooker", "mCallingIdentity field not found on this Android version", e)
                ""
            } catch (e: ClassNotFoundException) {
                L.e("QueryHooker", "mCallingIdentity class not found", e)
                ""
            } catch (e: Throwable) {
                L.e("QueryHooker", "Unexpected error resolving callingPackage", e)
                ""
            }
        }

    val XposedInterface.Chain.isCallingPackageAllowedHidden: Boolean
        get() {
            ensureMediaProvider()
            val thisObj = thisObject ?: return false
            val method = thisObj.javaClass.getDeclaredMethod("isCallingPackageAllowedHidden")
            method.isAccessible = true
            return method.invoke(thisObj) as Boolean
        }

    fun XposedInterface.Chain.matchUri(uri: Uri, allowHidden: Boolean): Int {
        ensureMediaProvider()
        val thisObj = thisObject ?: return -1
        val method = thisObj.javaClass.getDeclaredMethod("matchUri", Uri::class.java, Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        return method.invoke(thisObj, uri, allowHidden) as Int
    }
}