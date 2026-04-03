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
        delegate = new JsonSharedPreferencesImpl(new JSONObject()); 
        reload();

        int mask = FileObserver.ALL_EVENTS;
        mFileObserver = new FileObserver(src.getParent(), mask) {
            @Override
            public void onEvent(int event, String path) {
                if (path != null && path.equals(file.getName())) {
                    if ((event & (FileObserver.ACCESS | FileObserver.OPEN | FileObserver.CLOSE_NOWRITE)) != 0) {
                        return;
                    }
                    if (mScheduledFuture != null && !mScheduledFuture.isDone()) {
                        mScheduledFuture.cancel(false);
                    }
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
        
        if (TextUtils.isEmpty(newContent) && !TextUtils.isEmpty(contentCache)) {
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            newContent = readFromFile();
        }

        // 关键修复：必须确保 delegate 已经初始化，才能在内容相同时提前返回
        if (delegate != null && newContent.equals(contentCache)) {
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
            json = new JSONObject();
        }
        
        // 确保 delegate 被正确赋值，消灭 NPE 隐患
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