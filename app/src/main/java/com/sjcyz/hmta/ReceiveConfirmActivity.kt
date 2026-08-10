package com.sjcyz.hmta

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sjcyz.hmta.models.P2pInfo
import com.sjcyz.hmta.services.P2pReceiverService
import com.sjcyz.hmta.ui.theme.HmtaTheme
import com.sjcyz.hmta.utils.registerInternalBroadcastReceiver
import com.sjcyz.hmta.utils.TAG

/**
 * Keeps the app in the foreground while a transfer is being received, because some
 * ROMs (e.g. EMUI) refuse WifiP2p operations while the app has no visible activity.
 *
 * The page first shows a "connecting" state, then switches to the accept/reject
 * question as soon as the request details (sender, file name, size) arrive via
 * [P2pReceiverService.ACTION_RECEIVE_ASK]. The same question is shown on the
 * full-screen notification that raised this page, so there is only one
 * notification instead of two.
 */
@SuppressLint("MissingPermission")
class ReceiveConfirmActivity : ComponentActivity() {
    private var receiver: BroadcastReceiver? = null
    private val askState = mutableStateOf<ReceiveAskInfo?>(null)
    private val acceptedState = mutableStateOf(false)
    private val progressState = mutableStateOf<Pair<Long, Long>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // The notification accept/reject buttons launch this activity with only
        // an action + taskId (no p2p_info). In that case we must not finish:
        // processIntent() below handles the action directly.
        val hasAction = intent.getStringExtra("action") != null
        if (!hasAction) {
            val p2pInfo = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra("p2p_info", P2pInfo::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra("p2p_info")
            } ?: run {
                finish()
                return
            }

            startForegroundService(P2pReceiverService.getIntent(this, p2pInfo))
        }

        processIntent(intent)

        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    P2pReceiverService.ACTION_RECEIVE_FINISHED -> {
                        Log.i(TAG, "ReceiveConfirmActivity: transfer finished, closing")
                        finish()
                    }

                    P2pReceiverService.ACTION_RECEIVE_ASK -> {
                        askState.value = ReceiveAskInfo(
                            taskId = intent.getIntExtra("taskId", -1),
                            senderName = intent.getStringExtra("senderName").orEmpty(),
                            fileName = intent.getStringExtra("fileName").orEmpty(),
                            fileCount = intent.getIntExtra("fileCount", 1),
                            totalSize = intent.getLongExtra("totalSize", 0L),
                        )
                    }

                    P2pReceiverService.ACTION_RECEIVE_PROGRESS -> {
                        progressState.value = Pair(
                            intent.getLongExtra("processed", 0L),
                            intent.getLongExtra("total", 0L)
                        )
                    }
                }
            }
        }
        registerInternalBroadcastReceiver(r, IntentFilter().apply {
            addAction(P2pReceiverService.ACTION_RECEIVE_FINISHED)
            addAction(P2pReceiverService.ACTION_RECEIVE_ASK)
            addAction(P2pReceiverService.ACTION_RECEIVE_PROGRESS)
        })
        receiver = r

        setContent {
            HmtaTheme {
                // Floating bottom sheet, matching the share flow style.
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.35f),
                        shape = MaterialTheme.shapes.extraLarge
                    ) {
                        val ask = askState.value
                        val accepted = acceptedState.value
                        if (accepted) {
                            ReceiveReceivingContent(progress = progressState.value)
                        } else if (ask == null) {
                            ReceiveConnectingContent(
                                onCancel = {
                                    finish()
                                    sendBroadcast(
                                        Intent(P2pReceiverService.ACTION_CANCEL_RECEIVE_CONFIRM)
                                    )
                                }
                            )
                        } else {
                            ReceiveAskContent(
                                ask = ask,
                                onAccept = {
                                    acceptedState.value = true
                                    sendBroadcast(
                                        Intent(P2pReceiverService.ACTION_ACCEPTED)
                                            .putExtra("taskId", ask.taskId)
                                    )
                                },
                                onReject = {
                                    finish()
                                    sendBroadcast(
                                        Intent(P2pReceiverService.ACTION_DISMISSED)
                                            .putExtra("taskId", ask.taskId)
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        processIntent(intent)
    }

    private fun processIntent(intent: Intent?) {
        when (intent?.getStringExtra("action")) {
            P2pReceiverService.ACTION_ACCEPT -> {
                val taskId = intent.getIntExtra("taskId", askState.value?.taskId ?: -1)
                acceptedState.value = true
                if (taskId != -1) {
                    sendBroadcast(
                        Intent(P2pReceiverService.ACTION_ACCEPTED)
                            .putExtra("taskId", taskId)
                    )
                }
            }

            P2pReceiverService.ACTION_REJECT -> {
                val taskId = intent.getIntExtra("taskId", askState.value?.taskId ?: -1)
                finish()
                if (taskId != -1) {
                    sendBroadcast(
                        Intent(P2pReceiverService.ACTION_DISMISSED)
                            .putExtra("taskId", taskId)
                    )
                } else {
                    sendBroadcast(
                        Intent(P2pReceiverService.ACTION_CANCEL_RECEIVE_CONFIRM)
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        receiver?.let { unregisterReceiver(it) }
        receiver = null
    }
}

private data class ReceiveAskInfo(
    val taskId: Int,
    val senderName: String,
    val fileName: String,
    val fileCount: Int,
    val totalSize: Long,
)

@Composable
fun ReceiveConnectingContent(onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.recv_connecting),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = stringResource(R.string.recv_connecting_desc))
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onCancel) {
            Text(text = stringResource(R.string.cancel_receive))
        }
    }
}

@Composable
fun ReceiveReceivingContent(progress: Pair<Long, Long>?) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.receiving),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(modifier = Modifier.height(12.dp))
        if (progress != null && progress.second > 0) {
            val fraction = progress.first.toFloat() / progress.second.toFloat()
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${Formatter.formatShortFileSize(context, progress.first)} / ${
                    Formatter.formatShortFileSize(context, progress.second)
                }"
            )
        } else {
            Text(text = stringResource(R.string.recv_connecting_desc))
        }
    }
}

@Composable
private fun ReceiveAskContent(
    ask: ReceiveAskInfo,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    val context = LocalContext.current
    val fmtSize = Formatter.formatShortFileSize(context, ask.totalSize)
    val desc = context.resources.getQuantityString(
        R.plurals.noti_request_desc, ask.fileCount, ask.fileCount, fmtSize
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.receive_request_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = stringResource(R.string.shared_from, ask.senderName))
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = ask.fileName,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = desc)
        Spacer(modifier = Modifier.height(24.dp))
        Row {
            Button(onClick = onReject) {
                Text(text = stringResource(R.string.reject))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Button(onClick = onAccept) {
                Text(text = stringResource(R.string.accept))
            }
        }
    }
}
