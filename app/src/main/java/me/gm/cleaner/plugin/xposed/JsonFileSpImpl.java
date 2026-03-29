package me.gm.cleaner.plugin.xposed;

import android.os.FileObserver;
import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XposedBridge;
import me.gm.cleaner.plugin.dao.JsonSharedPreferencesImpl;
import me.gm.cleaner.plugin.dao.SharedPreferencesWrapper;

public class JsonFileSpImpl extends SharedPreferencesWrapper {
    public final File file;
    protected String contentCache = "";
    private FileObserver mFileObserver;
    
    private final ScheduledExecutorService mScheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> mScheduledFuture;

    public JsonFileSpImpl(File src) {
        file = src;

        reload();

        // 监听所有事件，确保任何编辑器的操作（覆写、删除重建、原子替换）都能被捕获
        int mask = FileObserver.ALL_EVENTS;
        mFileObserver = new FileObserver(src.getParent(), mask) {
            @Override
            public void onEvent(int event, String path) {
                if (path != null && path.equals(file.getName())) {
                    // 忽略纯读取和打开事件，防止无限循环
                    if ((event & (FileObserver.ACCESS | FileObserver.OPEN | FileObserver.CLOSE_NOWRITE)) != 0) {
                        return;
                    }
                    if (mScheduledFuture != null && !mScheduledFuture.isDone()) {
                        mScheduledFuture.cancel(false);
                    }
                    // 将延迟缩小至 50ms 实现快速响应
                    mScheduledFuture = mScheduler.schedule(() -> {
                        reload();
                    }, 50, TimeUnit.MILLISECONDS);
                }
            }
        };
        mFileObserver.startWatching();
    }

    private void ensureFile() {
        if (!file.exists()) {
            try {
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                file.createNewFile();
            } catch (IOException e) {
                XposedBridge.log(e);
            }
        }
    }

    private String readFromFile() {
        ensureFile();
        try (var it = new FileInputStream(file)) {
            var bb = ByteBuffer.allocate(it.available());
            it.getChannel().read(bb);
            return new String(bb.array());
        } catch (IOException e) {
            return "";
        }
    }

    public synchronized boolean reload() {
        String newContent = readFromFile();
        // 若文件内容未发生实质变化，阻断更新防止无意义的重建
        if (newContent.equals(contentCache)) {
            return true;
        }
        contentCache = newContent;
        JSONObject json;
        try {
            if (TextUtils.isEmpty(contentCache)) {
                json = new JSONObject();
            } else {
                json = new JSONObject(contentCache);
            }
        } catch (Throwable e) {
            // 对于 rule 规则，由于是 JSONArray 结构所以必然会抛异常走到这里，这是正常的，用空对象托底即可
            json = new JSONObject();
        }
        delegate = new JsonSharedPreferencesImpl(json);
        XposedBridge.log("MPM_Config: " + file.getName() + " hot reloaded.");
        return true;
    }

    public String read() {
        if (contentCache == null || contentCache.isEmpty()) {
            reload();
        }
        return contentCache;
    }

    public void write(String what) {
        if (what == null) what = "";
        // 阻止重复写入死循环
        if (what.equals(contentCache)) {
            return;
        }
        contentCache = what;
        try {
            delegate = new JsonSharedPreferencesImpl(new JSONObject(what));
        } catch (JSONException ignored) {
        }

        ensureFile();
        var bb = ByteBuffer.wrap(what.getBytes());
        try (var it = new FileOutputStream(file)) {
            it.getChannel().write(bb);
        } catch (IOException e) {
            XposedBridge.log(e);
        }
    }
}