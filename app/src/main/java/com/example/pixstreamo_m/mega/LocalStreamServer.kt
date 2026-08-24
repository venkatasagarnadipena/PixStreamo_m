/**
 * PixStreamo Local Streaming Broadcaster
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
    private val port = 8181 

    fun start() {
        if (server != null) return

        val ip = getIpAddress()
        Log.d("PixStreamo_Trace", "LocalServer: STARTING. URL will be: http://$ip:$port")

        server = embeddedServer(CIO, port = port) {
            install(CORS) {
                anyHost()
                allowHeader(HttpHeaders.ContentType)
            }

            routing {
                // Test endpoint to verify reachability
                get("/ping") {
                    val remote = call.request.local.remoteHost
                    Log.d("PixStreamo_Trace", "LocalServer: PING from $remote")
                    call.respondText("PixStreamo Server is ALIVE")
                }

                get("/test.jpg") {
                    val remote = call.request.local.remoteHost
                    Log.d("PixStreamo_Trace", "LocalServer: TEST Request from $remote")
                    call.respondText("REACHABLE")
                }

                get("/stream") {
                    val remote = call.request.local.remoteHost
                    val handle = call.request.queryParameters["h"]
                    val key = call.request.queryParameters["k"]
                    val folderUrl = call.request.queryParameters["f"]
                    
                    Log.d("PixStreamo_Trace", "LocalServer: [HTTP] Request from $remote | H=$handle | K=$key")

                    if (handle == null || key == null || folderUrl == null) {
                        Log.e("PixStreamo_Trace", "LocalServer: Missing parameters! H=$handle, K=$key, F=$folderUrl")
                        return@get call.respond(HttpStatusCode.BadRequest)
                    }

                    try {
                        Log.d("PixStreamo_Trace", "LocalServer: Starting decryption for $handle")
                        val bytes = withContext(Dispatchers.IO) {
                            repository.decryptImage(handle, key, folderUrl, isThumbnail = false, priority = DecryptPriority.URGENT)
                        }

                        if (bytes.isNotEmpty()) {
                            Log.d("PixStreamo_Trace", "LocalServer: Decryption SUCCESS. Sending ${bytes.size} bytes to TV")
                            call.response.header(HttpHeaders.ContentLength, bytes.size.toString())
                            call.respondBytes(bytes, ContentType.Image.JPEG)
                        } else {
                            Log.e("PixStreamo_Trace", "LocalServer: Decryption returned EMPTY bytes for $handle")
                            call.respond(HttpStatusCode.NotFound)
                        }
                    } catch (e: Exception) {
                        Log.e("PixStreamo_Trace", "LocalServer: Decryption/Streaming FATAL ERROR", e)
                        call.respond(HttpStatusCode.InternalServerError)
                    }
                }
            }
        }
        
        try {
            server?.start(wait = false)
            Log.d("PixStreamo_Trace", "LocalServer: BROADCASTER FULLY ACTIVE AT ${getBaseUrl()}")
        } catch (e: Exception) {
            Log.e("PixStreamo_Trace", "LocalServer: CRITICAL STARTUP FAILURE", e)
        }
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
    }

    fun getBaseUrl(): String = "http://${getIpAddress()}:$port"

    private fun getIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            
            val allIps = mutableListOf<String>()
            interfaces.forEach { iface ->
                iface.inetAddresses.toList().forEach { addr ->
                    if (!addr.isLoopbackAddress && addr is InetAddress && addr.address.size == 4) {
                        allIps.add("${iface.name}:${addr.hostAddress}")
                    }
                }
            }
            Log.d("PixStreamo_Trace", "All Detected IPs: $allIps")

            // 1. Prioritize Wi-Fi (wlan)
            val wifiAddr = interfaces.filter { it.name.lowercase().contains("wlan") }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is InetAddress && it.address.size == 4 }
            
            if (wifiAddr != null) {
                val ip = wifiAddr.hostAddress ?: "127.0.0.1"
                Log.d("PixStreamo_Trace", "Selected Wi-Fi IP: $ip")
                return ip
            }

            // 2. Fallback to any non-p2p address
            val anyAddr = interfaces.filter { !it.name.lowercase().contains("p2p") }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is InetAddress && it.address.size == 4 }
                
            val finalIp = anyAddr?.hostAddress ?: "127.0.0.1"
            Log.d("PixStreamo_Trace", "Selected Fallback IP: $finalIp")
            return finalIp
            
        } catch (e: Exception) {
            Log.e("PixStreamo_Trace", "IP lookup failed", e)
        }
        return "127.0.0.1"
    }
}
