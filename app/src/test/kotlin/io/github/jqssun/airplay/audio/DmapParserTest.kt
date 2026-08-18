package io.github.jqssun.airplay.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DmapParserTest {
    @Test
    fun parsesNestedTrackMetadata() {
        val item = join(
            tag("minm", "Drive".toByteArray(Charsets.UTF_8)),
            tag("asar", "Incubus".toByteArray(Charsets.UTF_8)),
            tag("asal", "Make Yourself".toByteArray(Charsets.UTF_8)),
            tag("astm", ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(232_000).array()),
        )
        val parsed = DmapParser.parse(tag("mlit", item))
        val track = TrackInfo.fromDmap(parsed)

        assertEquals("Drive", track.title)
        assertEquals("Incubus", track.artist)
        assertEquals("Make Yourself", track.album)
        assertEquals(232_000L, track.durationMs)
    }

    @Test
    fun preservesUtf8Metadata() {
        val parsed = DmapParser.parse(tag("minm", "Céu Azul".toByteArray(Charsets.UTF_8)))
        assertEquals("Céu Azul", parsed["minm"])
    }

    @Test
    fun stopsSafelyOnInvalidLength() {
        val malformed = ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN)
            .put("minm".toByteArray(Charsets.US_ASCII))
            .putInt(1000)
            .put(1)
            .array()
        assertTrue(DmapParser.parse(malformed).isEmpty())
    }

    private fun tag(name: String, payload: ByteArray): ByteArray {
        return ByteBuffer.allocate(8 + payload.size).order(ByteOrder.BIG_ENDIAN)
            .put(name.toByteArray(Charsets.US_ASCII))
            .putInt(payload.size)
            .put(payload)
            .array()
    }

    private fun join(vararg fields: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        fields.forEach(output::write)
        return output.toByteArray()
    }
}
