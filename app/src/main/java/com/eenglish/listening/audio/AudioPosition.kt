package com.eenglish.listening.audio

val playbackSpeeds = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/** Media timeline milliseconds, independent of playback speed; safe even for extreme deltas. */
fun seekTarget(positionMs: Long, deltaMs: Long, durationMs: Long): Long {
    val duration = durationMs.coerceAtLeast(0)
    val current = positionMs.coerceIn(0, duration)
    return current + if (deltaMs >= 0) deltaMs.coerceAtMost(duration - current)
        else deltaMs.coerceAtLeast(-current)
}
