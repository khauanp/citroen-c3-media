package io.github.jqssun.airplay.connectivity

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log

/**
 * Process-wide MBTiles cache used by the APK-only map renderer.
 *
 * The existing receiver owns a single serialized tile I/O executor. Keeping this
 * adapter synchronized makes reads/writes safe if lifecycle callbacks overlap.
 * Tile rows use the MBTiles TMS convention while callers keep using OSM/XYZ.
 */
object C3MbTilesStore {
    private const val TAG = "C3MbTilesStore"
    private const val DATABASE_NAME = "c3-map.mbtiles"
    private const val DATABASE_VERSION = 1
    private const val TILE_SIZE = 256
    private const val MAX_TILE_BYTES = 512 * 1024
    private const val MAX_DATABASE_BYTES = 256L * 1024L * 1024L
    private const val KEEP_TILES_AFTER_PRUNE = 2400

    private val lock = Any()
    @Volatile private var helper: Helper? = null

    @JvmStatic
    fun initialize(context: Context) {
        if (helper != null) return
        synchronized(lock) {
            if (helper == null) {
                helper = Helper(context.applicationContext)
                runCatching { helper!!.writableDatabase }
                    .onFailure { Log.w(TAG, "Unable to initialize MBTiles cache", it) }
            }
        }
    }

