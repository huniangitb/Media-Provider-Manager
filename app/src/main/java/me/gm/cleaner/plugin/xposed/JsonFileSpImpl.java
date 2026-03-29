package me.gm.cleaner.plugin.xposed;

import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import de.robv.android.xposed.XposedBridge;
import me.gm.cleaner.plugin.dao.JsonSharedPreferencesImpl;
import me.gm.cleaner.plugin.dao.SharedPreferencesWrapper;

public class JsonFileSpImpl extends SharedPreferencesWrapper {
    public final File file;
    protected String contentCache;
    private FileObserver mFileObserver;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mReloadRunnable = this::reload;

    public JsonFileSpImpl(File src) {
        file = src;

        reload();

        mFileObserver = new FileObserver(src.getParent(), FileObserver.MODIFY | FileObserver.CREATE) {
            @Override
            public void onEvent(int event, String path) {
                if (path != null && path.equals(file.getName())) {
                    mHandler.removeCallbacks(mReloadRunnable);
                    mHandler.postDelayed(mReloadRunnable, 1000);
                }
            }
        };
        mFileObserver.startWatching();
    }

    private void ensureFile() {
        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (IOException e) {
                XposedBridge.log(e);
                throw new RuntimeException(e);
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
            XposedBridge.log(e);
            return "";
        }
    }

    protected void reload() {
        contentCache = readFromFile();
        JSONObject json;
        try {
            if (TextUtils.isEmpty(contentCache)) {
                json = new JSONObject();
            } else {
                json = new JSONObject(contentCache);
            }
        } catch (JSONException e) {
            json = new JSONObject();
        }
        delegate = new JsonSharedPreferencesImpl(json);
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