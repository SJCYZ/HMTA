package com.sjcyz.hmta

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ComponentName
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelUuid
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import com.sjcyz.hmta.BuildConfig
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import com.sjcyz.hmta.models.DiscoveredDevice
import com.sjcyz.hmta.models.FileInfo
import com.sjcyz.hmta.models.TaskInfo
import com.sjcyz.hmta.services.P2pSenderService
import com.sjcyz.hmta.services.NfcSendService
import com.sjcyz.hmta.nfc.NfcForegroundDispatch
import com.sjcyz.hmta.ui.DefaultCard
import com.sjcyz.hmta.ui.theme.HmtaTheme
import com.sjcyz.hmta.utils.BleUtils
import com.sjcyz.hmta.utils.DeviceUtils
import com.sjcyz.hmta.utils.NotificationUtils
import com.sjcyz.hmta.utils.ShizukuUtils
import com.sjcyz.hmta.utils.TAG
import java.nio.ByteBuffer
import kotlin.random.Random

private data class SendingState(
    val taskId: Int,
    val deviceName: String,
    val progress: Pair<Long, Long>? = null,
    val isNfc: Boolean = false,
)

class ShareActivity : ComponentActivity() {
    private lateinit var bluetoothManager: BluetoothManager
    private val fileInfosState = mutableStateOf<List<FileInfo>?>(null)
    private lateinit var nfcForegroundDispatch: NfcForegroundDispatch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcForegroundDispatch = NfcForegroundDispatch(this)

