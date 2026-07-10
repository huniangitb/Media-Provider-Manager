# Media-Provider-Manager 迁移到 LSPosed (libxposed) API 计划

## 概述

将项目从旧版 EdXposed API (`de.robv.android.xposed:api:82`) 迁移到新版 LSPosed API (`io.github.libxposed:api:102`)。

## 旧版 API 使用清单

### 1. 依赖
- `app/build.gradle`: `compileOnly 'de.robv.android.xposed:api:82'`

### 2. AndroidManifest.xml
- `category: de.robv.android.xposed.category.MODULE_SETTINGS`

### 3. 导入的 API 类

| 旧 API | 使用文件 | 用途 |
|--------|---------|------|
| `IXposedHookLoadPackage` | `XposedInit.kt:38` | 模块入口接口 |
| `IXposedHookZygoteInit` | `XposedInit.kt:38` | Zygote 初始化接口 |
| `XC_LoadPackage.LoadPackageParam` | `XposedInit.kt:28` | 包加载参数 |
| `XC_MethodHook` | 所有 Hooker 类 | 方法 Hook 基类 |
| `XC_MethodHook.MethodHookParam` | 所有 Hooker 类 | Hook 参数 |
| `XposedBridge.hookAllMethods()` | `XposedInit.kt:102-112` | 批量 Hook 所有重载 |
| `XposedHelpers.findAndHookMethod()` | `XposedInit.kt:42,121,134` | 查找并 Hook 方法 |
| `XposedHelpers.findClass()` | `XposedInit.kt:62`, `ManagerService.kt:305-310` | 反射查找类 |
| `XposedHelpers.callMethod()` | `ManagerService.kt:305-326`, 各 Hooker | 反射调用方法 |
| `XposedHelpers.callStaticMethod()` | `ManagerService.kt:305-310` | 反射调用静态方法 |
| `XposedHelpers.getObjectField()` | `MediaProviderHooker.kt:152` | 反射获取字段 |
| `XposedBridge.log()` | `L.kt:80-321` | 日志输出 |

## API 映射表

### 模块入口

| 旧 API | 新 API |
|--------|--------|
| `IXposedHookLoadPackage` | `XposedModule` (继承) |
| `IXposedHookZygoteInit` | 不需要（新 API 不再需要 Zygote 初始化） |
| `handleLoadPackage(LoadPackageParam)` | `onPackageLoaded(PackageLoadedParam)` / `onPackageReady(PackageReadyParam)` |
| `initZygote(StartupParam)` | 移除，改为在 `onModuleLoaded()` 中初始化资源 |
| `LoadPackageParam.packageName` | `PackageLoadedParam.getPackageName()` |
| `LoadPackageParam.classLoader` | `PackageReadyParam.getClassLoader()` |

### Hook 机制

| 旧 API | 新 API |
|--------|--------|
| `XC_MethodHook` | `XposedInterface.Hooker` |
| `XC_MethodHook.beforeHookedMethod(param)` | `Hooker.intercept(chain)` |
| `XC_MethodHook.afterHookedMethod(param)` | `chain.proceed()` 后的逻辑 |
| `MethodHookParam.thisObject` | `Chain.getThisObject()` |
| `MethodHookParam.args[]` | `Chain.getArgs()` / `Chain.getArg(index)` |
| `MethodHookParam.result` | `Chain.proceed()` 的返回值 |
| `XposedBridge.hookAllMethods(cls, name, hook)` | `Class.getDeclaredMethods()` 遍历 + `hook(executable).intercept(hooker)` |
| `XposedHelpers.findAndHookMethod(cls, name, ..., hook)` | `cls.getDeclaredMethod(name, ...)` + `hook(method).intercept(hooker)` |
| `XposedHelpers.findClass(name, loader)` | `Class.forName(name, false, loader)` |
| `XposedHelpers.callMethod(obj, name, ...)` | `method.invoke(obj, ...)` 或 `getInvoker(method).invoke(obj, ...)` |
| `XposedHelpers.callStaticMethod(cls, name, ...)` | `method.invoke(null, ...)` 或 `getInvoker(method).invoke(null, ...)` |
| `XposedHelpers.getObjectField(obj, name)` | 反射直接访问 `field.get(obj)` |

