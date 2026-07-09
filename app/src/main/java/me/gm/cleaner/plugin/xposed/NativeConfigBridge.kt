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

package me.gm.cleaner.plugin.xposed

import me.gm.cleaner.plugin.util.L

/**
 * JNI 桥接 —— 直接通过 Unix Domain Socket 从 injector 拉取/订阅配置。
 *
 * 对应的 native 实现：`app/src/main/jni/nsp_bridge.c`
 * 编译后在运行时加载 `libnsp_bridge.so`。
 */
object NativeConfigBridge {

    private var loaded = false

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("nsp_bridge")
            loaded = true
            L.d("NativeConfigBridge loaded libnsp_bridge.so")
            true
        } catch (e: UnsatisfiedLinkError) {
            L.e("NativeConfigBridge failed to load libnsp_bridge.so", e)
            false
        }
    }

    @JvmStatic
    external fun nativeFetchConfig(cmd: String): String?

    @JvmStatic
    external fun nativeSubscribeConfig(callback: OnConfigUpdateListener)

    @JvmStatic
    external fun nativeStopSubscribe()
}

interface OnConfigUpdateListener {
    fun onConfigUpdate(json: String)
    fun onError(message: String)
}
