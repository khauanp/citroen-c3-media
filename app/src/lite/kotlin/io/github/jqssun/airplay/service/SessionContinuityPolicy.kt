package io.github.jqssun.airplay.service

/**
 * Pure, JVM-testable rules that prevent transient AirPlay events from tearing
 * down the receiver between tracks, notifications or app transitions.
 */
object SessionContinuityPolicy {
    const val TRANSIENT_PAUSE_GRACE_MS = 20_000L
    const val CONNECTION_GRACE_MS = 120_000L
    const val MAX_COVER_BYTES = 1 * 1024 * 1024
    const val LOW_MEMORY_MB = 96L

    fun mayPublishPause(
        expectedGeneration: Long,
        currentGeneration: Long,
        mirroring: Boolean,
    ): Boolean = expectedGeneration == currentGeneration && !mirroring

    fun mayReleaseSession(
        expectedGeneration: Long,
        currentGeneration: Long,
        connectionCount: Int,
    ): Boolean = expectedGeneration == currentGeneration && connectionCount == 0

    fun boundedConnectionCount(current: Int, delta: Int): Int =
        (current.toLong() + delta.toLong()).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    fun mayDecodeArtwork(
        byteCount: Int,
        thermalLimited: Boolean,
        availableMemoryMb: Long,
    ): Boolean =
        byteCount in 1..MAX_COVER_BYTES &&
            !thermalLimited &&
            availableMemoryMb !in 1..LOW_MEMORY_MB

    fun isLatestArtwork(jobGeneration: Long, currentGeneration: Long): Boolean =
        jobGeneration == currentGeneration
}
