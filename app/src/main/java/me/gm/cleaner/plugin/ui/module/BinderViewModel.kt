package me.gm.cleaner.plugin.ui.module

import android.content.pm.PackageInfo
import android.os.IBinder
import android.os.Process
import android.util.SparseArray
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import me.gm.cleaner.plugin.IManagerService
import me.gm.cleaner.plugin.IMediaChangeObserver
import javax.inject.Inject

@HiltViewModel
class BinderViewModel @Inject constructor(private val binder: IBinder?) : ViewModel() {
    private var service: IManagerService? = IManagerService.Stub.asInterface(binder)
    private val _remoteSpCacheLiveData = MutableLiveData(SparseArray<String>())
    val remoteSpCacheLiveData: LiveData<SparseArray<String>>
        get() = _remoteSpCacheLiveData
    val remoteSpCache: SparseArray<String>
        get() = _remoteSpCacheLiveData.value!!

    fun notifyRemoteSpChanged() {
        _remoteSpCacheLiveData.postValue(remoteSpCache)
    }

    fun pingBinder() = binder?.pingBinder() == true

    val moduleVersion: Int
        get() = service!!.moduleVersion

    fun getInstalledPackages(flags: Int): List<PackageInfo> =
        service!!.getInstalledPackages(Process.myUid() / AID_USER_OFFSET, flags).list

    fun getPackageInfo(packageName: String): PackageInfo? =
        service!!.getPackageInfo(packageName, 0, Process.myUid() / AID_USER_OFFSET)

    fun readSp(who: Int): String? =
        remoteSpCache[who, service!!.readSp(who).also { remoteSpCache.put(who, it) }]

    fun writeSp(who: Int, what: String) {
        if (remoteSpCache[who] != what) {
            service!!.writeSp(who, what)
            remoteSpCache.put(who, what)
            notifyRemoteSpChanged()
        }
    }

    fun clearAllTables() {
        service!!.clearAllTables()
    }

    fun packageUsageTimes(operation: Int, packageNames: List<String>): Int = 0

    fun registerMediaChangeObserver(observer: IMediaChangeObserver) {
        service!!.registerMediaChangeObserver(observer)
    }

    fun unregisterMediaChangeObserver(observer: IMediaChangeObserver) {
        service!!.unregisterMediaChangeObserver(observer)
    }

    companion object {
        const val AID_USER_OFFSET = 100000
    }
}