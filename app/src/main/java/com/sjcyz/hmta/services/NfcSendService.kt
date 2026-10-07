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
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sjcyz.hmta.MyApplication
import com.sjcyz.hmta.R
import com.sjcyz.hmta.ShareActivity
import com.sjcyz.hmta.models.FileInfo
import com.sjcyz.hmta.nfc.NfcTransferCoordinator
import com.sjcyz.hmta.nfc.NfcTransferPhase
import com.sjcyz.hmta.nfc.transport.NfcP2pServer
import com.sjcyz.hmta.nfc.transport.OppoNdefHceService
import com.sjcyz.hmta.nfc.transport.OshareGattClient
import com.sjcyz.hmta.nfc.transport.SenderFile
import com.sjcyz.hmta.utils.NotificationUtils
import com.sjcyz.hmta.utils.ShizukuUtils
import com.sjcyz.hmta.utils.TAG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("MissingPermission")
class NfcSendService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val stopped = AtomicBoolean(false)
    private var ownsBusyFlag = false

    private var generation = 0L
    private var taskId = -1
    private var touchBaseline = 0
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var scanning = false
    private var scanRounds = 0
    private var p2pManager: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var groupFetchAttempts = 0
    private var gattClient: OshareGattClient? = null
    private var p2pServer: NfcP2pServer? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning || !isActiveSession()) return
            val services = result.scanRecord?.serviceUuids?.map { it.uuid.toString() }.orEmpty()
            val target = services.any {
                it.startsWith("00003331-") || it.startsWith("00008881-") ||
                    it.startsWith("00003333-") || it.startsWith("00003334-") ||
                    it.startsWith("00006667-") || it.startsWith("00006669-") ||
                    it.startsWith("00008181-") || it.startsWith("00008182-")
            }
            if (!target) return
            stopScan()
            log("发现 OPPO 接收广播：${result.device.address}")
            createP2pGroup(result.device.address)
        }

        override fun onScanFailed(errorCode: Int) {
            fail("BLE 扫描失败：$errorCode")
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification("正在准备 NFC 一碰传"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSession(intent)
            ACTION_CANCEL -> cancelSession("用户取消")
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun startSession(intent: Intent) {
        cleanupResources(removeGroup = true)
        stopped.set(false)
        taskId = intent.getIntExtra(EXTRA_TASK_ID, -1)
        val files = intent.getParcelableArrayListExtra<FileInfo>(EXTRA_FILES).orEmpty()
        if (taskId == -1 || files.isEmpty()) {
            fail("没有可发送的文件")
            return
        }
        if (!MyApplication.getInstance().setBusy()) {
            fail("HMTA 正在执行其他传输")
            return
        }
        ownsBusyFlag = true
        generation = NfcTransferCoordinator.startSend()
        val senderFiles = files.mapNotNull(::toSenderFile)
        if (senderFiles.isEmpty()) {
            fail("无法读取待发送内容")
            return
        }
        configureHceAndStartServer(senderFiles)
    }

    private fun toSenderFile(file: FileInfo): SenderFile? {
        if (file.textContent != null) {
            val bytes = file.textContent.toByteArray(StandardCharsets.UTF_8)
            return SenderFile("sharedText.txt", "text/plain", bytes.size.toLong(), bytes = bytes)
        }
        if (file.uri == android.net.Uri.EMPTY) return null
        return SenderFile(
            fileName = file.name.ifBlank { "shared.bin" },
            mimeType = file.mimeType.ifBlank { "application/octet-stream" },
            size = file.size.coerceAtLeast(0),
            uri = file.uri,
        )
    }

    private fun configureHceAndStartServer(files: List<SenderFile>) {
        scope.launch(Dispatchers.IO) {
            val btMac = ShizukuUtils.getMacAddress(applicationContext, "hci0")
            if (btMac.isNullOrBlank() || btMac == "02:00:00:00:00:00") {
                fail("无法通过 Shizuku 读取真实蓝牙 MAC")
                return@launch
            }
            OppoNdefHceService.btMac = btMac
            OppoNdefHceService.topActivityPackageName = packageName
            OppoNdefHceService.ndefMode = OppoNdefHceService.MODE_SAME_BRAND_PTC
            OppoNdefHceService.invalidate()
            touchBaseline = OppoNdefHceService.selectCount

            val server = NfcP2pServer(
                context = applicationContext,
                onLog =(::log),
                onProgress = { done, total ->
                    updateState(NfcTransferPhase.TRANSFERRING, "正在发送文件", done, total)
                    sendProgress(done, total)
                },
                senderFiles = files,
                onComplete = { success, reason ->
                    if (success) complete(reason) else fail(reason)
                },
            )
            p2pServer = server
            server.start()
            updateState(NfcTransferPhase.WAITING_FOR_TAP, "请将华为 NFC 区域贴近 OPPO")
            waitForFreshTap()
        }
    }

    private fun waitForFreshTap() {
        val deadline = System.currentTimeMillis() + TAP_TIMEOUT_MS
        fun poll() {
            if (!isActiveSession()) return
            if (OppoNdefHceService.selectCount > touchBaseline) {
                log("检测到新的 HCE SELECT，开始扫描 OPPO")
                updateState(NfcTransferPhase.BLE_NEGOTIATING, "触碰成功，请在 OPPO 端确认接收")
                startScan()
            } else if (System.currentTimeMillis() >= deadline) {
                fail("30 秒内未检测到 NFC 触碰")
            } else {
                handler.postDelayed(::poll, 250)
            }
        }
        handler.post(::poll)
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
        scanRounds = 0
        startScanRound()
    }

    @SuppressLint("MissingPermission")
    private fun startScanRound() {
        if (!isActiveSession() || !scanning) return
        scanRounds++
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner?.startScan(null, settings, scanCallback) }
            .onFailure { fail("无法启动 BLE 扫描：${it.message}") }
        handler.postDelayed({
            if (!scanning || !isActiveSession()) return@postDelayed
            runCatching { scanner?.stopScan(scanCallback) }
            if (scanRounds >= MAX_SCAN_ROUNDS) {
                fail("长时间未发现 OPPO 接收广播")
            } else {
                log("第 $scanRounds 轮未发现 OPPO，继续扫描")
                startScanRound()
            }
        }, SCAN_ROUND_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        scanning = false
        runCatching { scanner?.stopScan(scanCallback) }
        scanner = null
    }

    @SuppressLint("MissingPermission")
    private fun createP2pGroup(oppoMac: String) {
        updateState(NfcTransferPhase.PREPARING_NETWORK, "正在创建 Wi‑Fi Direct 组")
        val manager = getSystemService(WifiP2pManager::class.java)
        val channel = manager.initialize(this, Looper.getMainLooper(), null)
        p2pManager = manager
        p2pChannel = channel
        groupFetchAttempts = 0

        fun create() {
            manager.createGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = fetchP2pGroupInfo(oppoMac)

                override fun onFailure(reason: Int) {
                    if (reason == WifiP2pManager.BUSY) {
                        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() = create()
                            override fun onFailure(removeReason: Int) =
                                fail("移除旧 P2P 组失败：$removeReason")
                        })
                    } else {
                        fail("创建 Wi‑Fi Direct 组失败：$reason")
                    }
                }
            })
        }
        create()
    }

    @SuppressLint("MissingPermission")
    private fun fetchP2pGroupInfo(oppoMac: String) {
        if (!isActiveSession()) return
        if (groupFetchAttempts++ >= 15) {
            fail("等待 Wi‑Fi Direct 组信息超时")
            return
        }
        val manager = p2pManager ?: return fail("P2P 管理器不可用")
        val channel = p2pChannel ?: return fail("P2P 通道不可用")
        manager.requestGroupInfo(channel) { group: WifiP2pGroup? ->
            if (group == null || group.networkName.isNullOrBlank()) {
                handler.postDelayed({ fetchP2pGroupInfo(oppoMac) }, 800)
                return@requestGroupInfo
            }
            scope.launch(Dispatchers.IO) {
                val realP2pMac = readP2pMacWithRetry()
                val fallback = group.owner?.deviceAddress
                    ?.replace(":", "")
                    ?.uppercase(Locale.US)
                    ?: "000000000000"
                val goMac = realP2pMac ?: fallback
                log("P2P 组就绪：${group.networkName} freq=${group.frequency} mac=$goMac")
                handler.post {
                    connectGatt(
                        oppoMac = oppoMac,
                        ssid = group.networkName,
                        psk = group.passphrase.orEmpty(),
                        frequency = group.frequency,
                        goMac = goMac,
                    )
                }
            }
        }
    }

    private suspend fun readP2pMacWithRetry(): String? {
        repeat(5) {
            val mac = ShizukuUtils.getMacAddress(applicationContext, "p2p0")
            if (!mac.isNullOrBlank() && mac != "02:00:00:00:00:00") return mac
            kotlinx.coroutines.delay(500)
        }
        return null
    }

    private fun connectGatt(
        oppoMac: String,
        ssid: String,
        psk: String,
        frequency: Int,
        goMac: String,
    ) {
        if (!isActiveSession()) return
        updateState(NfcTransferPhase.BLE_NEGOTIATING, "正在向 OPPO 发送热点凭据")
        val client = OshareGattClient(
            context = applicationContext,
            mac = oppoMac,
            onLog =(::log),
            onFinished = { success, reason ->
                if (success) {
                    updateState(NfcTransferPhase.PREPARING_NETWORK, "等待 OPPO 加入热点")
                } else {
                    fail(reason)
                }
            },
            ourMac = goMac,
            ourSsid = ssid,
            ourPsk = psk,
            ourFreq = frequency,
        )
        gattClient = client
        client.connect()
    }

    private fun complete(reason: String) {
        if (!stopped.compareAndSet(false, true)) return
        updateState(NfcTransferPhase.COMPLETED, reason)
        updateNotification("发送完成")
        sendFinished(true, reason)
        handler.postDelayed({ finishSession() }, 1_500)
    }

    private fun fail(reason: String) {
        if (!stopped.compareAndSet(false, true)) return
        updateState(NfcTransferPhase.FAILED, reason)
        log("失败：$reason")
        updateNotification(reason)
        sendFinished(false, reason)
        handler.postDelayed({ finishSession() }, 1_500)
    }

    private fun cancelSession(reason: String) {
        if (!stopped.compareAndSet(false, true)) return
        updateState(NfcTransferPhase.CANCELLING, reason)
        p2pServer?.cancel(reason)
        sendFinished(false, reason)
        finishSession()
    }

    private fun finishSession() {
        updateState(NfcTransferPhase.CLEANING_UP, "正在清理连接")
        cleanupResources(removeGroup = true)
        NfcTransferCoordinator.reset(generation)
        if (ownsBusyFlag) {
            MyApplication.getInstance().clearBusy()
            ownsBusyFlag = false
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @SuppressLint("MissingPermission")
    private fun cleanupResources(removeGroup: Boolean) {
        handler.removeCallbacksAndMessages(null)
        stopScan()
        gattClient?.disconnect()
        gattClient = null
        p2pServer?.stop()
        p2pServer = null
        if (removeGroup) {
            val manager = p2pManager
            val channel = p2pChannel
            if (manager != null && channel != null) runCatching { manager.removeGroup(channel, null) }
        }
        p2pManager = null
        p2pChannel = null
    }

    private fun updateState(
        phase: NfcTransferPhase,
        message: String,
        done: Long = NfcTransferCoordinator.state.value.transferredBytes,
        total: Long = NfcTransferCoordinator.state.value.totalBytes,
    ) {
        if (!isCurrentSession()) return
        NfcTransferCoordinator.transition(generation, phase, message, done, total)
        updateNotification(if (total > 0) "$message ${done * 100 / total}%" else message, done, total)
    }

    private fun updateNotification(text: String, done: Long = 0, total: Long = 0) {
        NotificationManagerCompat.from(this).notify(
            NOTIFICATION_ID,
            buildNotification(text, done, total),
        )
    }

    private fun buildNotification(text: String, done: Long = 0, total: Long = 0) =
        NotificationCompat.Builder(this, NotificationUtils.SENDER_CHAN_ID)
            .setSmallIcon(R.drawable.ic_upload_file)
            .setContentTitle("NFC 一碰传")
            .setContentText(text)
            .setOngoing(!stopped.get())
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, ShareActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .addAction(0, "取消", cancelPendingIntent())
            .apply {
                if (done > 0 && total > 0) {
                    setProgress(100, (done * 100 / total).toInt().coerceIn(0, 100), false)
                }
            }
            .build()

    private fun cancelPendingIntent() = PendingIntent.getService(
        this,
        0,
        Intent(this, NfcSendService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun sendProgress(done: Long, total: Long) {
        sendBroadcast(
            Intent(ACTION_SEND_PROGRESS)
                .setPackage(packageName)
                .putExtra("taskId", taskId)
                .putExtra("processed", done)
                .putExtra("total", total),
        )
    }

    private fun sendFinished(success: Boolean, reason: String) {
        sendBroadcast(
            Intent(ACTION_SEND_FINISHED)
                .setPackage(packageName)
                .putExtra("taskId", taskId)
                .putExtra("success", success)
                .putExtra("reason", reason),
        )
    }

    private fun isCurrentSession(): Boolean =
        generation > 0 && NfcTransferCoordinator.isCurrent(generation)

    private fun isActiveSession(): Boolean = isCurrentSession() && !stopped.get()

    private fun log(message: String) {
        Log.i(TAG, "[NFC Send][$generation] $message")
    }

    override fun onDestroy() {
        cleanupResources(removeGroup = true)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_SEND_PROGRESS = "com.sjcyz.hmta.nfc.SEND_PROGRESS"
        const val ACTION_SEND_FINISHED = "com.sjcyz.hmta.nfc.SEND_FINISHED"
        private const val ACTION_START = "com.sjcyz.hmta.nfc.SEND_START"
        private const val ACTION_CANCEL = "com.sjcyz.hmta.nfc.SEND_CANCEL"
        private const val EXTRA_TASK_ID = "taskId"
        private const val EXTRA_FILES = "files"
        private const val NOTIFICATION_ID = 5
        private const val TAP_TIMEOUT_MS = 30_000L
        private const val SCAN_ROUND_MS = 6_000L
        private const val MAX_SCAN_ROUNDS = 7

        fun start(context: Context, taskId: Int, files: List<FileInfo>) {
            val intent = Intent(context, NfcSendService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TASK_ID, taskId)
                .putParcelableArrayListExtra(EXTRA_FILES, ArrayList(files))
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, NfcSendService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
