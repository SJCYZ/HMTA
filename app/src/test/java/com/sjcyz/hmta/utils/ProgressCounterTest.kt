package com.sjcyz.hmta.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressCounterTest {
    @Test
    fun firstUpdateAlwaysReports() {
        var count = 0
        val counter = ProgressCounter(1000) { _, _ -> count++ }
        counter.update(5)
        assertEquals(1, count)
    }

    @Test
    fun smallDeltaWithinOneSecondIsThrottled() {
        val reported = mutableListOf<Long>()
        val counter = ProgressCounter(1000) { _, processed -> reported.add(processed) }
        counter.update(5)
        counter.update(6)
        counter.update(7)
        assertEquals(listOf(5L), reported)
    }

    @Test
    fun largeDeltaReportsImmediately() {
        val reported = mutableListOf<Long>()
        val counter = ProgressCounter(1000) { _, processed -> reported.add(processed) }
        counter.update(5)
        counter.update(100) // 9.5% jump, must bypass the one-second throttle
        assertEquals(listOf(5L, 100L), reported)
    }

    @Test
    fun boundaryPercentIsThrottled() {
        val reported = mutableListOf<Long>()
        val counter = ProgressCounter(1000) { _, processed -> reported.add(processed) }
        counter.update(5)
        counter.update(14) // 0.9% delta -> throttled
        assertEquals(listOf(5L), reported)
        counter.update(15) // 1.0% delta -> reported
        assertEquals(listOf(5L, 15L), reported)
    }
}
