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

package me.gm.cleaner.plugin.xposed.util

import android.database.Cursor
import android.database.CursorWrapper
import android.database.CharArrayBuffer
import android.provider.MediaStore.Files.FileColumns
import me.gm.cleaner.plugin.model.Templates

/**
 * CursorWrapper 重写 getString，对 _data 列应用反向路径重定向。
 *
 * 当远程配置定义了 redirect_rules（source → target），
 * 数据库中实际文件路径是 target 前缀，但应用查询到的结果应显示为 source 前缀。
 * 此类在 getString 中拦截 _data 列，将 target 前缀替换回 source。
 */
class RedirectCursorWrapper(
    cursor: Cursor,
    private val templates: Templates,
) : CursorWrapper(cursor) {

    private var dataColumnIndex: Int = -1

    /** 确保 [dataColumnIndex] 已缓存的便捷方法 */
    private fun ensureDataColumnIndex(): Int {
        if (dataColumnIndex < 0) {
            dataColumnIndex = getColumnIndex(FileColumns.DATA)
        }
        return dataColumnIndex
    }

    override fun getString(columnIndex: Int): String? {
        val value = super.getString(columnIndex)
        if (value == null) return null
        return if (columnIndex == ensureDataColumnIndex()) {
            templates.reverseRedirect(value)
        } else {
            value
        }
    }

    override fun getBlob(columnIndex: Int): ByteArray? {
        val value = super.getBlob(columnIndex)
        if (value == null) return null
        if (columnIndex != ensureDataColumnIndex()) return value
        val path = String(value, Charsets.UTF_8)
        val reversed = templates.reverseRedirect(path)
        return reversed.toByteArray(Charsets.UTF_8)
    }

    override fun copyStringToBuffer(columnIndex: Int, buffer: CharArrayBuffer) {
        // 用 getString 确保经过路径重写
        val s = getString(columnIndex)
        if (s != null) {
            buffer.data = s.toCharArray()
            buffer.sizeCopied = s.length
        } else {
            buffer.sizeCopied = 0
        }
    }
}
