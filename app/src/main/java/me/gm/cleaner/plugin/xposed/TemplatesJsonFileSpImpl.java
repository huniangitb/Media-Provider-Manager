package me.gm.cleaner.plugin.xposed;

import java.io.File;

import me.gm.cleaner.plugin.model.Templates;

public final class TemplatesJsonFileSpImpl extends JsonFileSpImpl {

    public TemplatesJsonFileSpImpl(File src) {
        super(src);
    }

    @Override
    public synchronized boolean reload() {
        return super.reload();
    }

    // 动态解析返回全新实例，彻底解决多线程/多进程读取污染的问题
    public Templates getTemplates() {
        return new Templates(read());
    }
}