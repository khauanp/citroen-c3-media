package io.github.jqssun.airplay.connectivity

/** Reassembles repeated/out-of-order UDP route parts without discarding useful parts. */
class C3LinkRouteAssembler(
    private val maximumParts: Int = 64,
    private val maximumPartLength: Int = 1_000,
    private val timeoutMs: Long = 20_000L,
) {
    private var pending: Pending? = null

    @Synchronized
    fun accept(routeId: String, part: Int, parts: Int, payload: String, nowMs: Long): String? {
        if (routeId.isBlank() || parts !in 1..maximumParts || part !in 0 until parts) return null
        if (payload.isEmpty() || payload.length > maximumPartLength) return null
        var current = pending
        if (current == null || current.routeId != routeId || current.parts.size != parts || nowMs - current.updatedAtMs > timeoutMs) {
            current = Pending(routeId, arrayOfNulls(parts), nowMs)
            pending = current
        }
        current.parts[part] = payload
        current.updatedAtMs = nowMs
        if (current.parts.any { it == null }) return null
        return buildString(current.parts.sumOf { it?.length ?: 0 }) {
            current.parts.forEach { append(it.orEmpty()) }
        }.also { pending = null }
    }

    @Synchronized fun clear() { pending = null }

    private data class Pending(
        val routeId: String,
        val parts: Array<String?>,
        var updatedAtMs: Long,
    )
}