### 其他

| 旧 API | 新 API |
|--------|--------|
| `XposedBridge.log(msg)` | `module.log(priority, tag, msg)` |
| `XC_MethodHook.MethodHookParam` 扩展属性 | 需要自己实现反射调用 |

## 文件变更清单

### 新增文件

| # | 文件 | 说明 |
|---|------|------|
| 1 | `app/src/main/assets/module.prop` | LSPosed 模块声明文件（替代 `xposedminversion` meta-data） |
| 2 | `app/src/main/resources/META-INF/xposed/java_init.list` | 模块入口类声明 |

### 删除文件

| # | 文件 | 说明 |
|---|------|------|
| 1 | 无 | 旧文件可保留，但引用路径需清理 |

### 修改文件

| # | 文件 | 修改内容 |
|---|------|----------|
| 1 | `app/build.gradle` | 替换依赖 `de.robv.android.xposed:api:82` → `io.github.libxposed:api:102` |
| 2 | `app/src/main/AndroidManifest.xml` | 替换 `xposedmodule`/`xposeddescription`/`xposedminversion`/`xposedscope` meta-data 为 LSPosed 格式 |
| 3 | `app/src/main/java/.../xposed/XposedInit.kt` | **核心重构**：从 `IXposedHookLoadPackage` + `IXposedHookZygoteInit` 改为继承 `XposedModule` |
| 4 | `app/src/main/java/.../xposed/hooker/QueryHooker.kt` | `XC_MethodHook` → `XposedInterface.Hooker` |
| 5 | `app/src/main/java/.../xposed/hooker/InsertHooker.kt` | 同上 |
| 6 | `app/src/main/java/.../xposed/hooker/DeleteHooker.kt` | 同上 |
| 7 | `app/src/main/java/.../xposed/hooker/FileHooker.kt` | 同上 |
| 8 | `app/src/main/java/.../xposed/hooker/MediaProviderHooker.kt` | 去掉 `XC_MethodHook` 依赖，适配新 API |
| 9 | `app/src/main/java/.../xposed/ManagerService.kt` | 移除 `XposedHelpers` 依赖，改为原生反射 |
| 10 | `app/src/main/java/.../util/L.kt` | `XposedBridge.log()` → `android.util.Log` 或 `module.log()` |

## 详细迁移方案

### 1. 模块入口重构（XposedInit.kt）

**旧代码**：
```kotlin
class XposedInit : ManagerService(), IXposedHookLoadPackage, IXposedHookZygoteInit {
    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        // 1. 检查包名
        // 2. 通过 ContentProvider.attachInfo 判断目标进程
        // 3. 分别初始化 MediaProvider / DownloadManager 钩子
    }
    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        // 加载模块资源
    }
}
```

**新代码**：
```kotlin
class XposedInit : ManagerService() {
    // 不再需要 implements IXposedHookLoadPackage
    // 通过 onPackageReady() 回调接收目标进程通知
    
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        super.onModuleLoaded(param)
        // 初始化 ManagerService（替代原来 initZygote 中的逻辑）
        // 注意：新 API 中模块入口在目标进程内执行，不需要手动加载资源
    }
    
    override fun onPackageReady(param: PackageReadyParam) {
        super.onPackageReady(param)
        val packageName = param.getPackageName()
        val classLoader = param.getClassLoader()
        
        when (packageName) {
            "com.android.providers.media.module" -> {
                onMediaProviderLoaded(classLoader)
            }
            "com.android.providers.downloads" -> {
                onDownloadManagerLoaded(classLoader)
            }
        }
    }
}
```

### 2. 依赖变更（build.gradle）

