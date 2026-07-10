# 阻止路径配置功能实施计划（LSPosed Native API 方案）

## 目标
在设置页面「全局」下新增阻止路径配置，允许用户配置要拦截的路径，**通过 LSPosed native inline hook 在系统调用层面拦截文件/目录创建**，并记录拦截次数。

## 为什么用 LSPosed native API
- 拦截 `mkdir`/`mkdirat`/`open`/`creat` 系统调用，**覆盖所有应用的所有文件创建路径**
- 不会被 Java 层绕过（native 代码直接调用 `mkdir()` 的情况）
- 与项目现有 `nsp_bridge.c` 的 native 层架构一致，均使用 LSPosed 环境

## 数据流总览

```
Native 层 (libsrx_block.so)
┌──────────────────────────────────────────────┐
│  native_init() 注册 hook:                     │
│  ├─ hook_mkdir   → 路径匹配 → EACCES + cnt++  │
│  ├─ hook_mkdirat → 同上                       │
│  ├─ hook_open    → (O_CREAT) 同上             │
│  └─ hook_creat   → 同上                       │
│                                               │
│  JNI:                                         │
│  ├─ nativeSetBlockPaths(String[])              │
│  ├─ nativeGetBlockCount() → long               │
│  └─ nativeResetBlockCount()                    │
└───────────────┬───────────────────────────────┘
                │ JNI
Java (Xposed 进程内)
┌───────────────▼───────────────────────────────┐
│  BlockNativeHook.kt (JNI 桥接)                 │
│  ManagerService.kt                             │
│  ├─ initBlockNative() → load lib + 推送路径    │
│  ├─ getBlockCount() → JNI                      │
│  └─ writeSp(ROOT) → 检测 block_paths 变更 → JNI│
└───────────────┬───────────────────────────────┘
                │ AIDL
UI (App 进程)
┌───────────────▼───────────────────────────────┐
│  BinderViewModel.getBlockCount()               │
│  SettingsScreen                                 │
│  ├─ rootSp.block_paths[] 路径列表编辑           │
│  ├─ 写 rootSp → ManagerService 感知 → JNI 推送  │
│  └─ 显示拦截次数 (getBlockCount)                │
└─────────────────────────────────────────────────┘
```

## 文件变更清单

### 一、新增文件（4 个）

| # | 文件 | 说明 | 来源 |
|---|------|------|------|
| 1 | `app/src/main/jni/srx_block/native_init.h` | LSPosed native API 类型定义 | 从 StorageRedirectionXposed 复制 |
| 2 | `app/src/main/jni/srx_block/block_hook.cpp` | 核心：系统调用 hook + 路径匹配 + 原子计数器 + JNI 接口 | 基于 StorageRedirectionXposed 的 `native_hook.cpp` 改造 |
| 3 | `app/src/main/jni/srx_block/CMakeLists.txt` | 编译 `srx_block` 库 | 新建 |
| 4 | `app/src/main/java/.../xposed/BlockNativeHook.kt` | Java JNI 桥接对象 | 新建 |

### 二、修改文件（10 个）

| # | 文件 | 修改内容 |
|---|------|----------|
| 5 | `app/src/main/jni/CMakeLists.txt` | `add_subdirectory(srx_block)` |
| 6 | `app/src/main/aidl/.../IManagerService.aidl` | 新增 `long getBlockCount() = 46` |
| 7 | `app/src/main/java/.../xposed/ManagerService.kt` | 新增 `initBlockNative()`、`getBlockCount()`、`onBlockPathsChanged()` |
| 8 | `app/src/main/java/.../xposed/XposedInit.kt` | 在 `onMediaProviderLoaded()` 中初始化 native hook |
| 9 | `app/src/main/java/.../ui/module/BinderViewModel.kt` | 新增 `getBlockCount()` |
| 10 | `app/src/main/java/.../ui/screens/settings/SettingsScreen.kt` | 全局下新增阻止路径 UI 区块 |
| 11 | `app/src/main/java/.../ui/navigation/AppNavHost.kt` | 传递 `blockCount` / `onBlockPathsChange` |
| 12 | `app/src/main/res/values/strings.xml` | 新增 block 相关字符串 |
| 13 | `app/src/main/res/values-zh-rCN/strings.xml` | 中文翻译 |
| 14 | `app/src/main/res/values-zh-rTW/strings.xml` | 繁体翻译 |

---

## 各文件详细设计

### 1. `native_init.h`（原样复制）

从 `StorageRedirectionXposed/app/src/main/cpp/native_init.h` 直接复制，定义：
- `HookFunType` — 函数指针，用于安装 hook
- `NativeAPIEntries` — LSPosed 传入的 API 入口结构体
- `NativeInit` — native_init 入口函数类型

### 2. `block_hook.cpp` 核心实现

**数据结构**：
```cpp
#include "native_init.h"
#include <string>
#include <vector>
#include <mutex>
#include <atomic>
#include <cstring>
#include <cerrno>
#include <dlfcn.h>
#include <android/log.h>

static HookFunType g_hook_func = nullptr;

static std::vector<std::string> g_blocked_paths;
static std::mutex g_paths_mutex;
static std::atomic<long> g_block_count{0};
```

