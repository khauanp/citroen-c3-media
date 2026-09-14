package io.github.jqssun.airplay.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionContinuityPolicyTest {
    @Test
    fun naturalTrackChangesNeverAuthorizeStalePauseOrRelease() {
        val generation = AtomicLong(0L)

        repeat(100_000) {
            val teardown = generation.incrementAndGet()
            generation.incrementAndGet() // metadata, format or progress for next track
            assertFalse(
                SessionContinuityPolicy.mayPublishPause(
                    teardown,
                    generation.get(),
                    mirroring = false,
                ),
            )

            val disconnect = generation.incrementAndGet()
            generation.incrementAndGet() // replacement AirPlay connection/activity
            assertFalse(
                SessionContinuityPolicy.mayReleaseSession(
                    disconnect,
                    generation.get(),
                    connectionCount = 1,
                ),
            )
        }
    }

    @Test
    fun onlySustainedQuietPeriodCanPauseOrRelease() {
        assertTrue(SessionContinuityPolicy.mayPublishPause(9L, 9L, mirroring = false))
        assertFalse(SessionContinuityPolicy.mayPublishPause(9L, 10L, mirroring = false))
        assertFalse(SessionContinuityPolicy.mayPublishPause(9L, 9L, mirroring = true))

        assertTrue(SessionContinuityPolicy.mayReleaseSession(11L, 11L, connectionCount = 0))
        assertFalse(SessionContinuityPolicy.mayReleaseSession(11L, 12L, connectionCount = 0))
        assertFalse(SessionContinuityPolicy.mayReleaseSession(11L, 11L, connectionCount = 1))
    }

    @Test
    fun connectionCounterCannotBecomeNegativeOrOverflow() {
        var count = 0
        repeat(100_000) {
            count = SessionContinuityPolicy.boundedConnectionCount(count, -1)
            assertEquals(0, count)
            count = SessionContinuityPolicy.boundedConnectionCount(count, 1)
            count = SessionContinuityPolicy.boundedConnectionCount(count, -1)
        }
        assertEquals(0, count)
        assertEquals(
            Int.MAX_VALUE,
            SessionContinuityPolicy.boundedConnectionCount(Int.MAX_VALUE, 1),
        )
    }

    @Test
    fun rapidConcurrentActivityInvalidatesEveryOldTeardown() {
        val workers = 8
        val iterations = 25_000
        val generation = AtomicLong(0L)
        val start = CountDownLatch(1)
        val done = CountDownLatch(workers)
        val pool = Executors.newFixedThreadPool(workers)
        val oldToken = generation.incrementAndGet()

        repeat(workers) {
            pool.execute {
                start.await()
                repeat(iterations) { generation.incrementAndGet() }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(20, TimeUnit.SECONDS))
        pool.shutdownNow()

        assertEquals(1L + workers.toLong() * iterations, generation.get())
        assertFalse(
            SessionContinuityPolicy.mayPublishPause(
                oldToken,
                generation.get(),
                mirroring = false,
            ),
        )
        assertFalse(
            SessionContinuityPolicy.mayReleaseSession(
                oldToken,
                generation.get(),
                connectionCount = 0,
            ),
        )
    }

    @Test
    fun artworkFloodAcceptsOnlySafeLatestCover() {
        assertFalse(SessionContinuityPolicy.mayDecodeArtwork(0, false, 512))
        assertFalse(
            SessionContinuityPolicy.mayDecodeArtwork(
                SessionContinuityPolicy.MAX_COVER_BYTES + 1,
                false,
                512,
            ),
        )
        assertFalse(SessionContinuityPolicy.mayDecodeArtwork(1024, true, 512))
        assertFalse(SessionContinuityPolicy.mayDecodeArtwork(1024, false, 64))
        assertTrue(SessionContinuityPolicy.mayDecodeArtwork(1024, false, 512))

        var generation = 0L
        repeat(100_000) {
            val stale = generation++
            assertFalse(SessionContinuityPolicy.isLatestArtwork(stale, generation))
        }
        assertTrue(SessionContinuityPolicy.isLatestArtwork(generation, generation))
    }

    @Test
    fun metadataAndHeapPressureAreBounded() {
        assertFalse(SessionContinuityPolicy.mayParseMetadata(0))
        assertTrue(SessionContinuityPolicy.mayParseMetadata(4_096))
        assertFalse(
            SessionContinuityPolicy.mayParseMetadata(SessionContinuityPolicy.MAX_METADATA_BYTES + 1),
        )

        val max = 32L * 1024L * 1024L
        assertTrue(SessionContinuityPolicy.hasHeapHeadroom(max, 16L * 1024L * 1024L, 4L * 1024L * 1024L))
        assertFalse(SessionContinuityPolicy.hasHeapHeadroom(max, 30L * 1024L * 1024L, 1L * 1024L * 1024L))
        assertFalse(SessionContinuityPolicy.hasHeapHeadroom(0L, 0L, 0L))
    }
}
