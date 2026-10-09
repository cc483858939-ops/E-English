package com.eenglish.listening

import com.eenglish.listening.audio.seekTarget
import com.eenglish.listening.ui.components.formatTime
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPositionTest {
    @Test fun fiveSecondJumpsAndRepeatedJumpsUseMediaMilliseconds() {
        assertEquals(25000L, seekTarget(20000, 5000, 90000))
        assertEquals(15000L, seekTarget(20000, -5000, 90000))
        var position = 10000L
        repeat(3) { position = seekTarget(position, 5000, 90000) }
        assertEquals(25000L, position)
    }
    @Test fun edgesAndUnavailableDurationAreClamped() {
        assertEquals(0L, seekTarget(2000, -5000, 90000))
        assertEquals(90000L, seekTarget(89000, 5000, 90000))
        assertEquals(0L, seekTarget(90000, 5000, 0))
        assertEquals(0L, seekTarget(-1, -5000, 90000))
    }
    @Test fun extremeDeltasCannotOverflow() {
        assertEquals(Long.MAX_VALUE, seekTarget(Long.MAX_VALUE - 1, Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(0L, seekTarget(90000, Long.MIN_VALUE, Long.MAX_VALUE))
    }
    @Test fun timestampsKeepSecondsAndHandleHours() {
        assertEquals("0:00", formatTime(-1))
        assertEquals("3:18", formatTime(198000))
        assertEquals("59:59", formatTime(3599999))
        assertEquals("1:00:00", formatTime(3600000))
        assertEquals("12:34:56", formatTime(45296000))
    }
}