    /** Returns a fully decoded tile, or null without ever crashing the map. */
    @JvmStatic
    fun read(id: String): Bitmap? = synchronized(lock) {
        val key = parseId(id) ?: return@synchronized null
        val (zoom, x, xyzY) = key
        val db = runCatching { helper?.readableDatabase }.getOrNull() ?: return@synchronized null
        val tmsY = tmsRow(zoom, xyzY) ?: return@synchronized null
        var data: ByteArray? = null
        var expiresAt = 0L
        runCatching {
            db.rawQuery(
                "SELECT t.tile_data,COALESCE(s.expires_epoch_seconds,0) FROM tiles t " +
                    "LEFT JOIN tile_state s ON t.zoom_level=s.zoom_level " +
                    "AND t.tile_column=s.tile_column AND t.tile_row=s.tile_row " +
                    "WHERE t.zoom_level=? AND t.tile_column=? AND t.tile_row=?",
                arrayOf(zoom.toString(), x.toString(), tmsY.toString()),
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    data = cursor.getBlob(0)
                    expiresAt = cursor.getLong(1)
                }
            }
        }.onFailure { Log.w(TAG, "Unable to read MBTiles tile $zoom/$x/$xyzY", it) }
        val bytes = data ?: return@synchronized null
        if (expiresAt <= System.currentTimeMillis() / 1000L) return@synchronized null
        if (!looksLikePng(bytes)) return@synchronized null
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
        if (bitmap?.width != TILE_SIZE || bitmap.height != TILE_SIZE) {
            bitmap?.recycle()
            return@synchronized null
        }
        runCatching {
            val touched = ContentValues().apply { put("last_access_epoch_ms", System.currentTimeMillis()) }
            db.update(
                "tile_state",
                touched,
                "zoom_level=? AND tile_column=? AND tile_row=?",
                arrayOf(zoom.toString(), x.toString(), tmsY.toString()),
            )
        }
        bitmap
    }

    /** Stores only validated 256x256 PNGs in one atomic SQLite transaction. */
    @JvmStatic
    fun write(id: String, png: ByteArray, expiresAtEpochSeconds: Long) {
        if (png.isEmpty() || png.size > MAX_TILE_BYTES || !looksLikePng(png)) return
        val key = parseId(id) ?: return
        val (zoom, x, xyzY) = key
        val tmsY = tmsRow(zoom, xyzY) ?: return
        synchronized(lock) {
            val db = runCatching { helper?.writableDatabase }.getOrNull() ?: return
            runCatching {
                db.beginTransaction()
                try {
                    val tile = ContentValues().apply {
                        put("zoom_level", zoom)
                        put("tile_column", x)
                        put("tile_row", tmsY)
                        put("tile_data", png)
                    }
                    db.insertWithOnConflict("tiles", null, tile, SQLiteDatabase.CONFLICT_REPLACE)
                    val state = ContentValues().apply {
                        put("zoom_level", zoom)
                        put("tile_column", x)
                        put("tile_row", tmsY)
                        put("expires_epoch_seconds", expiresAtEpochSeconds)
                        put("last_access_epoch_ms", System.currentTimeMillis())
                    }
                    db.insertWithOnConflict("tile_state", null, state, SQLiteDatabase.CONFLICT_REPLACE)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                pruneIfNeeded(db)
            }.onFailure { Log.w(TAG, "Unable to write MBTiles tile $zoom/$x/$xyzY", it) }
        }
    }

    private fun pruneIfNeeded(db: SQLiteDatabase) {
        val path = db.path ?: return
        if (java.io.File(path).length() <= MAX_DATABASE_BYTES) return
        db.execSQL(
            "DELETE FROM tiles WHERE rowid IN (" +
                "SELECT t.rowid FROM tiles t LEFT JOIN tile_state s " +
                "ON t.zoom_level=s.zoom_level AND t.tile_column=s.tile_column AND t.tile_row=s.tile_row " +
                "ORDER BY COALESCE(s.last_access_epoch_ms,0) ASC " +
                "LIMIT MAX(0,(SELECT COUNT(*) FROM tiles)-$KEEP_TILES_AFTER_PRUNE))",
        )
        db.execSQL(
            "DELETE FROM tile_state WHERE NOT EXISTS (SELECT 1 FROM tiles t WHERE " +
                "t.zoom_level=tile_state.zoom_level AND t.tile_column=tile_state.tile_column " +
                "AND t.tile_row=tile_state.tile_row)",
        )
    }

    private fun tmsRow(zoom: Int, xyzY: Int): Int? {
        if (zoom !in 0..22) return null
        val dimension = 1 shl zoom
        if (xyzY !in 0 until dimension) return null
        return dimension - 1 - xyzY
    }

    private fun parseId(id: String): Triple<Int, Int, Int>? {
        val parts = id.split('_')
        if (parts.size != 3) return null
        val zoom = parts[0].toIntOrNull() ?: return null
        val x = parts[1].toIntOrNull() ?: return null
        val y = parts[2].toIntOrNull() ?: return null
        return Triple(zoom, x, y)
    }

    private fun looksLikePng(data: ByteArray): Boolean =
        data.size >= 8 &&
            data[0] == 0x89.toByte() && data[1] == 0x50.toByte() &&
            data[2] == 0x4e.toByte() && data[3] == 0x47.toByte() &&
            data[4] == 0x0d.toByte() && data[5] == 0x0a.toByte() &&
            data[6] == 0x1a.toByte() && data[7] == 0x0a.toByte()

    private class Helper(context: Context) :
        SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            runCatching { db.enableWriteAheadLogging() }
            db.execSQL("PRAGMA synchronous=NORMAL")
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE metadata (name TEXT PRIMARY KEY, value TEXT)")
            db.execSQL(
                "CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, " +
                    "tile_data BLOB, PRIMARY KEY (zoom_level,tile_column,tile_row))",
            )
            db.execSQL(
                "CREATE TABLE tile_state (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, " +
                    "expires_epoch_seconds INTEGER, last_access_epoch_ms INTEGER, " +
                    "PRIMARY KEY (zoom_level,tile_column,tile_row))",
            )
            val metadata = mapOf(
                "name" to "C3 Media offline cache",
                "format" to "png",
                "type" to "baselayer",
                "version" to "1",
                "scheme" to "tms",
            )
            metadata.forEach { (name, value) ->
                db.execSQL("INSERT INTO metadata(name,value) VALUES(?,?)", arrayOf(name, value))
            }
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
