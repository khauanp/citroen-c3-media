package io.github.jqssun.airplay.audio

/**
 * Bounded, allocation-conscious DMAP (iTunes metadata) parser.
 *
 * AirPlay metadata arrives on a native callback thread. Malformed or unusually
 * large packets must never allocate a payload-sized temporary array or recurse
 * without a limit, because either can exhaust the small Android 5/K00E heap.
 */
object DmapParser {
    private const val MAX_INPUT_BYTES = 256 * 1024
    private const val MAX_ITEMS = 192
    private const val MAX_DEPTH = 4
    private const val MAX_STRING_BYTES = 8 * 1024

    private val INT_TAGS = setOf(
        "astm", "astn", "asdk", "asts", "miid", "mcti", "mper", "asai", "asri", "asci", "asgi",
    )
    private val CONTAINER_TAGS = setOf("mlit", "mcon", "mlcl")

    fun parse(data: ByteArray): Map<String, Any> {
        if (data.size !in 1..MAX_INPUT_BYTES) return emptyMap()
        val result = mutableMapOf<String, Any>()
        parseRange(data, 0, data.size, 0, result, ParseBudget())
        return result
    }

    private fun parseRange(
        data: ByteArray,
        from: Int,
        to: Int,
        depth: Int,
        result: MutableMap<String, Any>,
        budget: ParseBudget,
    ) {
        if (depth > MAX_DEPTH) return
        var offset = from
        while (offset <= to - 8 && budget.items < MAX_ITEMS) {
            val tag = String(data, offset, 4, Charsets.US_ASCII)
            val length = readLength(data, offset + 4)
            offset += 8
            if (length < 0 || length > to - offset) return
            budget.items++
            when {
                tag in CONTAINER_TAGS -> {
                    val nested = mutableMapOf<String, Any>()
                    parseRange(data, offset, offset + length, depth + 1, nested, budget)
                    result[tag] = nested
                }
                tag in INT_TAGS && length in 1..8 -> result[tag] = readInt(data, offset, length)
                tag !in INT_TAGS && length <= MAX_STRING_BYTES ->
                    result[tag] = String(data, offset, length, Charsets.UTF_8)
            }
            offset += length
        }
    }

    private fun readLength(data: ByteArray, offset: Int): Int {
        val value = ((data[offset].toLong() and 0xFFL) shl 24) or
            ((data[offset + 1].toLong() and 0xFFL) shl 16) or
            ((data[offset + 2].toLong() and 0xFFL) shl 8) or
            (data[offset + 3].toLong() and 0xFFL)
        return if (value > Int.MAX_VALUE) -1 else value.toInt()
    }

    private fun readInt(data: ByteArray, offset: Int, length: Int): Long {
        var value = 0L
        repeat(length) { index ->
            value = (value shl 8) or (data[offset + index].toLong() and 0xFFL)
        }
        return value
    }

    private class ParseBudget(var items: Int = 0)
}
