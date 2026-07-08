package me.gm.cleaner.plugin;

import me.gm.cleaner.plugin.model.ParceledListSlice;
import me.gm.cleaner.plugin.IMediaChangeObserver;

interface IManagerService {

    int getModuleVersion() = 0;

    ParceledListSlice<PackageInfo> getInstalledPackages(int userId, int flags) = 10;

    PackageInfo getPackageInfo(String packageName, int flags, int userId) = 11;

    String readSp(int who) = 20;

    void writeSp(int who, String what) = 21;

    void clearAllTables() = 30;

    int packageUsageTimes(int operation, in List<String> packageNames) = 31;

    void registerMediaChangeObserver(in IMediaChangeObserver observer) = 32;

    void unregisterMediaChangeObserver(in IMediaChangeObserver observer) = 33;

    // ----- 远程配置接口 -----

    /** 读取远程配置（只读，返回 JSON 字符串）*/
    String readRemoteSp() = 40;

    /** 写入远程配置 —— 静默忽略（远程配置只读不可改） */
    void writeRemoteSp(String what) = 41;

    /** 触发通过 UDS 拉取远程配置（向 injector 发送 GET 命令） */
    boolean triggerRemotePull() = 42;

    /** 获取远程配置源状态（最后拉取时间戳、错误信息、模板数等） */
    String getRemoteConfigStatus() = 43;

    /** 获取远程配置调试日志（JSON 字符串数组，最近 100 条） */
    String getRemoteConfigLogs() = 44;

    /** 读取本地 rule 配置（不合并远程），返回 JSON 字符串 */
    String readRuleSp() = 45;
}
