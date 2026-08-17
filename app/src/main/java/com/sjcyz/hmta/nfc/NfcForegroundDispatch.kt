package com.sjcyz.hmta.nfc

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.Ndef
import android.nfc.tech.NfcA
import android.nfc.tech.NfcF
import android.os.Build

class NfcForegroundDispatch(private val activity: Activity) {
    private val adapter: NfcAdapter? by lazy { NfcAdapter.getDefaultAdapter(activity) }
    private val deduplicator = NfcInvitationDeduplicator()

    fun enable() {
        val nfcAdapter = adapter ?: return
        val pendingIntent = PendingIntent.getActivity(
            activity,
            0,
            Intent(activity, activity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val filters = arrayOf(
            IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
                addDataScheme("https")
                addDataAuthority("connect.oppo.com", null)
            },
            IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
                addDataType("text/plain")
                addDataType("text/uri-list")
            },
            IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED),
            IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED),
        )
        nfcAdapter.enableForegroundDispatch(
            activity,
            pendingIntent,
            filters,
            arrayOf(
                arrayOf(NfcF::class.java.name),
                arrayOf(NfcA::class.java.name),
                arrayOf(IsoDep::class.java.name),
                arrayOf(MifareClassic::class.java.name),
            ),
        )
    }

    fun disable() {
        runCatching { adapter?.disableForegroundDispatch(activity) }
    }

    fun invitationFrom(intent: Intent?): OppoNfcInvitation? {
        if (intent == null) return null
        val rawUri = intent.dataString ?: readUriFromNdef(intent)
        val invitation = OppoNfcInvitationParser.parse(rawUri) ?: return null
        return invitation.takeIf(deduplicator::accept)
    }

    @Suppress("DEPRECATION")
    private fun readUriFromNdef(intent: Intent): String? {
        val messages = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayExtra(
                NfcAdapter.EXTRA_NDEF_MESSAGES,
                NdefMessage::class.java,
            )
        } else {
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        }
        messages?.filterIsInstance<NdefMessage>()?.forEach { message ->
            message.records.forEach { record ->
                record.toUri()?.toString()?.let { return it }
            }
        }

        val tag = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        } ?: return null
        return runCatching {
            val ndef = Ndef.get(tag) ?: return@runCatching null
            ndef.connect()
            try {
                ndef.ndefMessage?.records?.firstNotNullOfOrNull { it.toUri()?.toString() }
            } finally {
                ndef.close()
            }
        }.getOrNull()
    }
}
