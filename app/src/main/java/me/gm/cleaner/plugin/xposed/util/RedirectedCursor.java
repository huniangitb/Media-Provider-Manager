package me.gm.cleaner.plugin.xposed.util;

import android.database.Cursor;
import android.database.CursorWrapper;
import android.provider.MediaStore;
import java.util.Map;

public class RedirectedCursor extends CursorWrapper {
    private final Map<String, String> mRedirectMap; // Key: 真实路径(新), Value: 原始路径(旧)
    private final int mDataColumnIndex;

    public RedirectedCursor(Cursor cursor, Map<String, String> redirectMap) {
        super(cursor);
        mRedirectMap = redirectMap;
        mDataColumnIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA);
    }

    @Override
    public String getString(int columnIndex) {
        String value = super.getString(columnIndex);
        // 如果当前读取的是 DATA 列，且该路径在重定向列表中
        if (columnIndex == mDataColumnIndex && value != null) {
            // 遍历规则进行反向替换 (这里为了性能，实际生产中最好用前缀树或更高效的匹配)
            for (Map.Entry<String, String> entry : mRedirectMap.entrySet()) {
                String realPathPrefix = entry.getKey();
                if (value.startsWith(realPathPrefix)) {
                    // 将 /Redirected/Path/img.jpg 还原为 /Original/Path/img.jpg
                    return value.replaceFirst(realPathPrefix, entry.getValue());
                }
            }
        }
        return value;
    }
}