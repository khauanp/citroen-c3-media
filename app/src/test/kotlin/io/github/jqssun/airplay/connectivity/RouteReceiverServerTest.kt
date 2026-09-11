package io.github.jqssun.airplay.connectivity

import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList

class RouteReceiverServerTest {
    @Test fun realHttpRequests() {
        val received = CopyOnWriteArrayList<String>()
        val server = RouteReceiverServer(0) { received.add(it) }
        server.start(2000, true)
        try {
            fun request(path: String, method: String = "GET"): Pair<Int, String> {
                val connection = URL("http://127.0.0.1:${server.listeningPort}$path")
                    .openConnection() as HttpURLConnection
                connection.requestMethod = method
                connection.connectTimeout = 2000
                connection.readTimeout = 2000
                return try {
                    val code = connection.responseCode
                    val stream = if (code < 400) connection.inputStream else connection.errorStream
                    code to stream.bufferedReader().use { it.readText() }
                } finally { connection.disconnect() }
            }
            val route = "https://embed.waze.com/iframe?lat=-23.5&lon=-46.6&zoom=14"
            val encoded = URLEncoder.encode(route, "UTF-8")
            assertEquals(200 to "ROTA_RECEBIDA", request("/set-route?waze_url=$encoded"))
            assertEquals(listOf(route), received.toList())
            for (path in listOf("/wrong", "/set-route", "/set-route?waze_url=",
                "/set-route?waze_url=javascript%3Aalert(1)",
                "/set-route?waze_url=file%3A%2F%2F%2Fetc%2Fhosts",
                "/set-route?waze_url=$encoded&waze_url=$encoded")) {
                val response = request(path)
                println("Route rejection: $path -> $response")
                assertEquals(path, 400 to "PARAMETRO_INVALIDO", response)
            }
            assertEquals(400, request("/set-route?waze_url=$encoded", "POST").first)
            assertEquals(1, received.size)
            repeat(100) { assertEquals(200, request("/set-route?waze_url=$encoded").first) }
            assertEquals(101, received.size)
        } finally { server.stop() }
        assertFalse(server.isAlive)
    }
}
