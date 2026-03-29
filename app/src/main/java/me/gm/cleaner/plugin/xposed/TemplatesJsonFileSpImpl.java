package me.gm.cleaner.plugin.xposed;

import java.io.File;

import me.gm.cleaner.plugin.model.Templates;

public final class TemplatesJsonFileSpImpl extends JsonFileSpImpl {

    public TemplatesJsonFileSpImpl(File src) {
        super(src);
    }

    @Override
    public synchronized boolean reload() {
        boolean success = super.reload();
        // 尝试验证 JSON 是否能正确映射为 Templates（起校验作用）
        try {
            new Templates(contentCache);
        } catch (Throwable e) {
            success = false;
        }
        return success;
    }

    // 每次获取均返回新实例，避免 Templates.kt 内的 matchingTemplates 状态被多线程 Hook 并发污染
    public Templates getTemplates() {
        return new Templates(read());
    }
}