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
    private lateinit var database: MediaProviderRecordDatabase
    lateinit var dao: MediaProviderRecordDao
        private set
    private val observers = RemoteCallbackList<IMediaChangeObserver>()

    // 使用 Device Protected Storage，确保在 FBE 加密阶段也能读取规则文件。
    private val safeContext: Context by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !context.isDeviceProtectedStorage) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }
    }

    val rootSp by lazy {
        try {
            JsonFileSpImpl(File(safeContext.filesDir, "root"))
        } catch (e: Throwable) {
            XposedBridge.log("MPM_Init: Failed to init rootSp. Safe to ignore during Direct Boot: ${e.message}")
            JsonFileSpImpl(File("/dev/null"))
        }
    }

    val ruleSp by lazy {
        try {
            TemplatesJsonFileSpImpl(File(safeContext.filesDir, "rule"))
        } catch (e: Throwable) {
            XposedBridge.log("MPM_Init: Failed to init ruleSp. Safe to ignore during Direct Boot: ${e.message}")
            TemplatesJsonFileSpImpl(File("/dev/null"))
        }
    }
    
    private lateinit var usageRecordSocketServer: UsageRecordSocketServer
    private lateinit var commandSocketServer: CommandSocketServer
    
    // 关键修复：加入防重入锁。如果两个 Provider 在同一个进程中（如 MediaProvider 和 Downloads）
    // 该锁能防止数据库和 Socket 被二次实例化导致端口占用异常（Address already in use）
    private var isInitialized = false

    @Synchronized
    protected fun onCreate(context: Context) {
        if (isInitialized) return
        isInitialized = true
        
        this.context = context
        
        try {
            val dbContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !context.isDeviceProtectedStorage) {
                context.createDeviceProtectedStorageContext()
            } else {
                context
            }

            database = Room
                .databaseBuilder(
                    dbContext,
                    MediaProviderRecordDatabase::class.java,
                    MEDIA_PROVIDER_USAGE_RECORD_DATABASE_NAME
                )
                .addMigrations(MIGRATION_1_2)
                .build()
            dao = database.mediaProviderRecordDao()
        } catch (e: Throwable) {
            XposedBridge.log("MPM_DB: Failed to init Room database (often expected during Direct Boot): ${e.message}")
        }

        usageRecordSocketServer = UsageRecordSocketServer()
        usageRecordSocketServer.start()

        commandSocketServer = CommandSocketServer()
        commandSocketServer.start()
    }

    fun recordUsage(record: MediaProviderRecord) {
        try {
            if (::dao.isInitialized) {
                dao.insert(record)
            }
        } catch (e: Throwable) {
        }
        
        try {
            if (::usageRecordSocketServer.isInitialized) {
                usageRecordSocketServer.broadcast(record)
            }
        } catch (e: Throwable) {
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
        if (::database.isInitialized) {
            database.clearAllTables()
        }
    }

    override fun packageUsageTimes(operation: Int, packageNames: List<String>) =
        if (::dao.isInitialized) dao.packageUsageTimes(operation, *packageNames.toTypedArray()) else 0

    override fun registerMediaChangeObserver(observer: IMediaChangeObserver) {
        observers.register(observer)
    }

    override fun unregisterMediaChangeObserver(observer: IMediaChangeObserver) {
        observers.unregister(observer)
    }

    @Synchronized
    fun dispatchMediaChange() {
        try {
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
        } catch (e: Throwable) {
            XposedBridge.log("MPM_Observer: dispatchMediaChange failed: ${e.message}")
        }
    }

    inner class CommandSocketServer {
        private var isRunning = false

        fun start() {
            if (isRunning) return
            isRunning = true
            thread(name = "CommandSocketServer") {
                try {
                    val socketName = "me.gm.cleaner.command.${context.packageName}"
                    val serverSocket = LocalServerSocket(socketName)
                    XposedBridge.log("MPM_Socket: Command server listening on $socketName")
                    while (isRunning) {
                        val client = serverSocket.accept()
                        thread { handleClient(client) }
                    }
                } catch (e: Throwable) {
                    XposedBridge.log("MPM_Socket: CommandSocketServer failed on ${context.packageName}: ${e.message}")
                    isRunning = false
                }
            }
        }

        private fun handleClient(client: LocalSocket) {
            try {
                client.inputStream.bufferedReader().use { reader ->
                    client.outputStream.bufferedWriter().use { writer ->
                        val input = reader.readText().trim()
                        if (input.isNotEmpty()) {
                            when {
                                input == "RELOAD_RULES" -> {
                                    val success = ruleSp.reload()
                                    writer.write(if (success) "SUCCESS\n" else "FAILED\n")
                                }
                                input == "RELOAD_ROOT" -> {
                                    val success = rootSp.reload()
                                    writer.write(if (success) "SUCCESS\n" else "FAILED\n")
                                }
                                input.startsWith("[") -> {
                                    writeSp(R.xml.template_preferences, input)
                                    writer.write("SUCCESS\n")
                                }
                                input.startsWith("{") -> {
                                    writeSp(R.xml.root_preferences, input)
                                    writer.write("SUCCESS\n")
                                }
                                else -> {
                                    writer.write("UNKNOWN_COMMAND\n")
                                }
                            }
                            writer.flush()
                        }
                    }
                }
            } catch (e: Throwable) {
            } finally {
                try { client.close() } catch (ignored: Exception) {}
            }
        }
    }

    inner class UsageRecordSocketServer {
        private val clients = CopyOnWriteArrayList<LocalSocket>()
        private var isRunning = false

        fun start() {
            if (isRunning) return
            isRunning = true
            thread(name = "UsageRecordSocketServer") {
                try {
                    val socketName = "me.gm.cleaner.usage_record.${context.packageName}"
                    val serverSocket = LocalServerSocket(socketName)
                    XposedBridge.log("MPM_Socket: UsageRecord server listening on $socketName")
                    while (isRunning) {
                        val client = serverSocket.accept()
                        clients.add(client)
                    }
                } catch (e: Throwable) {
                    XposedBridge.log("MPM_Socket: UsageRecordSocketServer failed on ${context.packageName}: ${e.message}")
                    isRunning = false
                }
            }
        }

        fun broadcast(record: MediaProviderRecord) {
            if (clients.isEmpty()) return
            
            val jsonObject = JSONObject().apply {
                put("timeMillis", record.timeMillis)
                put("packageName", record.packageName)
                put("match", record.match)
                put("operation", record.operation)
                put("data", JSONArray(record.data))
                put("mimeType", JSONArray(record.mimeType))
                put("intercepted", JSONArray(record.intercepted))
            }
            val jsonStr = jsonObject.toString() + "\n"
            val bytes = jsonStr.toByteArray()
            
            val iterator = clients.iterator()
            for (client in iterator) {
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

    companion object {
        const val MEDIA_PROVIDER_USAGE_RECORD_DATABASE_NAME = "media_provider.db"
    }
}