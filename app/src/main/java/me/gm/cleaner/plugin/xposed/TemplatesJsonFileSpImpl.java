package me.gm.cleaner.plugin.xposed;

import java.io.File;

import de.robv.android.xposed.XposedBridge;
import me.gm.cleaner.plugin.model.Templates;

public final class TemplatesJsonFileSpImpl extends JsonFileSpImpl {
    private volatile Templates templatesCache;

    public TemplatesJsonFileSpImpl(File src) {
        super(src);
    }

    @Override
    public synchronized boolean reload() {
        boolean success = super.reload();
        try {
            templatesCache = new Templates(contentCache);
        } catch (Throwable e) {
            success = false;
            XposedBridge.log("MPM_Config: Failed to parse Templates model: " + e.getMessage());
        }
        if (templatesCache == null) {
            templatesCache = new Templates("");
        }
        return success;
    }

    @Override
    public void write(String what) {
        super.write(what);
        templatesCache = new Templates(what);
    }

    public Templates getTemplates() {
        return templatesCache;
    }
}