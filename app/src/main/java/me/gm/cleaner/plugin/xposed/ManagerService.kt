package me.gm.cleaner.plugin.xposed

import android.content.Context
import android.content.pm.PackageInfo
import android.content.res.Resources
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.*
import androidx.room.Room
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import me.gm.cleaner.plugin.BuildConfig
import me.gm.cleaner.plugin.IManagerService
import me.gm.cleaner.plugin.IMediaChangeObserver
import me.gm.cleaner.plugin.R
import me.gm.cleaner.plugin.dao.MIGRATION_1_2
import me.gm.cleaner.plugin.dao.MediaProviderRecordDao
import me.gm.cleaner.plugin.dao.MediaProviderRecordDatabase
import me.gm.cleaner.plugin.dao.MediaProviderRecord
import me.gm.cleaner.plugin.model.ParceledListSlice
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

abstract class ManagerService : IManagerService.Stub() {
    lateinit var classLoader: ClassLoader
        protected set
    lateinit var resources: Resources
        protected set
    lateinit var context: Context
        private set

    // 重新公开属性，确保 Hooker 能够访问
    val rootSp: JsonFileSpImpl
        get() = sRootSp ?: JsonFileSpImpl(File("/dev/null"))

    val ruleSp: TemplatesJsonFileSpImpl
        get() = sRuleSp ?: TemplatesJsonFileSpImpl(File("/dev/null"))

    val dao: MediaProviderRecordDao?
        get() = sDao

    protected fun onCreate(context: Context) {
        this.context = context
        initGlobalServices(context, this)
    }