```groovy
// 旧
compileOnly 'de.robv.android.xposed:api:82'

// 新
compileOnly 'io.github.libxposed:api:102.0.0'
```

### 3. AndroidManifest.xml 变更

```xml
<!-- 旧：EdXposed 格式 -->
<meta-data android:name="xposedmodule" android:value="true" />
<meta-data android:name="xposeddescription" android:value="@string/description" />
<meta-data android:name="xposedminversion" android:value="53" />
<meta-data android:name="xposedscope" android:resource="@array/recommend_package" />

<!-- 可以保留 activity-alias 用于模块设置入口 -->
<activity-alias ...>
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="de.robv.android.xposed.category.MODULE_SETTINGS" />
    </intent-filter>
</activity-alias>
```

### 4. module.prop（新增）

文件：`app/src/main/assets/module.prop`
```properties
# LSPosed module configuration
# 模块入口类（可选，可被 META-INF/xposed/java_init.list 覆盖）
entry = me.gm.cleaner.plugin.xposed.XposedInit
# 模块支持的 API 版本
api = 102
# 模块描述
description = An Xposed module intended to prevent media storage abuse.
# 模块作者
author = Green Mushroom
```

### 5. Hooker 类重构

**旧模式**（继承 `XC_MethodHook`，使用 `beforeHookedMethod`）：
```kotlin
class QueryHooker(private val service: ManagerService) : XC_MethodHook(), MediaProviderHooker {
    override fun beforeHookedMethod(param: MethodHookParam) {
        val uid = param.callingPackage
        // ... 拦截逻辑
        param.result = filteredCursor  // 或 null 表示拦截
    }
}
```

**新模式**（实现 `XposedInterface.Hooker`，使用 `intercept`）：
```kotlin
class QueryHooker(private val service: ManagerService) : XposedInterface.Hooker, MediaProviderHooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val method = chain.executable
        val thisObject = chain.thisObject
        val args = chain.args
        
        // ... 拦截逻辑（相当于旧的 beforeHookedMethod）
        
        // 需要放行时调用 chain.proceed() 并返回其结果
        // 需要拦截时直接返回替代值（如 null）
        return chain.proceed()
    }
}
```

### 6. Hook 安装方式变更

**旧方式**：
```kotlin
XposedBridge.hookAllMethods(mediaProvider, "queryInternal", QueryHooker(this))
XposedHelpers.findAndHookMethod(File::class.java, "mkdir", FileHooker(this))
```

**新方式**：
```kotlin
// 遍历所有方法，匹配名称后 hook
for (method in mediaProvider.declaredMethods) {
    if (method.name == "queryInternal") {
        hook(method).intercept(QueryHooker(this))
    }
    if (method.name == "insertFile") {
        hook(method).intercept(InsertHooker(this))
    }
    if (method.name == "deleteInternal") {
        hook(method).intercept(DeleteHooker(this))
    }
}

// 对 File.mkdir/mkdirs
val fileMkdir = File::class.java.getDeclaredMethod("mkdir")
hook(fileMkdir).intercept(FileHooker(this))
val fileMkdirs = File::class.java.getDeclaredMethod("mkdirs")
hook(fileMkdirs).intercept(FileHooker(this))
```

### 7. 反射调用迁移

**旧方式**（`XposedHelpers.callMethod`）：
```kotlin
val result = XposedHelpers.callMethod(thisObject, "getQueryBuilder", type, table, uri, query, honoredArgs)
```

**新方式**（原生反射或 `getInvoker`）：
```kotlin
// 方案 A：原生反射（推荐，不依赖 Xposed）
val method = thisObject.javaClass.getDeclaredMethod("getQueryBuilder", ...)
method.isAccessible = true
val result = method.invoke(thisObject, type, table, uri, query, honoredArgs)

// 方案 B：Invoker（新 API 提供，绕过访问检查）
val method = thisObject.javaClass.getDeclaredMethod("getQueryBuilder", ...)
val invoker = getInvoker(method)
val result = invoker.invoke(thisObject, type, table, uri, query, honoredArgs)
```

