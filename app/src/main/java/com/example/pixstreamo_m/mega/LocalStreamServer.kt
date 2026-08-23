/**
 * PixStreamo Local Streaming Broadcaster
 *
 * Runs a lightweight Ktor server to bridge MEGA's encrypted storage to the TV.
 */
package com.example.pixstreamo_m.mega

import android.util.Log
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.NetworkInterface

class LocalStreamServer(private val repository: MegaRepository) {

    private var server: CIOApplicationEngine? = null
    private val port = 8080

    fun start() {
        if (server != null) return

        Log.d("PixStreamo_Trace", "LocalServer: Starting broadcaster on port $port")
        server = embeddedServer(CIO, port = port) {
            // Enable CORS to ensure the TV's browser doesn't block the request
            install(CORS) {
                anyHost()
                allowHeader(HttpHeaders.ContentType)
            }

            routing {
                get("/stream") {
                    val handle = call.request.queryParameters["h"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val key = call.request.queryParameters["k"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val folderUrl = call.request.queryParameters["f"] ?: return@get call.respond(HttpStatusCode.BadRequest)

                    Log.d("PixStreamo_Trace", "LocalServer: TV Request -> $handle")

                    try {
                        val bytes = withContext(Dispatchers.IO) {
                            repository.decryptImage(handle, key, folderUrl, isThumbnail = false, priority = DecryptPriority.URGENT)
                        }

                        if (bytes.isNotEmpty()) {
                            // Default to JPEG, most TVs will sniff the format correctly
                            val contentType = ContentType.Image.JPEG
                            
                            Log.d("PixStreamo_Trace", "LocalServer: Serving $handle (${bytes.size} bytes)")
                            call.respondBytes(bytes, contentType)
                        } else {
                            Log.e("PixStreamo_Trace", "LocalServer: Decryption returned 0 bytes for $handle")
                            call.respond(HttpStatusCode.NotFound)
                        }
                    } catch (e: Exception) {
                        Log.e("PixStreamo_Trace", "LocalServer: Critical error serving $handle", e)
                        call.respond(HttpStatusCode.InternalServerError)
                    }
                }
            }
        }
        
        try {
            server?.start(wait = false)
            Log.d("PixStreamo_Trace", "LocalServer: Broadcaster active at ${getBaseUrl()}")
        } catch (e: Exception) {
            Log.e("PixStreamo_Trace", "LocalServer: Failed to start", e)
        }
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
        Log.d("PixStreamo_Trace", "LocalServer: Broadcaster stopped")
    }

    fun getBaseUrl(): String = "http://${getIpAddress()}:$port"

    /**
     * Finds the Wi-Fi IP address. Prefers interfaces like wlan0.
     */
    private fun getIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            
            // Priority 1: Wi-Fi interfaces
            val wifiAddr = interfaces.filter { it.name.contains("wlan") || it.name.contains("eth") }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is InetAddress && it.address.size == 4 }
            
            if (wifiAddr != null) return wifiAddr.hostAddress ?: "127.0.0.1"

            // Priority 2: Any non-loopback IPv4
            val anyAddr = interfaces.flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is InetAddress && it.address.size == 4 }
                
            if (anyAddr != null) return anyAddr.hostAddress ?: "127.0.0.1"
            
        } catch (e: Exception) {
            Log.e("PixStreamo_Trace", "LocalServer: IP lookup failed", e)
        }
        return "127.0.0.1"
    }
}
