package com.sjcyz.hmta.nfc

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

enum class NfcTransferDirection {
    OPPO_TO_HMTA,
    HMTA_TO_OPPO,
}

enum class NfcTransferPhase {
    IDLE,
    WAITING_FOR_TAP,
    BLE_NEGOTIATING,
    PREPARING_NETWORK,
    TRANSFERRING,
    FINALIZING,
    COMPLETED,
    CANCELLING,
    FAILED,
    CLEANING_UP,
}

data class NfcTransferState(
    val generation: Long = 0,
    val direction: NfcTransferDirection? = null,
    val phase: NfcTransferPhase = NfcTransferPhase.IDLE,
    val message: String = "",
    val transferredBytes: Long = 0,
    val totalBytes: Long = 0,
    val invitation: OppoNfcInvitation? = null,
)

object NfcTransferCoordinator {
    private val generationCounter = AtomicLong(0)
    private val mutableState = MutableStateFlow(NfcTransferState())
    val state: StateFlow<NfcTransferState> = mutableState.asStateFlow()

    @Synchronized
    fun startReceive(invitation: OppoNfcInvitation): Long {
        val generation = generationCounter.incrementAndGet()
        mutableState.value = NfcTransferState(
            generation = generation,
            direction = NfcTransferDirection.OPPO_TO_HMTA,
            phase = NfcTransferPhase.BLE_NEGOTIATING,
            message = "已识别 OPPO NFC，准备协商连接",
            invitation = invitation,
        )
        return generation
    }

    @Synchronized
    fun startSend(message: String = "请将手机贴近 OPPO"): Long {
        val generation = generationCounter.incrementAndGet()
        mutableState.value = NfcTransferState(
            generation = generation,
            direction = NfcTransferDirection.HMTA_TO_OPPO,
            phase = NfcTransferPhase.WAITING_FOR_TAP,
            message = message,
        )
        return generation
    }

    @Synchronized
    fun transition(
        generation: Long,
        phase: NfcTransferPhase,
        message: String,
        transferredBytes: Long = mutableState.value.transferredBytes,
        totalBytes: Long = mutableState.value.totalBytes,
    ): Boolean {
        val current = mutableState.value
        if (current.generation != generation || current.phase == NfcTransferPhase.IDLE) return false
        mutableState.value = current.copy(
            phase = phase,
            message = message,
            transferredBytes = transferredBytes,
            totalBytes = totalBytes,
        )
        return true
    }

    @Synchronized
    fun reset(generation: Long): Boolean {
        if (mutableState.value.generation != generation) return false
        mutableState.value = NfcTransferState(generation = generation)
        return true
    }

    fun isCurrent(generation: Long): Boolean = mutableState.value.generation == generation
}
