package com.sjcyz.hmta.utils

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.sjcyz.hmta.BuildConfig
import com.sjcyz.hmta.IMacAddressService
import com.sjcyz.hmta.services.MacAddressService
import rikka.shizuku.Shizuku
import java.net.NetworkInterface
import kotlin.collections.iterator

object ShizukuUtils {
    private val binderLock = Object()
    private var macService: IMacAddressService? = null
    private var serviceDeferred = CompletableDeferred<IMacAddressService?>()

    init {
        Shizuku.addBinderReceivedListenerSticky {
            bindService()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder?) {
            if (service != null && service.pingBinder()) {
                Log.d(ShizukuUtils.TAG, "Got service connection for $name")

                synchronized(binderLock) {
                    macService = IMacAddressService.Stub.asInterface(service)
                    serviceDeferred.complete(macService)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.d(ShizukuUtils.TAG, "Connection lost for $name")
            synchronized(binderLock) {
                macService = null
                serviceDeferred = CompletableDeferred()
            }
        }
    }

    fun unsafeBindService() {
        val cn = ComponentName(
            BuildConfig.APPLICATION_ID, MacAddressService::class.java.name
        )
        val args = Shizuku.UserServiceArgs(cn)
            .daemon(false)
            .processNameSuffix("service")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
        Shizuku.bindUserService(args, serviceConnection)
    }

    fun bindService() {
        try {
            unsafeBindService()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to bind service", e)
        }
    }

    fun getMacAddress(context: Context, name: String, l: (String?) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val res = try {
                suspendGetMacAddress(context, name)
            } catch (e: Throwable) {
                Log.e(ShizukuUtils.TAG, "Failed to obtain MAC address for $name", e)
                null
            }

            l(res)
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun nativeGetMacAddressByName(name: String): String? {
        val ifs = NetworkInterface.getNetworkInterfaces()
        for (intf in ifs) {
            if (intf.name == name) {
                return intf.hardwareAddress?.toHexString(HexFormat {
                    bytes.byteSeparator = ":"
                })
            }
        }
        return null
    }

    suspend fun getMacAddress(context: Context, name: String): String? {
        return suspendGetMacAddress(context, name)
    }

    private suspend fun suspendGetMacAddress(context: Context, name: String): String? {
        if (context.checkSelfPermission("android.permission.LOCAL_MAC_ADDRESS") == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Permission granted, using native method")
            return nativeGetMacAddressByName(name)
        }

        synchronized(binderLock) {
            macService?.let { return it.getMacAddressByName(name) }
        }

        try {
            unsafeBindService()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to bind service", e)
            return null
        }

        val deferred = serviceDeferred
        val svc = withTimeoutOrNull(20_000) {
            deferred.await()
        }
        return svc?.getMacAddressByName(name)
    }
}
