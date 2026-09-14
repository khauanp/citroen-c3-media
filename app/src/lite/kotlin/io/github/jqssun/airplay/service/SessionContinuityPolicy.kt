package io.github.jqssun.airplay.service

/**
 * Pure, JVM-testable rules that prevent transient AirPlay events from tearing
 * down the receiver between tracks, notifications or app transitions.
 */
object SessionContinuityPolicy {
    const val TRANSIENT_PAUSE_GRACE_MS = 20_000L
    const val CONNECTION_GRACE_MS = 120_000L
    const val MAX_COVER_BYTES = 1 * 1024 * 1024
    const val MAX_METADATA_BYTES = 256 * 1024
    const val LOW_MEMORY_MB = 96L
    const val MIN_HEAP_HEADROOM_BYTES = 8L * 1024L * 1024L

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

    fun mayParseMetadata(byteCount: Int): Boolean = byteCount in 1..MAX_METADATA_BYTES

    fun hasHeapHeadroom(maxMemory: Long, totalMemory: Long, freeMemory: Long): Boolean {
        if (maxMemory <= 0L || totalMemory < 0L || freeMemory < 0L) return false
        val used = (totalMemory - freeMemory).coerceAtLeast(0L)
        return maxMemory - used >= MIN_HEAP_HEADROOM_BYTES
    }
}