        bluetoothManager = getSystemService(BluetoothManager::class.java)
        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            NotificationUtils.showBluetoothToast(this)
            finish()
            return
        }

        val wifiManager = getSystemService(WifiManager::class.java)
        if (!wifiManager.isWifiEnabled) {
            NotificationUtils.showWifiToast(this)
            finish()
            return
        }

        val fileInfos = resolveFileInfos(intent)
        if (fileInfos == null) {
            finish()
            return
        }
        fileInfosState.value = fileInfos

        Log.i(TAG, "Shared ${fileInfos.size} files")

        ShizukuUtils.bindService()

        enableEdgeToEdge()
        setContent {
            HmtaTheme {
                val files = fileInfosState.value
                if (files != null) {
                    // key() resets the sending/scan state when a new set of
                    // files arrives via onNewIntent (singleTask relaunch).
                    key(files) {
                        ShareActivityContent(files)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == android.nfc.NfcAdapter.ACTION_NDEF_DISCOVERED ||
            intent.action == android.nfc.NfcAdapter.ACTION_TAG_DISCOVERED ||
            intent.action == android.nfc.NfcAdapter.ACTION_TECH_DISCOVERED
        ) {
            Log.i(TAG, "发送期间拦截本机 NFC 读卡事件")
            return
        }
        setIntent(intent)
        val fileInfos = resolveFileInfos(intent)
        if (fileInfos == null) {
            finish()
            return
        }
        fileInfosState.value = fileInfos
        Log.i(TAG, "New share request: ${fileInfos.size} files")
    }

    override fun onResume() {
        super.onResume()
        nfcForegroundDispatch.enable()
    }

    override fun onPause() {
        nfcForegroundDispatch.disable()
        super.onPause()
    }

    private fun resolveFileInfos(intent: Intent): List<FileInfo>? {
        val fileInfos = try {
            if (intent.action == Intent.ACTION_SEND) {
                @Suppress("DEPRECATION") val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) {
                    listOf(uri).mapNotNull { extractFileInfo(it) }
                } else {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    listOf(
                        FileInfo(
                            Uri.EMPTY, "", "", 0, text
                        )
                    )
                }
            } else {
                @Suppress("DEPRECATION") val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                uris?.mapNotNull { extractFileInfo(it) } ?: emptyList()
            }
        } catch (e: Throwable) {
            Log.e("ShareActivity", "Failed to extract file info", e)
            null
        }

        if (fileInfos.isNullOrEmpty()) {
            Toast.makeText(this, R.string.no_file_shared, Toast.LENGTH_SHORT).show()
            return null
        }
        return fileInfos
    }

    private fun extractFileInfo(uri: Uri): FileInfo? {
        val cr = contentResolver
        var name: String? = null
        var mime: String? = null
        var size: Long? = null

        try {
            val proj = arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.SIZE
            )
            cr.query(uri, proj, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && !c.isNull(nameIdx)) name = c.getString(nameIdx)
                    val mimeIdx = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                    if (mimeIdx >= 0 && !c.isNull(mimeIdx)) mime = c.getString(mimeIdx)
                    val sizeIdx = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to query file info for $uri", e)
        }

        // Some providers do not expose SIZE; fall back to the asset file descriptor.
        if (size == null || size!! <= 0) {
            try {
                cr.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    val len = afd.length
                    if (len > 0) size = len
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to open asset fd for $uri", e)
            }
        }

        // Last resort: some providers report an unknown length. Read the stream
        // once to determine the real size so the peer never sees totalSize=0/-1.
        if (size == null || size!! <= 0) {
            try {
                cr.openInputStream(uri)?.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                    }
                    if (total > 0) size = total
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to read stream size for $uri", e)
            }
        }

        // Fallback file name from the URI when the provider has no DISPLAY_NAME.
        if (name.isNullOrEmpty()) {
            val last = uri.lastPathSegment ?: ""
            val q = last.indexOf('?')
            name = if (q >= 0) last.substring(0, q) else last
        }

        if (mime.isNullOrEmpty()) {
            mime = cr.getType(uri) ?: "application/octet-stream"
        }

        if (name.isNullOrEmpty()) return null
        return FileInfo(uri, name, mime ?: "application/octet-stream", size ?: -1, null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareActivityContent(files: List<FileInfo>) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val discoveredDevices = deviceScanner()
    var sending by remember { mutableStateOf<SendingState?>(null) }
    val nfcTransferEnabled = remember { AppSettings(context).nfcTransferEnabled }

    // Keep this page (and therefore the share URI grant) alive until the
    // transfer finishes; some providers revoke the temporary grant as soon
    // as this activity is destroyed.
    DisposableEffect(activity) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    P2pSenderService.ACTION_SEND_FINISHED -> {
                        val taskId = intent.getIntExtra("taskId", -1)
                        if (sending?.taskId == taskId) {
                            activity?.finish()
                        }
                    }

                    NfcSendService.ACTION_SEND_FINISHED -> {
                        val taskId = intent.getIntExtra("taskId", -1)
                        if (sending?.taskId == taskId) activity?.finish()
                    }

                    P2pSenderService.ACTION_SEND_PROGRESS -> {
                        val taskId = intent.getIntExtra("taskId", -1)
                        val current = sending
                        if (current?.taskId == taskId) {
                            sending = current.copy(
                                progress = Pair(
                                    intent.getLongExtra("processed", 0L),
                                    intent.getLongExtra("total", 0L)
                                )
                            )
                        }
                    }

                    NfcSendService.ACTION_SEND_PROGRESS -> {
                        val taskId = intent.getIntExtra("taskId", -1)
                        val current = sending
                        if (current?.taskId == taskId) {
                            sending = current.copy(
                                progress = Pair(
                                    intent.getLongExtra("processed", 0L),
                                    intent.getLongExtra("total", 0L),
                                ),
                            )
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(P2pSenderService.ACTION_SEND_FINISHED)
            addAction(P2pSenderService.ACTION_SEND_PROGRESS)
            addAction(NfcSendService.ACTION_SEND_FINISHED)
            addAction(NfcSendService.ACTION_SEND_PROGRESS)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    val iconMod = Modifier.size(48.dp).padding(end = 16.dp)

    // Floating overlay: dark backdrop + centered card
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        // Floating card with device list
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.3f),
            shape = MaterialTheme.shapes.extraLarge
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                val currentSending = sending
                if (currentSending == null) {
                    // Title bar
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.choose_recipient),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Device list
                    val listState = rememberLazyListState()
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (nfcTransferEnabled) {
                            item(key = "nfc-oppo") {
                                DefaultCard(onClick = {
                                    val nfcTaskId = Random.nextInt()
                                    NfcSendService.start(context, nfcTaskId, files)
                                    sending = SendingState(
                                        taskId = nfcTaskId,
                                        deviceName = "NFC 一碰传（OPPO）",
                                        isNfc = true,
                                    )
                                }) {
                                    Row(
                                        modifier = Modifier.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Filled.AccountCircle, null, modifier = iconMod)
                                        Column {
                                            Text("NFC 一碰传（OPPO）", style = MaterialTheme.typography.titleMedium)
                                            Text("选择后将两台手机的 NFC 区域贴近")
                                        }
                                    }
                                }
                            }
                        }
                        if (discoveredDevices.isEmpty()) {
                            item { Text(stringResource(R.string.scanning_desc)) }
                        } else {
                            items(discoveredDevices, key = { it.id }) {
                                DefaultCard(onClick = {
                                    val task = TaskInfo(
                                        id = Random.nextInt(), device = it, files = files
                                    )
                                    if (P2pSenderService.startTaskChecked(context, task)) {
                                        sending = SendingState(task.id, it.name)
                                    }
                                }) {
                                    Row(
                                        modifier = Modifier.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Filled.AccountCircle, null, modifier = iconMod)
                                        Column {
                                            Text(
                                                text = if (BuildConfig.DEBUG) {
                                                    "${it.name} (${it.id}, ${it.device.address})"
                                                } else {
                                                    it.name
                                                },
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                            Text(text = it.brand ?: stringResource(R.string.unknown))
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Sending in progress: keep this page visible so the shared
                    // URI grants stay valid until the transfer completes.
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = stringResource(R.string.sending),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = currentSending.deviceName)
                        currentSending.progress?.let { p ->
                            Spacer(modifier = Modifier.height(16.dp))
                            val fraction = if (p.second > 0) {
                                p.first.toFloat() / p.second.toFloat()
                            } else {
                                0f
                            }
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "${Formatter.formatShortFileSize(context, p.first)} / ${
                                    Formatter.formatShortFileSize(context, p.second)
                                }"
                            )
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(onClick = {
                            if (currentSending.isNfc) {
                                NfcSendService.cancel(context)
                            } else {
                                context.sendBroadcast(
                                    Intent(P2pSenderService.ACTION_CANCEL_SENDING)
                                        .putExtra("taskId", currentSending.taskId)
                                )
                            }
                            activity?.finish()
                        }) {
                            Text(text = stringResource(R.string.cancel_receive))
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun deviceScanner(): List<DiscoveredDevice> {
    val context = LocalContext.current
    var discoveredDevices by remember { mutableStateOf(emptyList<DiscoveredDevice>()) }

    LifecycleResumeEffect(context) {
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager.adapter
        val devicesLock = Object()

        val callback = object : ScanCallback() {
            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "BLE scan failed: $errorCode")
            }

            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord ?: return
                var supports5Ghz = false
                var deviceName: String? = null
                var brandId: Byte? = null
                var senderId: String? = null

                for ((uuid, data) in record.serviceData.entries) {
                    when (data.size) {
                        6 -> {
                            // UUID contains brand and 5GHz flag
                            val buf = ByteBuffer.allocate(16)
                            buf.putLong(uuid.uuid.mostSignificantBits)
                            buf.putLong(uuid.uuid.leastSignificantBits)
                            val arr = buf.array()
                            supports5Ghz = arr[2].toInt() == 1
                            brandId = arr[3]
                        }

                        27 -> {
                            // Data contains device name and ID
                            val nameBuf = mutableListOf<Byte>()
                            for (i in 10..25) {
                                if (data[i].toInt() != 0) {
                                    nameBuf.add(data[i])
                                } else {
                                    break
                                }
                            }

                            val senderIdRaw = data[8].toInt().shl(8).or(data[9].toInt())
                            senderId = String.format("%04x", senderIdRaw)

                            var name = nameBuf.toByteArray().decodeToString()
                            if (name.last() == '\t') {
                                name = name.removeSuffix("\t") + "..."
                            }
                            deviceName = name
                        }
                    }
                }

                if (deviceName == null || senderId == null) {
                    return
                }

                val brand = brandId?.let {
                    DeviceUtils.deviceNameById(it)
                }

                val newDevice = DiscoveredDevice(
                    result.device, senderId, deviceName, brand, supports5Ghz
                )
                var replaced = false
                synchronized(devicesLock) {
                    val newList = discoveredDevices.map {
                        if (it.id == senderId) {
                            replaced = true
                            newDevice
                        } else {
                            it
                        }
                    }.toMutableList()
                    if (!replaced) {
                        newList.add(newDevice)
                    }
                    discoveredDevices = newList
                }
            }
        }

        var startedScanner: BluetoothLeScanner? = null

        if (adapter != null) {
            val scanner = adapter.bluetoothLeScanner
            val filters = listOf(
                ScanFilter.Builder().setServiceUuid(ParcelUuid(BleUtils.ADV_SERVICE_UUID)).build()
            )
            val settings =
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

            try {
                scanner.startScan(filters, settings, callback)
                startedScanner = scanner
                Log.d(TAG, "Started scanning")
            } catch (e: SecurityException) {
                Log.e(TAG, "Failed to start scan", e)
            }
        }

        onPauseOrDispose {
            try {
                startedScanner?.stopScan(callback)
                Log.d(TAG, "Stopped scanning")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop scan", e)
            }
        }
    }

    return discoveredDevices
}
