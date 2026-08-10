package com.sjcyz.hmta.utils

import java.util.concurrent.TimeUnit

class ProgressCounter(private val totalSize: Long, private val callback: (Long, Long) -> Unit) {
    private var lastProgressUpdate = 0L
    private var lastProcessed = 0L

    fun update(processedSize: Long) {
        val now = System.nanoTime()
        val elapsed = TimeUnit.SECONDS.convert(
            now - lastProgressUpdate, TimeUnit.NANOSECONDS
        )
        val pctDelta = if (totalSize > 0) {
            (processedSize - lastProcessed) * 100.0 / totalSize
        } else {
            0.0
        }
        // Throttle: at most once per second, or on >=1% progress change.
        if (elapsed > 1 || pctDelta >= 1.0) {
            callback(totalSize, processedSize)
            lastProgressUpdate = now
            lastProcessed = processedSize
        }
    }
}
