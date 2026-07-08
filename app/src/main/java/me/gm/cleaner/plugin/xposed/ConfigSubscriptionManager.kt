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

import me.gm.cleaner.plugin.model.Template
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 配置变更订阅管理器。
 *
 * 启动后通过 JNI 与 injector 建立长连接，每次收到配置推送时：
 * 1. 解析并转换 JSON（C 层已完成 hide_paths→filter_path 和字段剥离）
 * 2. 写入本地缓存文件
 * 3. 回调 [onConfigUpdated] 通知上层刷新
 *
 * 订阅活跃时（[isSubscribed]==true）禁用手动拉取。
 */
class ConfigSubscriptionManager(
    private val remoteFile: java.io.File,
    private val onConfigUpdated: () -> Unit,
) {
    @Volatile
    var isSubscribed: Boolean = false
        private set

    private var job: Job? = null

    fun start() {
        if (job != null) return
        if (!NativeConfigBridge.ensureLoaded()) return

        job = CoroutineScope(Dispatchers.IO).launch {
            RemoteConfigLogBuffer.log("=== Subscribe start ===")
            // 协程启动即标记已订阅，不等首次回调
            isSubscribed = true
            try {
                NativeConfigBridge.nativeSubscribeConfig(object : OnConfigUpdateListener {
                    override fun onConfigUpdate(json: String) {
                        try {
                            RemoteConfigLogBuffer.log("Subscribe received: ${json.take(100)}")
                            val parsed = Template.GSON.fromJson(json, Array<Template>::class.java).toList()
                            RemoteConfigLogBuffer.log("Subscribe parsed ${parsed.size} templates")

                            remoteFile.parentFile?.mkdirs()
                            remoteFile.writeText(json)
                            RemoteConfigLogBuffer.log("Subscribe cache written: ${remoteFile.length()} bytes")

                            cachedContent = json
                            cachedTemplates = parsed
                            lastPullTimestamp = System.currentTimeMillis()
                            lastError = null
                            isSubscribed = true

                            RemoteConfigLogBuffer.log("=== Subscribe update processed ===")
                            onConfigUpdated()
                        } catch (e: Exception) {
                            RemoteConfigLogBuffer.log("Subscribe parse error: ${e.message}")
                            lastError = "Subscribe parse: ${e.message}"
                        }
                    }

                    override fun onError(message: String) {
                        RemoteConfigLogBuffer.log("Subscribe error: $message")
                        lastError = message
                        // 连接性错误（超时重连等）不置 false，订阅仍然活跃
                        if (!message.startsWith("injector disconnected")) {
                            isSubscribed = false
                        }
                    }
                })
            } catch (e: Exception) {
                RemoteConfigLogBuffer.log("Subscribe threw: ${e.message}")
            }
            isSubscribed = false
            RemoteConfigLogBuffer.log("=== Subscribe ended ===")
        }
    }

    fun stop() {
        if (!NativeConfigBridge.ensureLoaded()) {
            job?.cancel()
            job = null
            isSubscribed = false
            return
        }
        NativeConfigBridge.nativeStopSubscribe()
        job?.cancel()
        job = null
        isSubscribed = false
    }

    /**
     * 停止当前订阅并重新启动。
     * 用于手动拉取后刷新推送通道。
     */
    fun restart() {
        stop()
        start()
    }

    companion object {
        @Volatile
        var cachedContent: String? = null
            private set
        @Volatile
        var cachedTemplates: List<Template> = emptyList()
            private set
        @Volatile
        var lastPullTimestamp: Long = 0L
            private set
        @Volatile
        var lastError: String? = null
            private set
    }
}
