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
    protected String contentCache;
    private FileObserver mFileObserver;
    
    private final ScheduledExecutorService mScheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> mScheduledFuture;

    public JsonFileSpImpl(File src) {
        file = src;

        reload();

        int mask = FileObserver.MODIFY | FileObserver.CREATE | FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO;
        mFileObserver = new FileObserver(src.getParent(), mask) {
            @Override
            public void onEvent(int event, String path) {
                if (path != null && path.equals(file.getName())) {
                    if (mScheduledFuture != null && !mScheduledFuture.isDone()) {
                        mScheduledFuture.cancel(false);
                    }
                    mScheduledFuture = mScheduler.schedule(() -> {
                        reload();
                    }, 1000, TimeUnit.MILLISECONDS);
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
        contentCache = readFromFile();
        JSONObject json;
        boolean success = true;
        try {
            if (TextUtils.isEmpty(contentCache)) {
                json = new JSONObject();
            } else {
                json = new JSONObject(contentCache);
            }
        } catch (Throwable e) {
            json = new JSONObject();
            success = false;
            XposedBridge.log("MPM_Config: Failed to parse JSON in " + file.getName() + ": " + e.getMessage());
        }
        delegate = new JsonSharedPreferencesImpl(json);
        if (success) {
            XposedBridge.log("MPM_Config: " + file.getName() + " reloaded successfully.");
        }
        return success;
    }

    public String read() {
        if (contentCache == null) {
            contentCache = readFromFile();
        }
        return contentCache;
    }

    public void write(String what) {
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