package io.github.jqssun.airplay.connectivity

import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

class RouteResourceProxyTest {
    @Test fun relayUsesPeerAndPreservesBinaryMimeStatusAndCors() {
        val bytes = byteArrayOf(0, 1, 2, -1, -128, 34, 10)
        val token = "12345678-1234-1234-1234-123456789abc"
        val calls = AtomicInteger()
        val iphone = object : NanoHTTPD(8081) {
            override fun serve(session: IHTTPSession): Response {
                assertEquals("/fetch-proxy", session.uri)
                assertEquals(token, session.headers["x-c3-relay-token"])
                assertEquals("https://embed.waze.com/test?a=1&b=2", session.parameters["url"]?.single())
                calls.incrementAndGet()
                return newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, "image/png", ByteArrayInputStream(bytes), bytes.size.toLong()).also {
                    it.addHeader("Access-Control-Allow-Origin", "https://embed.waze.com")
                    it.addHeader("Content-Range", "bytes 0-6/7")
                }
            }
        }
        val tablet = RouteReceiverServer(0) { }
        iphone.start(2000, true)
        tablet.start(2000, true)
        try {
            val route = "https://embed.waze.com/test?a=1&b=2"
            fun connection(path: String) = URL("http://127.0.0.1:${tablet.listeningPort}$path").openConnection() as HttpURLConnection
            val register = connection("/set-route?waze_url=" + URLEncoder.encode(route, "UTF-8"))
            register.setRequestProperty("X-C3-Relay-Token", token)
            assertEquals(200, register.responseCode)
            register.inputStream.close(); register.disconnect()
            assertEquals(route, tablet.currentRoute)
            repeat(20) {
                val resource = connection("/fetch-proxy?url=" + URLEncoder.encode(route, "UTF-8"))
                resource.readTimeout = 5000
                assertEquals(206, resource.responseCode)
                assertEquals("image/png", resource.contentType)
                assertEquals("https://embed.waze.com", resource.getHeaderField("Access-Control-Allow-Origin"))
                assertArrayEquals(bytes, resource.inputStream.use { it.readBytes() })
                resource.disconnect()
            }
            assertEquals(20, calls.get())
            for (url in listOf("https://waze.com.evil.invalid/a", "https://evilwaze.com/a", "file:///tmp/a", "https://127.0.0.1/a", "https://user@waze.com/a", "https://waze.com:8443/a")) {
                val result = tablet.proxy.fetch(url, "GET", emptyMap())
                assertEquals(url, 403, result.status); result.body.close()
            }
            assertEquals(20, calls.get())
            val post = tablet.proxy.fetch(route, "POST", emptyMap())
            assertEquals(405, post.status); post.body.close()
            iphone.stop()
            val unavailable = tablet.proxy.fetch(route, "GET", emptyMap())
            assertEquals(502, unavailable.status); unavailable.body.close()
        } finally { tablet.stop(); iphone.stop() }
    }

    @Test fun missingCompanionFailsLocallyAndShutdownDisablesProxy() {
        val proxy = RouteResourceProxy()
        val result = proxy.fetch("https://embed.waze.com/iframe", "GET", emptyMap())
        assertEquals(503, result.status); result.body.close()
        assertFalse(proxy.pair("example.com", "12345678-1234-1234-1234-123456789abc"))
        proxy.close()
        assertFalse(proxy.pair("127.0.0.1", "12345678-1234-1234-1234-123456789abc"))
    }
}