### 8. 日志迁移

**旧方式**（`XposedBridge.log`）：
```kotlin
XposedBridge.log("TAG: message")
XposedBridge.log(throwable)
```

**新方式**（`module.log` 或 `android.util.Log`）：
```kotlin
// 在模块入口类中
module.log(Log.INFO, "TAG", "message")
module.log(Log.ERROR, "TAG", "message", throwable)

// 或直接使用 android.util.Log（推荐，更简单）
Log.i("TAG", "message")
Log.e("TAG", "message", throwable)
```

### 9. MediaProviderHooker 扩展属性迁移

旧代码中 `MediaProviderHooker` 定义了多个 `XC_MethodHook.MethodHookParam` 的扩展属性：
- `param.isFuseThread`
- `param.isSystemCallingPackage`
- `param.callingPackage`
- `param.isCallingPackageAllowedHidden`
- `param.matchUri(uri, allowHidden)`

这些在新 API 中不再有 `MethodHookParam`，需要通过 `chain.getThisObject()` 获取 MediaProvider 实例后自行反射调用。

```kotlin
// 新方式：从 Chain 中获取信息
fun XposedInterface.Chain.getCallingPackage(): String {
    val thisObject = thisObject ?: return ""
    // 反射获取 mCallingIdentity
    val threadLocal = thisObject.javaClass
        .getDeclaredField("mCallingIdentity").also { it.isAccessible = true }
        .get(thisObject) as ThreadLocal<*>
    return threadLocal.get()?.javaClass
        ?.getMethod("getPackageName")?.invoke(threadLocal.get()) as? String ?: ""
}
```

### 10. ManagerService 中 XposedHelpers 调用的迁移

`ManagerService.kt` 中使用了 `XposedHelpers.callStaticMethod` 和 `XposedHelpers.findClass` 来获取 `IPackageManager` 服务：

```kotlin
// 旧
val binder = XposedHelpers.callStaticMethod(
    XposedHelpers.findClass("android.os.ServiceManager", classLoader),
    "getService", "package"
) as IBinder

// 新
val smClass = Class.forName("android.os.ServiceManager", false, classLoader)
val getService = smClass.getDeclaredMethod("getService", String::class.java)
val binder = getService.invoke(null, "package") as IBinder
```

## 实施顺序

1. **依赖和 Manifest 变更**：`build.gradle` + `AndroidManifest.xml` + 新增 `module.prop`
2. **模块入口重构**：`XposedInit.kt` 改为继承 `XposedModule`
3. **日志工具迁移**：`L.kt` 去掉 `XposedBridge.log` 改为 `android.util.Log`
4. **Hooker 类重构**：`QueryHooker`、`InsertHooker`、`DeleteHooker`、`FileHooker`
5. **MediaProviderHooker 适配**：去掉 `XC_MethodHook` 依赖
6. **ManagerService 反射迁移**：去掉 `XposedHelpers` 依赖
7. **编译验证**：`./gradlew assembleDebug` 确认通过

## 注意事项

1. **新 API 不再支持 Zygote 注入**：`initZygote` 中的资源加载逻辑需要迁移到 `onModuleLoaded` 中
2. **新 API 目标进程执行**：模块入口类在目标进程内执行，`ManagerService` 的初始化方式可能变化
3. **`XC_MethodHook.beforeHookedMethod` 变成 `Hooker.intercept`**：返回值语义不同，旧代码中通过 `param.result = x` 设置结果，新代码中通过 `return x` 返回结果
4. **`hookAllMethods` 不再存在**：需要自己遍历方法列表
5. **`module.prop` 必须存在**：LSPosed 通过 `assets/module.prop` 识别模块
6. **`META-INF/xposed/java_init.list` 可选**：用于指定模块入口类，也可通过 `module.prop` 配置