    fun recordUsage(record: MediaProviderRecord) {
        try {
            sDao?.insert(record)
        } catch (ignored: Throwable) {}
        
        try {
            sUsageRecordSocketServer?.broadcast(record)
        } catch (ignored: Throwable) {}
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
        val parceledListSlice = XposedHelpers.callMethod(
            packageManagerService,
            "getInstalledPackages",
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) flags.toLong() else flags,
            userId
        )
        val list = XposedHelpers.callMethod(parceledListSlice, "getList") as List<PackageInfo>
        return ParceledListSlice(list)
    }

    override fun getPackageInfo(packageName: String, flags: Int, userId: Int) =
        XposedHelpers.callMethod(
            packageManagerService,
            "getPackageInfo",
            packageName,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) flags.toLong() else flags,
            userId
        ) as? PackageInfo

    override fun readSp(who: Int): String? = when (who) {
        R.xml.root_preferences -> rootSp.read()
        R.xml.template_preferences -> ruleSp.read()
        else -> throw IllegalArgumentException()
    }

    override fun writeSp(who: Int, what: String) {
        when (who) {
            R.xml.root_preferences -> rootSp.write(what)
            R.xml.template_preferences -> ruleSp.write(what)
        }
    }

    override fun clearAllTables() {
        try {
            sDatabase?.clearAllTables()
        } catch (ignored: Throwable) {}
    }

    override fun packageUsageTimes(operation: Int, packageNames: List<String>) =
        sDao?.packageUsageTimes(operation, *packageNames.toTypedArray()) ?: 0

    override fun registerMediaChangeObserver(observer: IMediaChangeObserver) {
        sObservers.register(observer)
    }

    override fun unregisterMediaChangeObserver(observer: IMediaChangeObserver) {
        sObservers.unregister(observer)
    }

    @Synchronized
    fun dispatchMediaChange() {
        try {
            var i = sObservers.beginBroadcast()
            while (i > 0) {
                i--
                val observer = sObservers.getBroadcastItem(i)
                if (observer != null) {
                    try {
                        observer.onChange()
                    } catch (ignored: RemoteException) {
                    }
                }
            }
            sObservers.finishBroadcast()
        } catch (e: Throwable) {
            XposedBridge.log("MPM_Observer: dispatchMediaChange failed: ${e.message}")
        }
    }

    companion object {
        const val MEDIA_PROVIDER_USAGE_RECORD_DATABASE_NAME = "media_provider.db"

        @Volatile private var sIsInitialized = false
        @Volatile private var sDatabase: MediaProviderRecordDatabase? = null
        @Volatile private var sDao: MediaProviderRecordDao? = null
        @Volatile private var sRootSp: JsonFileSpImpl? = null
        @Volatile private var sRuleSp: TemplatesJsonFileSpImpl? = null
        @Volatile private var sUsageRecordSocketServer: UsageRecordSocketServer? = null
        @Volatile private var sCommandSocketServer: CommandSocketServer? = null
        private val sObservers = RemoteCallbackList<IMediaChangeObserver>()

        @Synchronized
        fun initGlobalServices(context: Context, service: ManagerService) {
            if (sIsInitialized) return
            sIsInitialized = true
            
            val safeCtx = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !context.isDeviceProtectedStorage) {
                    context.createDeviceProtectedStorageContext()
                } else context
            } catch (e: Throwable) {
                context
            }

            sRootSp = try {
                JsonFileSpImpl(File(safeCtx.filesDir, "root"))
            } catch (e: Throwable) {
                JsonFileSpImpl(File("/dev/null"))
            }

            sRuleSp = try {
                TemplatesJsonFileSpImpl(File(safeCtx.filesDir, "rule"))
            } catch (e: Throwable) {
                TemplatesJsonFileSpImpl(File("/dev/null"))
            }

            try {
                sDatabase = Room
                    .databaseBuilder(
                        safeCtx,
                        MediaProviderRecordDatabase::class.java,
                        MEDIA_PROVIDER_USAGE_RECORD_DATABASE_NAME
                    )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                sDao = sDatabase?.mediaProviderRecordDao()
            } catch (e: Throwable) {
                XposedBridge.log("MPM_DB: Room init failed: ${e.message}")
            }

            sUsageRecordSocketServer = UsageRecordSocketServer(context.packageName)
            sUsageRecordSocketServer?.start()

            sCommandSocketServer = CommandSocketServer(context.packageName, service)
            sCommandSocketServer?.start()
        }
    }

    class CommandSocketServer(private val pkg: String, private val service: ManagerService) {
        private var isRunning = false
        fun start() {
            if (isRunning) return
            isRunning = true
            thread(name = "CommandServer-$pkg") {
                try {
                    val socketName = "me.gm.cleaner.command.$pkg"
                    val serverSocket = LocalServerSocket(socketName)
                    while (isRunning) {
                        val client = serverSocket.accept()
                        thread { handleClient(client) }
                    }
                } catch (e: Throwable) {
                    isRunning = false
                }
            }
        }

        private fun handleClient(client: LocalSocket) {
            try {
                // 使用 lineReader 以便快速响应单行指令，避免 readText() 导致的死锁
                val reader = client.inputStream.bufferedReader()
                val writer = client.outputStream.bufferedWriter()
                
                val input = reader.readLine()?.trim() // 修改此处
                if (!input.isNullOrEmpty()) {
                    XposedBridge.log("MPM_Socket: Received command [$input] in ${context.packageName}")
                    when {
                        input == "RELOAD_RULES" -> {
                            val success = sRuleSp?.reload() ?: false
                            writer.write(if (success) "SUCCESS\n" else "FAILED\n")
                        }
                        input == "RELOAD_ROOT" -> {
                            val success = sRootSp?.reload() ?: false
                            writer.write(if (success) "SUCCESS\n" else "FAILED\n")
                        }
                        input.startsWith("[") -> {
                            service.writeSp(R.xml.template_preferences, input)
                            writer.write("SUCCESS\n")
                        }
                        input.startsWith("{") -> {
                            service.writeSp(R.xml.root_preferences, input)
                            writer.write("SUCCESS\n")
                        }
                        else -> writer.write("UNKNOWN_COMMAND\n")
                    }
                    writer.flush()
                }
            } catch (e: Throwable) {
                XposedBridge.log("MPM_Socket: handleClient error: ${e.message}")
            } finally {
                try { client.close() } catch (ignored: Exception) {}
            }
        }
    }

    class UsageRecordSocketServer(private val pkg: String) {
        private val clients = CopyOnWriteArrayList<LocalSocket>()
        private var isRunning = false
        fun start() {
            if (isRunning) return
            isRunning = true
            thread(name = "UsageServer-$pkg") {
                try {
                    val socketName = "me.gm.cleaner.usage_record.$pkg"
                    val serverSocket = LocalServerSocket(socketName)
                    while (isRunning) {
                        val client = serverSocket.accept()
                        clients.add(client)
                    }
                } catch (e: Throwable) {
                    isRunning = false
                }
            }
        }

        fun broadcast(record: MediaProviderRecord) {
            if (clients.isEmpty()) return
            val json = JSONObject().apply {
                put("timeMillis", record.timeMillis)
                put("packageName", record.packageName)
                put("match", record.match)
                put("operation", record.operation)
                put("data", JSONArray(record.data))
                put("mimeType", JSONArray(record.mimeType))
                put("intercepted", JSONArray(record.intercepted))
            }.toString() + "\n"
            val bytes = json.toByteArray()
            clients.forEach { client ->
                try {
                    client.outputStream.write(bytes)
                    client.outputStream.flush()
                } catch (e: Exception) {
                    clients.remove(client)
                    try { client.close() } catch (ignored: Exception) {}
                }
            }
        }
    }
}