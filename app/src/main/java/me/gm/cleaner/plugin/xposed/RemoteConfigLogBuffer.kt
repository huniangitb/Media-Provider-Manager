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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 远程配置调试日志缓冲 —— 环形缓冲区，保存最近 [MAX_LOG_COUNT] 条日志。
 *
 * 由 [RemoteConfigFetcher] 和 [ManagerService] 写入，
 * 通过 AIDL [getRemoteConfigLogs] 暴露给客户端 UI 显示。
 */
object RemoteConfigLogBuffer {

    private const val MAX_LOG_COUNT = 200

    private val logs = ConcurrentLinkedDeque<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    /**
     * 写入一条带时间戳的日志。
     */
    fun log(msg: String) {
        val line = "[${dateFormat.format(Date())}] $msg"
        synchronized(logs) {
            logs.addLast(line)
            while (logs.size > MAX_LOG_COUNT) {
                logs.pollFirst()
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
        val all = logs.toList()
        all.takeLast(n.coerceIn(1, MAX_LOG_COUNT))
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
