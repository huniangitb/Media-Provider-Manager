package me.gm.cleaner.plugin.xposed;

import java.io.File;

import me.gm.cleaner.plugin.model.Templates;

public final class TemplatesJsonFileSpImpl extends JsonFileSpImpl {
    private volatile Templates templatesCache;

    public TemplatesJsonFileSpImpl(File src) {
        super(src);
    }

    @Override
    protected void reload() {
        super.reload();
        templatesCache = new Templates(contentCache);
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