**路径匹配函数**（前缀匹配，保证目录边界）：
```cpp
static bool is_path_blocked(const char* path) {
    if (!path) return false;
    std::lock_guard<std::mutex> lock(g_paths_mutex);
    for (const auto& blocked : g_blocked_paths) {
        if (strncmp(path, blocked.c_str(), blocked.size()) == 0) {
            char next = path[blocked.size()];
            if (next == '\0' || next == '/') return true;
        }
    }
    return false;
}
```

**Hook 函数**（4 个系统调用）：
```cpp
static int (*backup_mkdir)(const char*, mode_t) = nullptr;
static int (*backup_mkdirat)(int, const char*, mode_t) = nullptr;
static int (*backup_open)(const char*, int, ...) = nullptr;
static int (*backup_creat)(const char*, mode_t) = nullptr;

static int hook_mkdir(const char* path, mode_t mode) {
    if (is_path_blocked(path)) {
        g_block_count++;
        errno = EACCES;
        return -1;
    }
    return backup_mkdir ? backup_mkdir(path, mode) : -1;
}

static int hook_mkdirat(int dirfd, const char* path, mode_t mode) {
    if (is_path_blocked(path)) {
        g_block_count++;
        errno = EACCES;
        return -1;
    }
    return backup_mkdirat ? backup_mkdirat(dirfd, path, mode) : -1;
}

static int hook_open(const char* path, int flags, ...) {
    va_list args;
    va_start(args, flags);
    mode_t mode = (flags & O_CREAT) ? va_arg(args, mode_t) : 0;
    va_end(args);
    
    if ((flags & O_CREAT) && is_path_blocked(path)) {
        g_block_count++;
        errno = EACCES;
        return -1;
    }
    return backup_open ? backup_open(path, flags, mode) : -1;
}

static int hook_creat(const char* path, mode_t mode) {
    if (is_path_blocked(path)) {
        g_block_count++;
        errno = EACCES;
        return -1;
    }
    return backup_creat ? backup_creat(path, mode) : -1;
}
```

**JNI 接口**（3 个）：
```cpp
extern "C" {

JNIEXPORT void JNICALL
Java_me_gm_cleaner_plugin_xposed_BlockNativeHook_nativeSetBlockPaths(
    JNIEnv* env, jclass /*clazz*/, jobjectArray jPaths) {
    std::lock_guard<std::mutex> lock(g_paths_mutex);
    g_blocked_paths.clear();
    jsize count = env->GetArrayLength(jPaths);
    for (jsize i = 0; i < count; i++) {
        auto jStr = (jstring)env->GetObjectArrayElement(jPaths, i);
        const char* chars = env->GetStringUTFChars(jStr, nullptr);
        g_blocked_paths.emplace_back(chars);
        env->ReleaseStringUTFChars(jStr, chars);
        env->DeleteLocalRef(jStr);
    }
}

JNIEXPORT jlong JNICALL
Java_me_gm_cleaner_plugin_xposed_BlockNativeHook_nativeGetBlockCount(
    JNIEnv* /*env*/, jclass /*clazz*/) {
    return (jlong)g_block_count.load();
}

JNIEXPORT void JNICALL
Java_me_gm_cleaner_plugin_xposed_BlockNativeHook_nativeResetBlockCount(
    JNIEnv* /*env*/, jclass /*clazz*/) {
    g_block_count.store(0);
}

}  // extern "C"
```

**native_init 入口**：
```cpp
[[gnu::visibility("default")]] [[gnu::used]]
NativeOnModuleLoaded native_init(const NativeAPIEntries* entries) {
    g_hook_func = entries->hook_func;

    void* real_mkdir = dlsym(RTLD_DEFAULT, "mkdir");
    if (real_mkdir) g_hook_func(real_mkdir, (void*)hook_mkdir, (void**)&backup_mkdir);

    void* real_mkdirat = dlsym(RTLD_DEFAULT, "mkdirat");
    if (real_mkdirat) g_hook_func(real_mkdirat, (void*)hook_mkdirat, (void**)&backup_mkdirat);

    void* real_open = dlsym(RTLD_DEFAULT, "open");
    if (real_open) g_hook_func(real_open, (void*)hook_open, (void**)&backup_open);

    void* real_creat = dlsym(RTLD_DEFAULT, "creat");
    if (real_creat) g_hook_func(real_creat, (void*)hook_creat, (void**)&backup_creat);

    return nullptr;  // 不需要 on_library_loaded 回调
}
```

### 3. `srx_block/CMakeLists.txt`

```cmake
cmake_minimum_required(VERSION 3.4.1)
project(srx_block CXX)

add_library(srx_block SHARED block_hook.cpp)

target_link_libraries(srx_block c log dl)
target_compile_features(srx_block PUBLIC cxx_std_17)
```

### 4. 主 `CMakeLists.txt` 修改

```cmake
add_subdirectory(srx_block)
```

