package me.gm.cleaner.plugin.xposed

import android.content.Context
import android.content.pm.PackageInfo
import android.content.res.Resources
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.*
import androidx.room.Room
import com.google.gson.Gson
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
import java.io.File
import java.io.IOException
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
    val rootSp by lazy { JsonFileSpImpl(File(context.filesDir, "root")) }
    val ruleSp by lazy { TemplatesJsonFileSpImpl(File(context.filesDir, "rule")) }
    
    private var socketServer: UsageRecordSocketServer? = null

    protected fun onCreate(context: Context) {
        this.context = context
        database = Room
            .databaseBuilder(
                context,
                MediaProviderRecordDatabase::class.java,
                MEDIA_PROVIDER_USAGE_RECORD_DATABASE_NAME
            )
            .addMigrations(MIGRATION_1_2)
            .build()
        dao = database.mediaProviderRecordDao()

        socketServer = UsageRecordSocketServer()
        socketServer?.start()
    }

    fun recordUsage(record: MediaProviderRecord) {
        dao.insert(record)
        socketServer?.broadcast(record)
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
        database.clearAllTables()
    }

    override fun packageUsageTimes(operation: Int, packageNames: List<String>) =
        dao.packageUsageTimes(operation, *packageNames.toTypedArray())

    override fun registerMediaChangeObserver(observer: IMediaChangeObserver) {
        observers.register(observer)
    }

    override fun unregisterMediaChangeObserver(observer: IMediaChangeObserver) {
        observers.unregister(observer)
    }

    @Synchronized
    fun dispatchMediaChange() {
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

    class UsageRecordSocketServer {
        private val clients = CopyOnWriteArrayList<LocalSocket>()
        private val gson = Gson()
        private var isRunning = false

        fun start() {
            if (isRunning) return
            isRunning = true
            thread(name = "UsageRecordSocketServer") {
                try {
                    val serverSocket = LocalServerSocket("me.gm.cleaner.usage_record")
                    while (isRunning) {
                        val client = serverSocket.accept()
                        clients.add(client)
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }

        fun broadcast(record: MediaProviderRecord) {
            if (clients.isEmpty()) return
            val recordMap = mapOf(
                "timeMillis" to record.timeMillis,
                "packageName" to record.packageName,
                "match" to record.match,
                "operation" to record.operation,
                "data" to record.data,
                "mimeType" to record.mimeType,
                "intercepted" to record.intercepted
            )
            val json = gson.toJson(recordMap) + "\n"
            val bytes = json.toByteArray()
            for (client in clients) {
                try {
                    client.outputStream.write(bytes)
                    client.outputStream.flush()
                } catch (e: IOException) {
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