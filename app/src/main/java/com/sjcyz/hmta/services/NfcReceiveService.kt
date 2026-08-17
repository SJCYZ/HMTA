package com.sjcyz.hmta.services

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sjcyz.hmta.MainActivity
import com.sjcyz.hmta.AppSettings
import com.sjcyz.hmta.MyApplication
import com.sjcyz.hmta.R
import com.sjcyz.hmta.nfc.NfcTransferCoordinator
import com.sjcyz.hmta.nfc.NfcTransferPhase
import com.sjcyz.hmta.nfc.OppoNfcInvitation
import com.sjcyz.hmta.nfc.PrivilegedWifiController
import com.sjcyz.hmta.nfc.storage.MediaStoreIncomingArchiveSink
import com.sjcyz.hmta.nfc.transport.IosGattClient
import com.sjcyz.hmta.nfc.transport.OshareWsClient
import com.sjcyz.hmta.utils.NotificationUtils
import com.sjcyz.hmta.utils.TAG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("MissingPermission")
class NfcReceiveService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val wifiController = PrivilegedWifiController()
    private val stopped = AtomicBoolean(false)
    private var ownsBusyFlag = false

    private var generation = 0L
    private var invitation: OppoNfcInvitation? = null
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var scanning = false
    private var iosGattClient: IosGattClient? = null
    private var wsClient: OshareWsClient? = null
    private var networkJob: Job? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning || !isActiveSession()) return
            val services = result.scanRecord?.serviceUuids?.map { it.uuid.toString() }.orEmpty()
            val target = services.any {
                it.startsWith("00006667-") || it.startsWith("00006669-") ||
                    it.startsWith("00003334-") || it.startsWith("00008181-")
            }
            if (!target) return
            stopScan()
            log("发现 OPPO iOS 协商广播：${result.device.address}")
            connectGatt(result.device.address)
        }

        override fun onScanFailed(errorCode: Int) {
            fail("BLE 扫描失败：$errorCode")
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification("等待 NFC 会话"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSession(intent)
            ACTION_ACCEPT -> iosGattClient?.sendAccept()
            ACTION_REJECT -> {
                iosGattClient?.sendReject()
                fail("已拒绝 OPPO 文件")
            }
            ACTION_CANCEL -> cancelSession("用户取消")
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSession(intent: Intent) {
        cleanupResources(restoreWifi = false)
        stopped.set(false)
        generation = intent.getLongExtra(EXTRA_GENERATION, 0)
        invitation = OppoNfcInvitation(
            deviceId = intent.getStringExtra(EXTRA_DEVICE_ID).orEmpty(),
            randomCode = intent.getStringExtra(EXTRA_RANDOM_CODE).orEmpty(),
            deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME),
            deviceType = intent.getIntExtra(EXTRA_DEVICE_TYPE, -1).takeIf { it >= 0 },
            version = intent.getIntExtra(EXTRA_VERSION, -1).takeIf { it >= 0 },
            rawUri = intent.getStringExtra(EXTRA_RAW_URI).orEmpty(),
        )
        if (generation <= 0 || invitation?.randomCode?.length != 16) {
            fail("NFC 会话参数无效")
            return
        }
        if (!MyApplication.getInstance().setBusy()) {
            fail("HMTA 正在执行其他传输")
            return
        }
        ownsBusyFlag = true
        updateState(NfcTransferPhase.BLE_NEGOTIATING, "扫描 OPPO 协商广播")
        startScan()
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        if (adapter == null || !adapter.isEnabled) {
            fail("蓝牙未开启")
            return
        }
        scanner = adapter.bluetoothLeScanner
        scanning = true
        runCatching {
            scanner?.startScan(
                null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                scanCallback,
            )
        }.onFailure { fail("无法启动 BLE 扫描：${it.message}") }
        mainHandler.postDelayed({
            if (scanning && isActiveSession()) fail("未发现 OPPO 协商广播")
        }, SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { scanner?.stopScan(scanCallback) }
        scanner = null
    }

    private fun connectGatt(mac: String) {
        val currentInvitation = invitation ?: return fail("缺少 NFC 邀请")
        updateState(NfcTransferPhase.BLE_NEGOTIATING, "已发现 OPPO，等待对端上滑确认")
        val client = IosGattClient(
            context = applicationContext,
            mac = mac,
            ndefCode = currentInvitation.randomCode,
            bandReadDelayMs = 500,
            onReady = {
                if (!isActiveSession()) return@IosGattClient
                updateState(NfcTransferPhase.BLE_NEGOTIATING, "请在 OPPO 端上滑确认发送")
                mainHandler.postDelayed({
                    if (isActiveSession()) iosGattClient?.readBand(500)
                }, BAND_WAIT_MS)
            },
            onLog =(::log),
            onReceiveConfirm = { sender -> showReceiveConfirmation(sender) },
            onWifiInfo = { ssid, psk, ip, port -> prepareNetwork(ssid, psk, ip, port) },
            onFinished = { success, reason -> if (!success) fail(reason) },
        )
        iosGattClient = client
        client.connect()
    }

    private fun showReceiveConfirmation(sender: String) {
        if (!isActiveSession()) return
        if (AppSettings(this).autoAccept) {
            log("已启用自动接收，接受来自 $sender 的 NFC 传输")
            iosGattClient?.sendAccept()
            return
        }
        val notification = buildNotification(
            text = "$sender 请求发送文件",
            includeDecisionActions = true,
        )
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
    }

    private fun prepareNetwork(ssid: String, psk: String, host: String, port: Int) {
        if (!isActiveSession()) return
        networkJob?.cancel()
        networkJob = serviceScope.launch {
            updateState(NfcTransferPhase.PREPARING_NETWORK, "正在加入 OPPO 热点")
            val result = withContext(Dispatchers.IO) { wifiController.connect(ssid, psk) }
            if (!result.success) {
                fail("连接 OPPO 热点失败：${result.output}")
                return@launch
            }
            val networkInfo = withContext(Dispatchers.IO) { waitForWifi(ssid) }
            if (networkInfo == null) {
                fail("连接 OPPO 热点或 DHCP 超时")
                return@launch
            }
            log("热点已连接，本机 IP=${networkInfo.localIp}")
            iosGattClient?.sendIpPort(networkInfo.localIp, 8959)
            delay(1_500)
            startWebSocket(host, port, networkInfo.network)
        }
    }

    private fun startWebSocket(host: String, port: Int, network: Network) {
        if (!isActiveSession()) return
        updateState(NfcTransferPhase.TRANSFERRING, "正在连接 OPPO 文件服务")
        val client = OshareWsClient(
            host = host,
            port = port,
            network = network,
            onLog =(::log),
            onProgress = { done, total ->
                updateState(NfcTransferPhase.TRANSFERRING, "正在接收文件", done, total)
            },
            onMessage = { _, _, _, _ -> },
            archiveSink = MediaStoreIncomingArchiveSink(this),
            onCancelled = { fail(it) },
            onFinished = { success, reason ->
                if (success) complete(reason) else fail(reason)
            },
        )
        wsClient = client
        Thread {
            if (!client.connect()) {
                fail("无法连接 OPPO WebSocket 文件服务")
                return@Thread
            }
            client.readLoop()
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun waitForWifi(targetSsid: String): WifiConnection? {
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        repeat(30) {
            if (!isActiveSession()) return null
            val currentSsid = runCatching { wifi.connectionInfo?.ssid?.trim('"') }.getOrNull()
            val ip = runCatching { wifi.connectionInfo?.ipAddress ?: 0 }.getOrDefault(0)
            val network = resolveWifiNetwork()
            if (!currentSsid.isNullOrBlank() &&
                (currentSsid == targetSsid || currentSsid.contains(targetSsid)) &&
                ip != 0 && network != null
            ) {
                return WifiConnection(network, intToIp(ip))
            }
            Thread.sleep(1_000)
        }
        return null
    }

    private fun resolveWifiNetwork(): Network? {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        return connectivity.allNetworks.firstOrNull { network ->
            connectivity.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    private fun complete(reason: String) {
        if (!stopped.compareAndSet(false, true)) return
        updateState(NfcTransferPhase.COMPLETED, reason)
        updateNotification("接收完成")
        mainHandler.postDelayed({ finishSession(restoreWifi = true) }, 1_500)
    }

    private fun fail(reason: String) {
        if (!stopped.compareAndSet(false, true)) return
        updateState(NfcTransferPhase.FAILED, reason)
        log("失败：$reason")
        updateNotification(reason)
        mainHandler.postDelayed({ finishSession(restoreWifi = true) }, 1_500)
    }

    private fun cancelSession(reason: String) {
        if (!stopped.compareAndSet(false, true)) return
        updateState(NfcTransferPhase.CANCELLING, reason)
        wsClient?.cancel()
        iosGattClient?.sendReject()
        finishSession(restoreWifi = true)
    }

    private fun finishSession(restoreWifi: Boolean) {
        updateState(NfcTransferPhase.CLEANING_UP, "正在清理连接")
        cleanupResources(restoreWifi)
        NfcTransferCoordinator.reset(generation)
        if (ownsBusyFlag) {
            MyApplication.getInstance().clearBusy()
            ownsBusyFlag = false
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanupResources(restoreWifi: Boolean) {
        mainHandler.removeCallbacksAndMessages(null)
        stopScan()
        networkJob?.cancel()
        networkJob = null
        wsClient?.close()
        wsClient = null
        iosGattClient?.disconnect()
        iosGattClient = null
        if (restoreWifi) {
            serviceScope.launch(NonCancellable + Dispatchers.IO) { wifiController.disconnect() }
        }
    }

    private fun updateState(
        phase: NfcTransferPhase,
        message: String,
        done: Long = NfcTransferCoordinator.state.value.transferredBytes,
        total: Long = NfcTransferCoordinator.state.value.totalBytes,
    ) {
        if (!isCurrentSession()) return
        NfcTransferCoordinator.transition(generation, phase, message, done, total)
        updateNotification(
            if (total > 0) "$message ${done * 100 / total}%" else message,
            done,
            total,
        )
    }

    private fun updateNotification(text: String, done: Long = 0, total: Long = 0) {
        NotificationManagerCompat.from(this).notify(
            NOTIFICATION_ID,
            buildNotification(text, done = done, total = total),
        )
    }

    private fun buildNotification(
        text: String,
        includeDecisionActions: Boolean = false,
        done: Long = 0,
        total: Long = 0,
    ) = NotificationCompat.Builder(this, NotificationUtils.RECEIVER_CHAN_ID)
        .setSmallIcon(R.drawable.ic_downloading)
        .setContentTitle("NFC 一碰传")
        .setContentText(text)
        .setOngoing(!stopped.get())
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .apply {
            if (done > 0 && total > 0) {
                setProgress(100, (done * 100 / total).toInt().coerceIn(0, 100), false)
            }
            if (includeDecisionActions) {
                addAction(0, "接收", serviceAction(ACTION_ACCEPT, 1))
                addAction(0, "拒绝", serviceAction(ACTION_REJECT, 2))
            } else if (!stopped.get()) {
                addAction(0, "取消", serviceAction(ACTION_CANCEL, 3))
            }
        }
        .build()

    private fun serviceAction(action: String, requestCode: Int) = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, NfcReceiveService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun isCurrentSession(): Boolean =
        generation > 0 && NfcTransferCoordinator.isCurrent(generation)

    private fun isActiveSession(): Boolean = isCurrentSession() && !stopped.get()

    private fun log(message: String) {
        Log.i(TAG, "[NFC Receive][$generation] $message")
    }

    override fun onDestroy() {
        cleanupResources(restoreWifi = false)
        serviceScope.cancel()
        super.onDestroy()
    }

    private data class WifiConnection(val network: Network, val localIp: String)

    companion object {
        private const val NOTIFICATION_ID = 4
        private const val SCAN_TIMEOUT_MS = 8_000L
        private const val BAND_WAIT_MS = 3_000L

        private const val ACTION_START = "com.sjcyz.hmta.nfc.RECEIVE_START"
        private const val ACTION_ACCEPT = "com.sjcyz.hmta.nfc.RECEIVE_ACCEPT"
        private const val ACTION_REJECT = "com.sjcyz.hmta.nfc.RECEIVE_REJECT"
        private const val ACTION_CANCEL = "com.sjcyz.hmta.nfc.RECEIVE_CANCEL"
        private const val EXTRA_GENERATION = "generation"
        private const val EXTRA_DEVICE_ID = "deviceId"
        private const val EXTRA_RANDOM_CODE = "randomCode"
        private const val EXTRA_DEVICE_NAME = "deviceName"
        private const val EXTRA_DEVICE_TYPE = "deviceType"
        private const val EXTRA_VERSION = "version"
        private const val EXTRA_RAW_URI = "rawUri"

        fun start(context: Context, invitation: OppoNfcInvitation, generation: Long) {
            val intent = Intent(context, NfcReceiveService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_GENERATION, generation)
                .putExtra(EXTRA_DEVICE_ID, invitation.deviceId)
                .putExtra(EXTRA_RANDOM_CODE, invitation.randomCode)
                .putExtra(EXTRA_DEVICE_NAME, invitation.deviceName)
                .putExtra(EXTRA_DEVICE_TYPE, invitation.deviceType ?: -1)
                .putExtra(EXTRA_VERSION, invitation.version ?: -1)
                .putExtra(EXTRA_RAW_URI, invitation.rawUri)
            ContextCompat.startForegroundService(context, intent)
        }

        private fun intToIp(value: Int): String =
            "${value and 0xFF}.${value shr 8 and 0xFF}.${value shr 16 and 0xFF}.${value shr 24 and 0xFF}"
    }
}
