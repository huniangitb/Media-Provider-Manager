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
import me.gm.cleaner.plugin.util.L
import android.os.Handler
import android.os.HandlerThread
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 远程配置拉取器 —— 从 injector 进程获取配置广播。
 *
 * 通过 JNI ([NativeConfigBridge]) 直连 injector 的 UDS 广播服务
 * (SOCK_DGRAM, 抽象命名空间 `nsp_config_broadcast`)，发送 GET 命令
 * 获取 JSON 模板配置。
 *
 * 拉取后的 JSON 配置持久化到独立文件，进程重启后恢复。
 * 所有文件读写均通过 [remoteSp] 的 [@Synchronized] 方法进行，确保
 * 与 [JsonFileSpImpl] 的并发访问安全。
 * 该配置为只读，且优先级最低（本地同名模板覆盖远程）。
 *
 * @param remoteFile 远程配置持久化文件
 * @param remoteSp 共享的 [JsonFileSpImpl] 实例，用于同步文件 I/O
 */
class RemoteConfigFetcher(
    private val remoteFile: File,
    private val remoteSp: JsonFileSpImpl,
) {
    companion object {
        /** 拉取失败后重试间隔（毫秒） */
        private const val RETRY_INTERVAL_MS = 5000L
        /** 最大连续重试次数，超过后暂停直到下次手动拉取 */
        private const val MAX_RETRIES = 10
    }

    /** 上次成功拉取的时间戳（epoch millis） */
    @Volatile
    var lastPullTimestamp: Long = 0

    /** 上次拉取遇到的错误信息 */
    @Volatile
    var lastError: String? = null

    /** 远程配置文本缓存 */
    @Volatile
    var cachedContent: String? = null
        private set

    /** 远程模板列表（解析后缓存） */
    @Volatile
    var cachedTemplates: List<Template> = emptyList()
        private set

    /** 是否正在后台重试 */
    @Volatile
    var isRetrying: Boolean = false
        private set

    private val retryScheduled = AtomicBoolean(false)
    private var retryCount = 0
    @Volatile
    private var stopped = false
    private var retryHandlerThread: HandlerThread? = null
    private var retryHandler: Handler? = null

    private fun ensureRetryHandler(): Handler {
        if (retryHandler == null) {
            retryHandlerThread = HandlerThread("RemoteConfigRetry").also { it.start() }
            retryHandler = Handler(retryHandlerThread!!.looper)
        }
        return retryHandler!!
    }

    /**
     * 主动触发远程拉取 —— 通过 JNI 直连 injector 的 UDS 获取配置。
     *
     * 若拉取失败，自动在后台每 [RETRY_INTERVAL_MS] 毫秒重试一次。
     * 成功拉取后自动取消待处理的重试。
     *
     * @return true 表示拉取成功；false 表示失败。
     */
    @Synchronized
    fun pull(): Boolean {
        RemoteConfigLogBuffer.log("=== Pull start ===")

        if (!NativeConfigBridge.ensureLoaded()) {
            val msg = "JNI not available"
            lastError = msg
            RemoteConfigLogBuffer.log("ERROR: $msg")
            scheduleRetry()
            return false
        }

        RemoteConfigLogBuffer.log("Trying JNI nativeFetchConfig...")
        val response = try {
            NativeConfigBridge.nativeFetchConfig("GET")
        } catch (e: Exception) {
            val msg = "JNI call threw: ${e.message}"
            lastError = msg
            RemoteConfigLogBuffer.log("ERROR: $msg")
            scheduleRetry()
            return false
        }
        if (response.isNullOrBlank()) {
            val msg = "JNI returned empty — injector 可能未运行、JSON 提取失败、或网络超时（详见 logcat）"
            lastError = msg
            RemoteConfigLogBuffer.log("ERROR: $msg")
            scheduleRetry()
            return false
        }

        RemoteConfigLogBuffer.log("JNI fetch succeeded, ${response.length} chars")
        return processResponse(response)
    }

    @Synchronized
    private fun processResponse(response: String): Boolean {
        RemoteConfigLogBuffer.log("Response received, parsing templates array...")

        return try {
            RemoteConfigLogBuffer.log("Response length: ${response.length} chars")
            RemoteConfigLogBuffer.log("Response preview: ${response.take(200)}")

            // JNI 已转换字段：hide_paths → filter_path，已剥离 injector 内部字段
            val parsed = Template.GSON.fromJson(response, Array<Template>::class.java).toList()
            if (response.length <= 1000) {
                RemoteConfigLogBuffer.log("Full JSON: $response")
            } else {
                RemoteConfigLogBuffer.log("Parsed ${parsed.size} templates (JSON ${response.length} chars)")
            }

            RemoteConfigLogBuffer.log("Writing to cache file: ${remoteFile.absolutePath}")
            remoteFile.parentFile?.mkdirs()
            remoteSp.write(response)
            RemoteConfigLogBuffer.log("Cache file written: ${remoteFile.length()} bytes")

            cachedContent = response
            cachedTemplates = parsed
            lastPullTimestamp = System.currentTimeMillis()
            lastError = null
            retryCount = 0
            cancelRetry()

            RemoteConfigLogBuffer.log("=== Pull SUCCESS (${parsed.size} templates) ===")
            true
        } catch (e: Exception) {
            val preview = response.take(200)
            val msg = "JSON parse failed: ${e.javaClass.simpleName}: ${e.message} | preview: $preview"
            lastError = msg
            RemoteConfigLogBuffer.log("ERROR: $msg")

            // 清空损坏的缓存，避免后续 readRemoteSp()/restoreFromFile() 持续失败
            remoteSp.write("")
            cachedContent = null
            cachedTemplates = emptyList()
            RemoteConfigLogBuffer.log("CACHE CLEARED: corrupted content removed")

            scheduleRetry()
            false
        }
    }

    /**
     * 在后台调度一次重试，5 秒后执行。
     * 避免重复调度：若已有待处理的重试则跳过。
     * 超过 [MAX_RETRIES] 次连续失败后暂停重试。
     */
    private fun scheduleRetry() {
        if (stopped) return
        if (retryCount >= MAX_RETRIES) {
            RemoteConfigLogBuffer.log("Max retries ($MAX_RETRIES) reached, giving up until next manual pull")
            isRetrying = false
            return
        }
        if (!retryScheduled.compareAndSet(false, true)) {
            RemoteConfigLogBuffer.log("Retry already scheduled, skipping")
            return
        }
        retryCount++
        isRetrying = true
        RemoteConfigLogBuffer.log("Scheduling retry in ${RETRY_INTERVAL_MS}ms...")
        ensureRetryHandler().postDelayed({
            retryScheduled.set(false)
            RemoteConfigLogBuffer.log("=== Retry trigger ===")
            pull()
        }, RETRY_INTERVAL_MS)
    }

    /**
     * 取消所有待处理的重试。
     */
    @Synchronized
    fun cancelRetry() {
        if (retryScheduled.get()) {
            RemoteConfigLogBuffer.log("Cancelling pending retry")
        }
        retryScheduled.set(false)
        retryHandler?.removeCallbacksAndMessages(null)
        isRetrying = false
    }

    /**
     * 释放重试线程资源。当 [RemoteConfigFetcher] 不再需要时调用。
     */
    @Synchronized
    fun stop() {
        RemoteConfigLogBuffer.log("RemoteConfigFetcher stopping, cleaning up retry thread")
        stopped = true
        cancelRetry()
        retryHandlerThread?.quitSafely()
        retryHandlerThread = null
        retryHandler = null
    }

    /**
     * 从本地缓存文件恢复远程配置（进程重启后恢复持久化内容）。
     */
    fun restoreFromFile() {
        if (!remoteFile.exists()) {
            RemoteConfigLogBuffer.log("No cached remote config file found")
            return
        }
        RemoteConfigLogBuffer.log("Restoring from cache file: ${remoteFile.absolutePath}")
        val body: String = try {
            remoteSp.read() ?: ""
        } catch (e: Exception) {
            RemoteConfigLogBuffer.log("Cache read FAILED: ${e.javaClass.simpleName}: ${e.message}")
            remoteSp.write("")
            cachedContent = null
            cachedTemplates = emptyList()
            lastError = "本地缓存读取失败，已自动清除"
            return
        }
        RemoteConfigLogBuffer.log("Cache file size: ${body.length} chars")
        if (body.isBlank()) {
            RemoteConfigLogBuffer.log("Cache file is empty, skipping")
            return
        }
        try {
            val parsed = Template.GSON.fromJson(body, Array<Template>::class.java).toList()
            RemoteConfigLogBuffer.log("Restored ${parsed.size} templates from cache")
            cachedContent = body
            cachedTemplates = parsed
            lastError = null
        } catch (e: Exception) {
            val preview = body.take(200)
            RemoteConfigLogBuffer.log("Cache restore FAILED: ${e.javaClass.simpleName}: ${e.message} | preview: $preview")
            L.e("RemoteConfigFetcher", "restore failed", e)
            // 清空损坏的缓存文件
            remoteSp.write("")
            cachedContent = null
            cachedTemplates = emptyList()
            lastError = "本地缓存损坏，已自动清除"
            RemoteConfigLogBuffer.log("Corrupted cache file cleared")
        }
    }
}
