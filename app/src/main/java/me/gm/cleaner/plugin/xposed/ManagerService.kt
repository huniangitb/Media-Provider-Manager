/*
 * Copyright 2021 Green Mushroom
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.gm.cleaner.plugin.xposed

import android.content.Context
import android.content.pm.PackageInfo
import android.content.res.Resources
import android.os.*
import androidx.room.Room
import de.robv.android.xposed.XposedHelpers
import me.gm.cleaner.plugin.BuildConfig
import me.gm.cleaner.plugin.IManagerService
import me.gm.cleaner.plugin.IMediaChangeObserver
import me.gm.cleaner.plugin.R
import me.gm.cleaner.plugin.dao.MIGRATION_1_2
import me.gm.cleaner.plugin.dao.MediaProviderRecord
import me.gm.cleaner.plugin.dao.MediaProviderRecordDao
import me.gm.cleaner.plugin.dao.MediaProviderRecordDatabase
import me.gm.cleaner.plugin.model.ParceledListSlice
import me.gm.cleaner.plugin.model.SpIdentifiers
import me.gm.cleaner.plugin.model.Templates
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

abstract class ManagerService : IManagerService.Stub() {
    lateinit var classLoader: ClassLoader
        protected set
    lateinit var resources: Resources
        protected set
    lateinit var context: Context
        private set
    private lateinit var database: MediaProviderRecordDatabase
    lateinit var dao: MediaProviderRecordDao
        private set
    private val observers = RemoteCallbackList<IMediaChangeObserver>()
    val rootSp by lazy { JsonFileSpImpl(File(context.filesDir, "root")) }
    val ruleSp by lazy { TemplatesJsonFileSpImpl(File(context.filesDir, "rule")) }

    // 远程配置 —— 只读，优先级最低
    private var remoteSp: JsonFileSpImpl? = null
    private var remoteConfigFetcher: RemoteConfigFetcher? = null
    private var configSubscriptionManager: ConfigSubscriptionManager? = null

    private var appUid: Int = -1

    // Async database write mechanism
    private val recordQueue = ConcurrentLinkedQueue<MediaProviderRecord>()
    private var writeHandler: Handler? = null
    private var handlerThread: HandlerThread? = null
    private val hasPendingWrite = AtomicBoolean(false)

    private fun enforceCallerPermission() {
        val callingUid = Binder.getCallingUid()
        if (callingUid != appUid && callingUid != Process.SYSTEM_UID) {
            throw SecurityException("Unauthorized caller: uid=$callingUid")
        }
    }

    protected fun onCreate(context: Context) {
        this.context = context
        appUid = context.packageManager.getPackageUid(BuildConfig.APPLICATION_ID, 0)
        database = Room
            .databaseBuilder(
                context,
                MediaProviderRecordDatabase::class.java,
                MEDIA_PROVIDER_USAGE_RECORD_DATABASE_NAME
            )
            .addMigrations(MIGRATION_1_2)
            .build()
        dao = database.mediaProviderRecordDao()
        
        // Initialize async write handler
        handlerThread = HandlerThread("MediaRecordWriter").also { it.start() }
        writeHandler = object : Handler(handlerThread!!.looper) {
            override fun handleMessage(msg: Message) {
                if (msg.what == MSG_WRITE_RECORDS) {
                    flushRecordQueue()
                }
            }
        }

        // 初始化远程配置
        initRemoteConfig()
    }
    
    /**
     * Clean up resources when service is being destroyed.
     * Should be called from Xposed hook when MediaProvider is shutting down.
     */
    protected fun onDestroy() {
        // Flush remaining records before shutdown
        flushRecordQueueSync()

        // Remove pending dispatch callbacks
        writeHandler?.removeCallbacksAndMessages(null)
        dispatchScheduled = false
        
        writeHandler = null
        handlerThread?.quitSafely()
        handlerThread = null
        
        // 清理远程配置重试线程
        remoteConfigFetcher?.stop()

        // 停止订阅
        configSubscriptionManager?.stop()

        // Clear observers
        observers.kill()
    }

    // ==================== 远程配置 ====================

    /**
     * 初始化远程配置子系统。
     * 创建独立文件用于持久化远程配置，从文件恢复已拉取内容。
     */
    private fun initRemoteConfig() {
        RemoteConfigLogBuffer.log("=== initRemoteConfig ===")
        val remoteFile = File(context.filesDir, "rule_remote")
        RemoteConfigLogBuffer.log("Remote config file: ${remoteFile.absolutePath}")
        remoteSp = JsonFileSpImpl(remoteFile).also { sp ->
            sp.read()
            RemoteConfigLogBuffer.log("remoteSp initialized, size=${sp.file.length()}")
        }
        val fetcher = RemoteConfigFetcher(remoteFile, remoteSp!!).also {
            it.restoreFromFile()
        }
        remoteConfigFetcher = fetcher

        RemoteConfigLogBuffer.log("RemoteConfig initialized, cached templates: ${fetcher.cachedTemplates.size}")

        // 初始合并：让 hookers 能看到已缓存的远程模板（包含空清空场景）
        ruleSp.templates = Templates(ruleSp.read(), remoteValues = fetcher.cachedTemplates)

        // 启动订阅（JNI 可用时自动启用，免手动拉取）
        configSubscriptionManager = ConfigSubscriptionManager(remoteFile) {
            ruleSp.templates.clearCache()
            remoteSp?.invalidateCache()
            // 订阅更新时同步远程模板到 ruleSp.templates，供 hookers 使用
            ruleSp.templates = Templates(ruleSp.read(),
                remoteValues = ConfigSubscriptionManager.cachedTemplates)
        }.also { it.start() }
    }

    /**
     * 合并本地配置与远程配置，返回 JSON 字符串。
     *
     * 合并规则（优先级：本地 > 远程）：
     * 1. 以远程模板为基座
     * 2. 本地模板覆盖同名模板（template_name 作为合并键）
     * 3. 仅存在于本地的模板追加到末尾
     */
    private fun mergeTemplates(localJson: String?, remoteJson: String?): String {
        // 没有远程配置时直接返回本地
        if (remoteJson.isNullOrBlank()) return localJson ?: "[]"

        val localArray = try {
            JSONArray(localJson ?: "[]")
        } catch (e: Exception) {
            JSONArray()
        }
        val remoteArray = try {
            JSONArray(remoteJson)
        } catch (e: Exception) {
            JSONArray()
        }

        // 建立 template_name → 本地模板 的索引
        val localByName = mutableMapOf<String, JSONObject>()
        for (i in 0 until localArray.length()) {
            val obj = localArray.optJSONObject(i) ?: continue
            val name = obj.optString("template_name")
            if (name.isNotEmpty()) localByName[name] = obj
        }

        // 构建合并结果：以远程为基，同名的被本地覆盖
        val merged = JSONArray()

        for (i in 0 until remoteArray.length()) {
            val remoteObj = remoteArray.optJSONObject(i) ?: continue
            val name = remoteObj.optString("template_name")
            if (name.isEmpty()) continue

            if (localByName.containsKey(name)) {
                // 本地存在同名模板 → 用本地方覆盖远程
                merged.put(localByName[name]!!)
                localByName.remove(name)
            } else {
                // 仅远程 → 直接使用
                merged.put(remoteObj)
            }
        }

        // 追加仅在本地存在的模板
        for (entry in localByName) {
            merged.put(entry.value)
        }

        return merged.toString()
    }
    
    /**
     * Insert record asynchronously to avoid blocking MediaProvider thread.
     * Records are batched and written in background thread.
     */
    fun insertRecordAsync(record: MediaProviderRecord) {
        recordQueue.offer(record)
        scheduleFlush()
    }
    
    /**
     * Insert multiple records asynchronously.
     */
    fun insertRecordsAsync(records: List<MediaProviderRecord>) {
        records.forEach { recordQueue.offer(it) }
        scheduleFlush()
    }
    
    private fun scheduleFlush() {
        if (hasPendingWrite.compareAndSet(false, true)) {
            writeHandler?.sendEmptyMessageDelayed(MSG_WRITE_RECORDS, WRITE_DELAY_MS)
        }
    }
    
    private fun flushRecordQueue() {
        hasPendingWrite.set(false)
        val batch = mutableListOf<MediaProviderRecord>()
        while (batch.size < MAX_BATCH_SIZE) {
            val record = recordQueue.poll() ?: break
            batch.add(record)
        }
        
        if (batch.isNotEmpty()) {
            try {
                if (batch.size == 1) {
                    dao.insert(batch[0])
                } else {
                    dao.insertAll(batch)
                }
            } catch (e: Exception) {
                // Log and continue, don't crash the system process
            }
        }
        
        // If there are more records, schedule another flush
        if (recordQueue.isNotEmpty()) {
            scheduleFlush()
        }
        
        // Dispatch media change after write
        if (batch.isNotEmpty()) {
            dispatchMediaChange()
        }
    }
    
    /**
     * Synchronously flush all remaining records in the queue.
     * Used during shutdown to ensure no records are lost.
     */
    private fun flushRecordQueueSync() {
        var totalFlushed = 0
        while (recordQueue.isNotEmpty()) {
            val batch = mutableListOf<MediaProviderRecord>()
            while (batch.size < MAX_BATCH_SIZE) {
                val record = recordQueue.poll() ?: break
                batch.add(record)
            }
            
            if (batch.isNotEmpty()) {
                try {
                    if (batch.size == 1) {
                        dao.insert(batch[0])
                    } else {
                        dao.insertAll(batch)
                    }
                    totalFlushed += batch.size
                } catch (e: Exception) {
                    // Log and continue, don't crash the system process
                }
            } else {
                break
            }
        }
    }

    private val packageManagerService: IInterface by lazy {
        val binder = XposedHelpers.callStaticMethod(
            XposedHelpers.findClass("android.os.ServiceManager", classLoader),
            "getService", "package"
        ) as IBinder
        XposedHelpers.callStaticMethod(
            XposedHelpers.findClass(
                "android.content.pm.IPackageManager\$Stub", classLoader
            ), "asInterface", binder
        ) as IInterface
    }

    override fun getModuleVersion() = BuildConfig.VERSION_CODE

    override fun getInstalledPackages(userId: Int, flags: Int): ParceledListSlice<PackageInfo> {
        enforceCallerPermission()
        val parceledListSlice = XposedHelpers.callMethod(
            packageManagerService,
            "getInstalledPackages",
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) flags.toLong() else flags,
            userId
        )
        val list = (XposedHelpers.callMethod(parceledListSlice, "getList") as? List<*>)
            ?.filterIsInstance<PackageInfo>()
            .orEmpty()
        return ParceledListSlice(list)
    }

    override fun getPackageInfo(packageName: String, flags: Int, userId: Int): PackageInfo? {
        enforceCallerPermission()
        return XposedHelpers.callMethod(
            packageManagerService,
            "getPackageInfo",
            packageName,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) flags.toLong() else flags,
            userId
        ) as? PackageInfo
    }

    override fun readSp(who: Int): String? {
        enforceCallerPermission()
        return when (who) {
            SpIdentifiers.ROOT_PREFERENCES -> rootSp.read()
            SpIdentifiers.TEMPLATE_PREFERENCES -> {
                val local = ruleSp.read()
                val remote = remoteSp?.read()
                if (remote.isNullOrBlank()) local else mergeTemplates(local, remote)
            }
            SpIdentifiers.REMOTE_PREFERENCES -> remoteSp?.read()
            else -> null
        }
    }

    override fun writeSp(who: Int, what: String) {
        enforceCallerPermission()
        when (who) {
            SpIdentifiers.ROOT_PREFERENCES -> rootSp.write(what)
            SpIdentifiers.TEMPLATE_PREFERENCES -> {
                // 过滤：只将本地模板写入 rule 文件，远程模板保留在 rule_remote
                val remoteJson = remoteSp?.read()
                val filtered = if (!remoteJson.isNullOrBlank()) {
                    val localBefore = ruleSp.read()
                    val remoteNames = try {
                        val arr = JSONArray(remoteJson)
                        (0 until arr.length()).map { arr.getJSONObject(it).optString("template_name") }
                            .filter { it.isNotEmpty() }.toSet()
                    } catch (_: Exception) { emptySet<String>() }
                    val localNamesBefore = try {
                        val arr = JSONArray(localBefore ?: "[]")
                        (0 until arr.length()).map { arr.getJSONObject(it).optString("template_name") }
                            .filter { it.isNotEmpty() }.toSet()
                    } catch (_: Exception) { emptySet<String>() }
                    val remoteOnly = remoteNames - localNamesBefore
                    if (remoteOnly.isNotEmpty()) {
                        try {
                            val incoming = JSONArray(what)
                            val filtered = JSONArray()
                            for (i in 0 until incoming.length()) {
                                val obj = incoming.getJSONObject(i)
                                if (obj.optString("template_name") !in remoteOnly) {
                                    filtered.put(obj)
                                }
                            }
                            filtered.toString()
                        } catch (_: Exception) { what }
                    } else what
                } else what
                ruleSp.write(filtered)
                // 本地写入后重建 ruleSp.templates，保留远程合并
                ruleSp.templates = Templates(ruleSp.read(),
                    remoteValues = if (configSubscriptionManager?.isSubscribed == true)
                        ConfigSubscriptionManager.cachedTemplates
                    else
                        remoteConfigFetcher?.cachedTemplates
                            ?: ConfigSubscriptionManager.cachedTemplates)
            }
            SpIdentifiers.REMOTE_PREFERENCES -> {
                // 远程配置只读，静默忽略写入
                me.gm.cleaner.plugin.util.L.d("ManagerService", "writeSp(REMOTE) ignored — read-only")
            }
        }
    }

    // ----- 远程配置接口 -----

    override fun readRemoteSp(): String? {
        enforceCallerPermission()
        return remoteSp?.read()
    }

    override fun writeRemoteSp(what: String) {
        enforceCallerPermission()
        // 远程配置只读，静默忽略
        me.gm.cleaner.plugin.util.L.d("ManagerService", "writeRemoteSp ignored — read-only")
    }

    override fun triggerRemotePull(): Boolean {
        enforceCallerPermission()
        RemoteConfigLogBuffer.log("=== triggerRemotePull called from client ===")
        val fetcher = remoteConfigFetcher
        if (fetcher == null) {
            RemoteConfigLogBuffer.log("ERROR: remoteConfigFetcher not initialized")
            return false
        }

        val subscribed = configSubscriptionManager?.isSubscribed == true
        if (subscribed) {
            RemoteConfigLogBuffer.log("Subscribed mode: will re-subscribe after pull")
            configSubscriptionManager?.stop()
        }

        RemoteConfigLogBuffer.log("Cached before pull: ${fetcher.cachedTemplates.size} templates, " +
                "lastPull=${fetcher.lastPullTimestamp}, error=${fetcher.lastError}")
        val success = fetcher.pull()
        if (success) {
            // 拉取后使 ruleSp 的 Templates 缓存失效，下次查询重新 merge
            RemoteConfigLogBuffer.log("Pull succeeded, clearing Template cache for re-merge")
            ruleSp.templates.clearCache()
            remoteSp?.invalidateCache()
            ruleSp.templates = Templates(ruleSp.read(),
                remoteValues = fetcher.cachedTemplates)
            RemoteConfigLogBuffer.log("Merged config will include ${fetcher.cachedTemplates.size} remote templates")
        } else {
            RemoteConfigLogBuffer.log("Pull failed, retry scheduled automatically")
        }

        // 订阅模式：拉取后重新订阅，刷新推送通道
        if (subscribed) {
            RemoteConfigLogBuffer.log("Re-subscribing after manual pull")
            configSubscriptionManager?.start()
        }

        RemoteConfigLogBuffer.log("=== triggerRemotePull end → $success ===")
        return success
    }

    override fun getRemoteConfigStatus(): String? {
        enforceCallerPermission()
        val fetcher = remoteConfigFetcher ?: return """{"lastPull":0,"error":"not initialized","templateCount":0}"""
        val subscribed = configSubscriptionManager?.isSubscribed == true
        val status = JSONObject().apply {
            put("lastPull", if (subscribed) ConfigSubscriptionManager.lastPullTimestamp
                           else fetcher.lastPullTimestamp)
            put("error", if (subscribed) ConfigSubscriptionManager.lastError
                         else fetcher.lastError ?: JSONObject.NULL)
            put("templateCount", if (subscribed) ConfigSubscriptionManager.cachedTemplates.size
                                 else fetcher.cachedTemplates.size)
            put("logCount", RemoteConfigLogBuffer.size())
            put("isRetrying", fetcher.isRetrying)
            put("isSubscribed", subscribed)
        }
        return status.toString()
    }

    override fun getRemoteConfigLogs(): String? {
        enforceCallerPermission()
        return RemoteConfigLogBuffer.toJson(100)
    }

    override fun clearAllTables() {
        enforceCallerPermission()
        database.clearAllTables()
    }

    override fun packageUsageTimes(operation: Int, packageNames: List<String>): Int {
        enforceCallerPermission()
        return dao.packageUsageTimes(operation, packageNames.toTypedArray())
    }

    override fun registerMediaChangeObserver(observer: IMediaChangeObserver) {
        enforceCallerPermission()
        observers.register(observer)
    }

    override fun unregisterMediaChangeObserver(observer: IMediaChangeObserver) {
        enforceCallerPermission()
        observers.unregister(observer)
    }

    private var lastDispatchTime = 0L
    private var dispatchScheduled = false

    /**
     * Dispatch media change with debouncing to avoid excessive notifications.
     * Multiple calls within 500ms will be coalesced into a single notification.
     * Uses a scheduled approach to batch multiple rapid changes.
     */
    @Synchronized
    fun dispatchMediaChange() {
        val now = SystemClock.uptimeMillis()
        
        // If we're within the debounce window, schedule a delayed dispatch
        if (now - lastDispatchTime < DEBOUNCE_INTERVAL_MS) {
            if (!dispatchScheduled) {
                dispatchScheduled = true
                writeHandler?.postDelayed({
                    dispatchMediaChangeInternal()
                }, DEBOUNCE_INTERVAL_MS - (now - lastDispatchTime))
            }
            return
        }
        
        // Otherwise, dispatch immediately
        dispatchMediaChangeInternal()
    }
    
    private fun dispatchMediaChangeInternal() {
        val now = SystemClock.uptimeMillis()
        lastDispatchTime = now
        dispatchScheduled = false
        
        var i = observers.beginBroadcast()
        while (i > 0) {
            i--
            val observer = observers.getBroadcastItem(i)
            if (observer != null) {
                try {
                    observer.onChange()
                } catch (ignored: RemoteException) {
                }
            }
        }
        observers.finishBroadcast()
    }

    companion object {
        const val MEDIA_PROVIDER_USAGE_RECORD_DATABASE_NAME = "media_provider.db"

        private const val MSG_WRITE_RECORDS = 1
        private const val WRITE_DELAY_MS = 100L // Batch writes within 100ms
        private const val MAX_BATCH_SIZE = 50
        private const val DEBOUNCE_INTERVAL_MS = 500L // Debounce interval for media change notifications
    }
}
