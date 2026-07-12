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

    /**
     * 向上递归查找方法（含父类），替代旧 XposedHelpers.callMethod 的查找行为。
     * 仅在当前类用 getDeclaredMethod 查找会漏掉父类方法，导致回归。
     */
    fun findMethodUp(clazz: Class<*>, name: String, vararg paramTypes: Class<*>): java.lang.reflect.Method? {
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java && c != Object::class.java) {
            try {
                val m = c.getDeclaredMethod(name, *paramTypes)
                m.isAccessible = true
                return m
            } catch (_: NoSuchMethodException) {
                // 继续向父类查找
            }
            c = c.superclass
        }
        L.e("MediaProviderHooker", "findMethodUp FAILED: $name(${
            paramTypes.joinToString(", ") { it.simpleName }
        }) on ${clazz.name} and its superclasses")
        return null
    }

    /**
     * 向上递归查找方法（按名匹配，含父类），用于 getQueryBuilder 这类多候选场景。
     */
    fun findMethodsUp(clazz: Class<*>, name: String): List<java.lang.reflect.Method> {
        val result = mutableListOf<java.lang.reflect.Method>()
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java && c != Object::class.java) {
            c.declaredMethods.forEach { m ->
                if (m.name == name) {
                    m.isAccessible = true
                    result.add(m)
                }
            }
            c = c.superclass
        }
        return result
    }

    /**
     * 向上递归查找字段（含父类），替代旧 XposedHelpers.getObjectField 的查找行为。
     */
    fun findFieldUp(clazz: Class<*>, name: String): java.lang.reflect.Field? {
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java && c != Object::class.java) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                return f
            } catch (_: NoSuchFieldException) {
                // 继续向父类查找
            }
            c = c.superclass
        }
        L.e("MediaProviderHooker", "findFieldUp FAILED: $name on ${clazz.name} and its superclasses")
        return null
    }

    private fun resolveQueryBuilderMethod(thisObject: Any) {
        if (isQueryBuilderResolved.get()) return
        synchronized(MediaProviderHooker::class.java) {
            if (isQueryBuilderResolved.get()) return
            val clazz = thisObject.javaClass
            // 必须向上查父类，否则 MediaProvider 重构后 getQueryBuilder 移到父类会漏掉
            val methods = findMethodsUp(clazz, "getQueryBuilder")

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
            if (method != null) {
                dlog("Resolved getQueryBuilder: $method")
            } else {
                // 失败时打印所有候选签名，便于现场诊断
                L.e("MediaProviderHooker", "Failed to resolve getQueryBuilder on ${clazz.name}. Candidates:")
                if (methods.isEmpty()) {
                    L.e("MediaProviderHooker", "  (no getQueryBuilder found in class hierarchy)")
                } else {
                    methods.forEach { m ->
                        val params = m.parameterTypes.joinToString(", ") { it.name }
                        L.e("MediaProviderHooker", "  ${m.name}($params) declared in ${m.declaringClass.name}")
                    }
                }
            }
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
        // 允许 MediaProvider 及其任何子类/父类继承链中的方法
        // 不能用 require(declaringClass == ...) 精确匹配，因为不同 ROM 上
        // queryInternal/insertFile/deleteInternal 可能在不同类中声明
        val name = executable.declaringClass.name
        if (name != "com.android.providers.media.MediaProvider" &&
            !name.startsWith("com.android.providers.media.")
        ) {
            throw IllegalArgumentException(
                "Expected MediaProvider but got ${executable.declaringClass.name}"
            )
        }
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
                val isFuseThread = findMethodUp(thisObj.javaClass, "isFuseThread")
                    ?: return false
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
                val mCallingIdentityField = findFieldUp(thisObj.javaClass, "mCallingIdentity")
                    ?: return ""
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
            val method = findMethodUp(thisObj.javaClass, "isCallingPackageAllowedHidden")
                ?: return false
            return method.invoke(thisObj) as Boolean
        }

    fun XposedInterface.Chain.matchUri(uri: Uri, allowHidden: Boolean): Int {
        ensureMediaProvider()
        val thisObj = thisObject ?: return -1
        val method = findMethodUp(
            thisObj.javaClass, "matchUri", Uri::class.java, java.lang.Boolean.TYPE
        ) ?: return -1
        return method.invoke(thisObj, uri, allowHidden) as Int
    }
}