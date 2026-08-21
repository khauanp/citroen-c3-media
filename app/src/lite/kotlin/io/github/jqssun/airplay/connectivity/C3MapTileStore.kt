package io.github.jqssun.airplay.connectivity

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.util.LruCache
import io.github.jqssun.airplay.service.MapTileChunk
import io.github.jqssun.airplay.service.MapTileKey
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Small two-level raster cache designed for the 1 GB ASUS K00E. */
class C3MapTileStore(context: Context) {
    private val directory = File(context.cacheDir, "c3-map-v2").apply { mkdirs() }
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "C3Map-Cache").apply { isDaemon = true }
    }
    private val memory = object : LruCache<MapTileKey, Bitmap>(MEMORY_KB) {
        override fun sizeOf(key: MapTileKey, value: Bitmap): Int = value.byteCount / 1024
    }
    private val assemblies = HashMap<String, TileAssembly>()
    private val loading = HashSet<MapTileKey>()
    private val unavailableUntil = HashMap<MapTileKey, Long>()
    private val requestQueued = AtomicBoolean(false)
    private val requestLock = Any()
    private val requested = LinkedHashSet<MapTileKey>()

    @Volatile var onTileAvailable: (() -> Unit)? = null
    @Volatile var onTilesRequested: ((List<MapTileKey>) -> Unit)? = null

    fun tile(key: MapTileKey): Bitmap? = synchronized(memory) { memory.get(key) }

    fun prefetch(keys: List<MapTileKey>) {
        val now = SystemClock.elapsedRealtime()
        keys.take(MAX_VISIBLE_TILES).forEach { raw ->
            val key = raw.normalized() ?: return@forEach
            if (tile(key) != null || unavailableUntil.getOrDefault(key, 0L) > now) return@forEach
            synchronized(loading) {
                if (!loading.add(key)) return@forEach
            }
            worker.execute { loadOrRequest(key) }
        }
    }

    fun accept(chunk: MapTileChunk) {
        val key = chunk.key.normalized() ?: return
        if (chunk.parts !in 1..MAX_PARTS || chunk.part !in 0 until chunk.parts || chunk.encodedData.length > MAX_ENCODED_PART) return
        val assemblyKey = "${key.diskName}:${chunk.transferId}"
        val complete = synchronized(assemblies) {
            val existing = assemblies[assemblyKey]
            val assembly = if (existing == null || existing.parts.size != chunk.parts ||
                SystemClock.elapsedRealtime() - existing.updatedAt > ASSEMBLY_TIMEOUT_MS
            ) {
                TileAssembly(key, arrayOfNulls(chunk.parts), chunk.expiresAtEpochSeconds).also { assemblies[assemblyKey] = it }
            } else existing
            assembly.parts[chunk.part] = try {
                Base64.decode(chunk.encodedData, Base64.DEFAULT)
            } catch (_: IllegalArgumentException) {
                null
            }
            assembly.updatedAt = SystemClock.elapsedRealtime()
            if (assembly.parts.any { it == null }) null else {
                assemblies.remove(assemblyKey)
                assembly
            }
        } ?: return
        worker.execute { install(complete) }
    }

    fun reportUnavailable(raw: MapTileKey) {
        val key = raw.normalized() ?: return
        synchronized(unavailableUntil) { unavailableUntil[key] = SystemClock.elapsedRealtime() + RETRY_AFTER_MS }
        synchronized(loading) { loading.remove(key) }
    }

    fun close() {
        worker.shutdownNow()
        synchronized(assemblies) { assemblies.clear() }
        synchronized(memory) { memory.evictAll() }
    }

    private fun loadOrRequest(key: MapTileKey) {
        val file = File(directory, key.diskName)
        try {
            if (file.isFile && file.length() in 1..MAX_TILE_BYTES.toLong()) {
                val decoded = decode(file.readBytes())
                if (decoded != null) {
                    put(key, decoded)
                    synchronized(loading) { loading.remove(key) }
                    return
                }
                file.delete()
            }
        } catch (error: Exception) {
            Log.w(TAG, "Map tile cache read failed", error)
        }
        synchronized(loading) { loading.remove(key) }
        synchronized(requestLock) { requested += key }
        dispatchRequests()
    }

    private fun dispatchRequests() {
        if (!requestQueued.compareAndSet(false, true)) return
        worker.execute {
            try {
                val batch = synchronized(requestLock) {
                    requested.take(REQUEST_BATCH).also { requested.removeAll(it.toSet()) }
                }
                if (batch.isNotEmpty()) onTilesRequested?.invoke(batch)
            } finally {
                requestQueued.set(false)
                val hasMore = synchronized(requestLock) { requested.isNotEmpty() }
                if (hasMore) dispatchRequests()
            }
        }
    }

    private fun install(assembly: TileAssembly) {
        val total = assembly.parts.sumOf { it?.size ?: 0 }
        if (total !in 1..MAX_TILE_BYTES) return reportUnavailable(assembly.key)
        val bytes = ByteArray(total)
        var offset = 0
        assembly.parts.forEach { part ->
            val value = part ?: return@forEach
            value.copyInto(bytes, offset)
            offset += value.size
        }
        val decoded = decode(bytes) ?: return reportUnavailable(assembly.key)
        try {
            val file = File(directory, assembly.key.diskName)
            file.writeBytes(bytes)
            pruneDiskCache()
        } catch (error: Exception) {
            Log.w(TAG, "Map tile disk cache unavailable", error)
        }
        synchronized(unavailableUntil) { unavailableUntil.remove(assembly.key) }
        put(assembly.key, decoded)
    }

    private fun decode(bytes: ByteArray): Bitmap? = try {
        BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inDither = true
            },
        )
    } catch (_: OutOfMemoryError) {
        null
    } catch (_: RuntimeException) {
        null
    }

    private fun put(key: MapTileKey, bitmap: Bitmap) {
        synchronized(memory) { memory.put(key, bitmap) }
        onTileAvailable?.invoke()
    }

    private fun pruneDiskCache() {
        val files = directory.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_BYTES) return
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= DISK_BYTES) return
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    private data class TileAssembly(
        val key: MapTileKey,
        val parts: Array<ByteArray?>,
        val expiresAtEpochSeconds: Long,
        var updatedAt: Long = SystemClock.elapsedRealtime(),
    )

    companion object {
        private const val TAG = "C3MapTileStore"
        private const val MEMORY_KB = 12 * 1024
        private const val DISK_BYTES = 96L * 1024L * 1024L
        private const val MAX_TILE_BYTES = 512 * 1024
        private const val MAX_ENCODED_PART = 2_000
        private const val MAX_PARTS = 512
        private const val MAX_VISIBLE_TILES = 48
        private const val REQUEST_BATCH = 8
        private const val RETRY_AFTER_MS = 20_000L
        private const val ASSEMBLY_TIMEOUT_MS = 35_000L
    }
}