### 5. `BlockNativeHook.kt`

```kotlin
package me.gm.cleaner.plugin.xposed

object BlockNativeHook {
    private var loaded = false

    fun init(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("srx_block")
            loaded = true
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    @JvmStatic external fun nativeSetBlockPaths(paths: Array<String>)
    @JvmStatic external fun nativeGetBlockCount(): Long
    @JvmStatic external fun nativeResetBlockCount()
}
```

### 6. `ManagerService.kt` 修改

新增内容：
```kotlin
import java.util.concurrent.atomic.AtomicLong

// 在类中新增
fun initBlockNative() {
    if (!BlockNativeHook.init()) {
        L.e("BlockNativeHook", "Failed to load libsrx_block.so")
        return
    }
    // 推送已有阻止路径
    syncBlockPathsToNative()
}

fun syncBlockPathsToNative() {
    val paths = rootSp.getStringSet("block_paths", emptySet())
    BlockNativeHook.nativeSetBlockPaths(paths.toTypedArray())
}

// 在 writeSp(ROOT_PREFERENCES) 中检测 block_paths 变更
// 在 onRootSettingsChange 写回 rootSp 时，ManagerService 需要感知并推送
// 方案：writeSp 中如果 who == ROOT_PREFERENCES，解析 JSON 检查 block_paths 字段，
//       若有变化则调用 syncBlockPathsToNative()

override fun getBlockCount(): Long {
    enforceCallerPermission()
    return if (BlockNativeHook.init()) BlockNativeHook.nativeGetBlockCount() else 0L
}
```

在 `onCreate()` 末尾调用 `initBlockNative()`。

### 7. `XposedInit.kt` 修改

在 `onMediaProviderLoaded()` 中：
```kotlin
// 在 hook 安装之前或之后初始化 native block
BlockNativeHook.init()
service.syncBlockPathsToNative()
```

注意：`XposedInit` 继承 `ManagerService`，所以 `service` 就是 `this`。

### 8. `IManagerService.aidl` 新增

```aidl
long getBlockCount() = 46;
```

### 9. `BinderViewModel.kt` 新增

```kotlin
fun getBlockCount(): Long =
    serviceCall("getBlockCount") { getBlockCount() } ?: 0L
```

### 10. `SettingsScreen.kt` UI 布局

在「全局」Section 的 `PreferenceGroup` 中，`usage_record` 开关之后新增：

```
SectionHeader("阻止路径", "阻止指定路径的文件/文件夹被创建")
PreferenceGroup {
    PathPickerRow("添加路径")  // 点击 → OpenDocumentTree
    ─────────────────
    /storage/emulated/0/DCIM/.thumbnails  🗑
    /storage/emulated/0/...                🗑
    ─────────────────
    已拦截 N 次
}
```

交互逻辑：
- 读取 `rootSp` 的 `block_paths` 和 `block_count`
- 添加路径：`OpenDocumentTree` 选择目录 → 写入 rootSp
- 删除路径：点击 🗑 移除 → 写入 rootSp
- 拦截次数：通过 `BinderViewModel.getBlockCount()` 获取
- 路径列表清空时自动重置计数（调用 `nativeResetBlockCount()`）

UI 参数签名新增：
```kotlin
fun SettingsScreen(
    ...
    blockCount: Long,
    onBlockPathsChange: (List<String>) -> Unit,
)
```

### 11. 字符串资源

```xml
<!-- values/strings.xml -->
<string name="block_path_title">Blocked paths</string>
<string name="block_path_summary">Block specified files/folders from being created at the system call level</string>
<string name="block_path_add">Add a path</string>
<string name="block_path_count">Blocked %d time(s)</string>
<string name="block_path_empty">No blocked paths configured</string>

<!-- values-zh-rCN/strings.xml -->
<string name="block_path_title">阻止路径</string>
<string name="block_path_summary">在系统调用层面阻止指定路径的文件或文件夹被创建</string>
<string name="block_path_add">添加路径</string>
<string name="block_path_count">已拦截 %d 次</string>
<string name="block_path_empty">尚未配置阻止路径</string>
```

## 路径匹配规则

前缀匹配，保证目录边界：
- `blocked = "/storage/emulated/0/DCIM/.thumbnails"`
- `"/storage/emulated/0/DCIM/.thumbnails/hidden.jpg"` → 匹配 ✅
- `"/storage/emulated/0/DCIM/.thumbnails"` → 匹配 ✅
- `"/storage/emulated/0/DCIM/other.jpg"` → 不匹配 ❌

## 实现顺序

1. Native 层：`native_init.h` + `block_hook.cpp` + `srx_block/CMakeLists.txt`
2. 主 CMakeLists.txt 添加子目录
3. JNI 桥接：`BlockNativeHook.kt`
4. AIDL 接口：`IManagerService.aidl`
5. ManagerService：初始化 + 路径推送 + getBlockCount
6. XposedInit：集成初始化
7. BinderViewModel：getBlockCount
8. 字符串资源（3 个文件）
9. SettingsScreen：UI 布局
10. AppNavHost：传递参数