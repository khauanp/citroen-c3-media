package io.github.jqssun.airplay.connectivity

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.github.jqssun.airplay.service.C3LinkNavigation
import io.github.jqssun.airplay.service.MapTileChunk
import io.github.jqssun.airplay.service.MapTileKey
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/** Local UDP bridge used by C3 Link. Route data is retained until an explicit stop. */
class C3LinkServer(context: Context, private val listener: Listener) {
    interface Listener {
        fun onNavigationChanged(navigation: C3LinkNavigation)
        fun onMapTileChunk(chunk: MapTileChunk)
        fun onMapTileUnavailable(key: MapTileKey)
    }

    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val routeAssembler = C3LinkRouteAssembler()
    private val running = AtomicBoolean(false)
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var approvedDeviceId: String? = preferences.getString(KEY_DEVICE_ID, null)
    @Volatile private var clientAddress: InetAddress? = null
    @Volatile private var clientPort: Int = 0
    @Volatile private var lastPacketAt = 0L
    @Volatile private var disconnectReported = false
    @Volatile private var navigation = restoreRoute()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread(::receiveLoop, "C3Link-UDP").apply {
            isDaemon = true
            start()
        }
        if (navigation.route.size >= 2) listener.onNavigationChanged(navigation)
    }

    fun stop() {
        running.set(false)
        routeAssembler.clear()
        socket?.close()
        socket = null
        worker?.interrupt()
        worker = null
    }

    fun requestTiles(keys: List<MapTileKey>) {
        val activeSocket = socket ?: return
        val address = clientAddress ?: return
        val port = clientPort
        if (port <= 0 || approvedDeviceId == null) return
        keys.mapNotNull { it.normalized() }.distinct().chunked(8).forEach { group ->
            val tiles = JSONArray()
            group.forEach { key ->
                tiles.put(JSONObject().put("z", key.zoom).put("x", key.x).put("y", key.y))
            }
            send(activeSocket, address, port, JSONObject().put("type", "tile-request").put("version", PROTOCOL).put("tiles", tiles))
        }
    }

    private fun receiveLoop() {
        try {
            DatagramSocket(PORT).use { activeSocket ->
                socket = activeSocket
                activeSocket.soTimeout = 1_000
                activeSocket.receiveBufferSize = 180_000
                activeSocket.sendBufferSize = 60_000
                val buffer = ByteArray(MAX_PACKET)
                while (running.get()) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        activeSocket.receive(packet)
                        val json = JSONObject(String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8))
                        handlePacket(activeSocket, packet, json)
                    } catch (_: SocketTimeoutException) {
                        reportTemporaryDisconnectIfNeeded()
                    } catch (error: Throwable) {
                        if (running.get()) Log.w(TAG, "C3 Link packet rejected", error)
                    }
                }
            }
        } catch (error: Throwable) {
            if (running.get()) Log.e(TAG, "C3 Link server stopped", error)
        } finally {
            socket = null
            running.set(false)
        }
    }

    private fun handlePacket(activeSocket: DatagramSocket, packet: DatagramPacket, json: JSONObject) {
        if (json.optInt("version", -1) != PROTOCOL) return
        val deviceId = json.optString("deviceId").take(80)
        if (deviceId.isBlank()) return
        val type = json.optString("type")
        if (type == "hello") {
            receiveHello(activeSocket, packet, json, deviceId)
            return
        }
        if (approvedDeviceId != deviceId) {
            sendStatus(activeSocket, packet.address, packet.port, "pair-first", deviceId)
            return
        }
        clientAddress = packet.address
        clientPort = packet.port
        markAlive()
        when (type) {
            "ping" -> sendStatus(activeSocket, packet.address, packet.port, "pong", deviceId, navigation.routeId)
            "route" -> receiveRoute(activeSocket, packet, json, deviceId)
            "route-part" -> receiveRoutePart(activeSocket, packet, json, deviceId)
            "position" -> receivePosition(activeSocket, packet, json, deviceId)
            "tile-chunk" -> receiveTileChunk(json)
            "tile-error" -> receiveTileError(json)
            "stop" -> stopNavigation(activeSocket, packet, deviceId)
            "goodbye" -> markTemporarilyDisconnected()
        }
    }

    private fun receiveHello(socket: DatagramSocket, packet: DatagramPacket, json: JSONObject, deviceId: String) {
        val trusted = approvedDeviceId
        if (trusted != null && trusted != deviceId) {
            sendStatus(socket, packet.address, packet.port, "busy", deviceId)
            return
        }
        if (trusted == null) {
            approvedDeviceId = deviceId
            preferences.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        }
        clientAddress = packet.address
        clientPort = packet.port
        markAlive()
        navigation = navigation.copy(
            connected = true,
            deviceId = deviceId,
            deviceName = json.optString("name", "iPhone").take(80),
            lastUpdateElapsedMs = lastPacketAt,
        )
        listener.onNavigationChanged(navigation)
        sendStatus(socket, packet.address, packet.port, "ready", deviceId, navigation.routeId)
    }

    private fun receiveRoute(socket: DatagramSocket, packet: DatagramPacket, json: JSONObject, deviceId: String) {
        installRoute(
            socket,
            packet,
            deviceId,
            json.optString("routeId").take(80),
            json.optString("destination").take(180),
            json.optString("polyline"),
            json.optInt("precision", 5),
            json.optDouble("distanceMeters", 0.0),
            json.optDouble("durationSeconds", 0.0),
        )
    }

    private fun receiveRoutePart(socket: DatagramSocket, packet: DatagramPacket, json: JSONObject, deviceId: String) {
        val routeId = json.optString("routeId").take(80)
        val assembled = routeAssembler.accept(
            routeId,
            json.optInt("part", -1),
            json.optInt("parts", 0),
            json.optString("data"),
            SystemClock.elapsedRealtime(),
        ) ?: return
        installRoute(
            socket,
            packet,
            deviceId,
            routeId,
            json.optString("destination").take(180),
            assembled,
            json.optInt("precision", 5),
            json.optDouble("distanceMeters", 0.0),
            json.optDouble("durationSeconds", 0.0),
        )
    }

    private fun installRoute(
        socket: DatagramSocket,
        packet: DatagramPacket,
        deviceId: String,
        routeId: String,
        destination: String,
        encodedPolyline: String,
        precision: Int,
        distanceMeters: Double,
        durationSeconds: Double,
    ) {
        val route = C3LinkPolyline.decode(encodedPolyline, 12_000, precision)
        if (route.size !in 2..12_000 || routeId.isBlank()) {
            sendStatus(socket, packet.address, packet.port, "route-invalid", deviceId, routeId)
            return
        }
        navigation = navigation.copy(
            connected = true,
            routeId = routeId,
            destination = destination,
            route = route,
            remainingDistanceMeters = distanceMeters.coerceAtLeast(0.0),
            remainingSeconds = durationSeconds.coerceAtLeast(0.0),
            lastUpdateElapsedMs = lastPacketAt,
        )
        persistRoute(routeId, destination, encodedPolyline, precision, distanceMeters, durationSeconds)
        listener.onNavigationChanged(navigation)
        sendStatus(socket, packet.address, packet.port, "route-ok", deviceId, routeId)
    }

    private fun receivePosition(socket: DatagramSocket, packet: DatagramPacket, json: JSONObject, deviceId: String) {
        val routeId = json.optString("routeId").take(80)
        if (routeId.isBlank() || navigation.route.size < 2 || navigation.routeId != routeId) {
            sendStatus(socket, packet.address, packet.port, "route-missing", deviceId, routeId)
            return
        }
        val latitude = json.optDouble("latitude", navigation.latitude)
        val longitude = json.optDouble("longitude", navigation.longitude)
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return
        val rawCourse = json.optDouble("course", navigation.courseDegrees)
        val course = if (rawCourse.isFinite()) (rawCourse % 360.0 + 360.0) % 360.0 else navigation.courseDegrees
        navigation = navigation.copy(
            connected = true,
            latitude = latitude,
            longitude = longitude,
            speedMps = json.optDouble("speedMps", 0.0).coerceIn(0.0, 100.0),
            courseDegrees = course,
            instruction = json.optString("instruction", navigation.instruction).take(180),
            maneuver = json.optString("maneuver", navigation.maneuver).take(40),
            stepDistanceMeters = json.optDouble("stepDistanceMeters", navigation.stepDistanceMeters).coerceAtLeast(0.0),
            remainingDistanceMeters = json.optDouble("remainingDistanceMeters", navigation.remainingDistanceMeters).coerceAtLeast(0.0),
            remainingSeconds = json.optDouble("remainingSeconds", navigation.remainingSeconds).coerceAtLeast(0.0),
            lastUpdateElapsedMs = lastPacketAt,
        )
        listener.onNavigationChanged(navigation)
    }

    private fun receiveTileChunk(json: JSONObject) {
        val key = MapTileKey(json.optInt("z", -1), json.optInt("x", -1), json.optInt("y", -1)).normalized() ?: return
        val transferId = json.optString("transferId").take(80)
        if (transferId.isBlank()) return
        listener.onMapTileChunk(
            MapTileChunk(
                key,
                transferId,
                json.optInt("part", -1),
                json.optInt("parts", 0),
                json.optString("data"),
                json.optLong("expiresAt", 0L),
            ),
        )
    }

    private fun receiveTileError(json: JSONObject) {
        MapTileKey(json.optInt("z", -1), json.optInt("x", -1), json.optInt("y", -1)).normalized()?.let(listener::onMapTileUnavailable)
    }

    private fun stopNavigation(socket: DatagramSocket, packet: DatagramPacket, deviceId: String) {
        routeAssembler.clear()
        clearPersistedRoute()
        navigation = navigation.copy(
            routeId = "",
            destination = "",
            route = emptyList(),
            instruction = "",
            remainingDistanceMeters = 0.0,
            remainingSeconds = 0.0,
            stepDistanceMeters = 0.0,
            lastUpdateElapsedMs = lastPacketAt,
        )
        listener.onNavigationChanged(navigation)
        sendStatus(socket, packet.address, packet.port, "stopped", deviceId)
    }

    private fun reportTemporaryDisconnectIfNeeded() {
        if (!disconnectReported && lastPacketAt > 0L && SystemClock.elapsedRealtime() - lastPacketAt > DISCONNECT_AFTER_MS) {
            markTemporarilyDisconnected()
        }
    }

    private fun markTemporarilyDisconnected() {
        disconnectReported = true
        navigation = navigation.copy(connected = false, lastUpdateElapsedMs = lastPacketAt)
        listener.onNavigationChanged(navigation)
    }

    private fun markAlive() {
        lastPacketAt = SystemClock.elapsedRealtime()
        disconnectReported = false
    }

    private fun sendStatus(socket: DatagramSocket, address: InetAddress, port: Int, status: String, deviceId: String, routeId: String = "") {
        val response = JSONObject()
            .put("type", "status")
            .put("version", PROTOCOL)
            .put("deviceId", deviceId)
            .put("status", status)
        if (routeId.isNotBlank()) response.put("routeId", routeId)
        if (status == "ready") response.put("capabilities", JSONArray().put("route-parts").put("route-ack-id"))
        send(socket, address, port, response)
    }

    private fun send(socket: DatagramSocket, address: InetAddress, port: Int, json: JSONObject) {
        try {
            val bytes = json.toString().toByteArray(StandardCharsets.UTF_8)
            if (bytes.size <= MAX_PACKET) socket.send(DatagramPacket(bytes, bytes.size, address, port))
        } catch (error: Throwable) {
            if (running.get()) Log.w(TAG, "C3 Link reply failed", error)
        }
    }

    private fun persistRoute(routeId: String, destination: String, polyline: String, precision: Int, distance: Double, duration: Double) {
        preferences.edit()
            .putString(KEY_ROUTE_ID, routeId)
            .putString(KEY_DESTINATION, destination)
            .putString(KEY_POLYLINE, polyline)
            .putInt(KEY_PRECISION, precision)
            .putLong(KEY_DISTANCE, distance.toBits())
            .putLong(KEY_DURATION, duration.toBits())
            .apply()
    }

    private fun restoreRoute(): C3LinkNavigation {
        val routeId = preferences.getString(KEY_ROUTE_ID, "").orEmpty()
        val encoded = preferences.getString(KEY_POLYLINE, "").orEmpty()
        val precision = preferences.getInt(KEY_PRECISION, 5)
        val route = C3LinkPolyline.decode(encoded, 12_000, precision)
        if (routeId.isBlank() || route.size < 2) return C3LinkNavigation()
        return C3LinkNavigation(
            routeId = routeId,
            destination = preferences.getString(KEY_DESTINATION, "").orEmpty(),
            route = route,
            remainingDistanceMeters = Double.fromBits(preferences.getLong(KEY_DISTANCE, 0L)).takeIf { it.isFinite() } ?: 0.0,
            remainingSeconds = Double.fromBits(preferences.getLong(KEY_DURATION, 0L)).takeIf { it.isFinite() } ?: 0.0,
        )
    }

    private fun clearPersistedRoute() {
        preferences.edit()
            .remove(KEY_ROUTE_ID)
            .remove(KEY_DESTINATION)
            .remove(KEY_POLYLINE)
            .remove(KEY_PRECISION)
            .remove(KEY_DISTANCE)
            .remove(KEY_DURATION)
            .apply()
    }

    companion object {
        private const val TAG = "C3LinkServer"
        private const val PORT = 30_303
        private const val PROTOCOL = 2
        private const val MAX_PACKET = 60_000
        private const val DISCONNECT_AFTER_MS = 18_000L
        private const val PREFS = "c3link-server"
        private const val KEY_DEVICE_ID = "device-id"
        private const val KEY_ROUTE_ID = "route-id"
        private const val KEY_DESTINATION = "destination"
        private const val KEY_POLYLINE = "polyline"
        private const val KEY_PRECISION = "precision"
        private const val KEY_DISTANCE = "distance"
        private const val KEY_DURATION = "duration"
    }
}
