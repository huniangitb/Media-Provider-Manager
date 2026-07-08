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

import org.json.JSONArray
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 远程配置调试日志缓冲 —— 按拉取轮次裁剪，仅保留最近 [KEEP_PULL_CYCLES] 次拉取操作的日志。
 *
 * 由 [RemoteConfigFetcher] 和 [ManagerService] 写入，
 * 通过 AIDL [getRemoteConfigLogs] 暴露给客户端 UI 显示。
 */
object RemoteConfigLogBuffer {

    private const val KEEP_PULL_CYCLES = 2
    private const val PULL_START_MARKER = "=== Pull start ==="

    private val logs = ConcurrentLinkedDeque<String>()
    private val pullCycleMarkers = ConcurrentLinkedDeque<String>()
    private val dateFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    /**
     * 写入一条带时间戳的日志。
     * 当收到 Pull start 标记时，自动裁剪超过 [KEEP_PULL_CYCLES] 轮的旧日志。
     */
    fun log(msg: String) {
        val line = "[${LocalTime.now().format(dateFormat)}] $msg"
        synchronized(logs) {
            logs.addLast(line)

            if (msg == PULL_START_MARKER) {
                pullCycleMarkers.addLast(line)
                // 超过保留轮次时，从头部裁剪最旧一轮的日志
                while (pullCycleMarkers.size > KEEP_PULL_CYCLES) {
                    val oldestMarker = pullCycleMarkers.pollFirst() ?: break
                    val nextMarker = pullCycleMarkers.peekFirst()
                    while (logs.isNotEmpty()) {
                        val first = logs.peekFirst()
                        if (nextMarker != null && first == nextMarker) break
                        logs.pollFirst()
                    }
                }
            }
        }
    }

    /**
     * 获取所有日志（从旧到新）。
     */
    fun getAll(): List<String> = synchronized(logs) {
        logs.toList()
    }

    /**
     * 获取最近 N 条日志。
     */
    fun getLast(n: Int): List<String> = synchronized(logs) {
        logs.toList().takeLast(n.coerceAtLeast(1))
    }

    /**
     * 以 JSON 数组字符串形式返回日志。
     */
    fun toJson(n: Int = 100): String {
        val entries = getLast(n)
        return JSONArray(entries).toString()
    }

    /** 当前日志条目数 */
    fun size(): Int = synchronized(logs) { logs.size }
}
