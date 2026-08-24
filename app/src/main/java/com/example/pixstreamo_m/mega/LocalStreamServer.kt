/**
 * PixStreamo Local Streaming Broadcaster
 */
package com.example.pixstreamo_m.mega

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.NetworkInterface

class LocalStreamServer(private val repository: MegaRepository) {

    private var server: CIOApplicationEngine? = null
    private val port = 8181 

    fun start() {
        if (server != null) return

        server = embeddedServer(CIO, port = port) {
            install(CORS) {
                anyHost()
                allowHeader(HttpHeaders.ContentType)
            }

            routing {
                get("/ping") {
                    call.respondText("ALIVE")
                }

                get("/loading.jpg") {
                    val bytes = createLoadingImage()
                    call.respondBytes(bytes, ContentType.Image.JPEG)
                }

                get("/stream") {
                    val handle = call.request.queryParameters["h"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val key = call.request.queryParameters["k"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val folderUrl = call.request.queryParameters["f"] ?: return@get call.respond(HttpStatusCode.BadRequest)

                    try {
                        val bytes = withContext(Dispatchers.IO) {
                            repository.decryptImage(handle, key, folderUrl, isThumbnail = false)
                        }

                        if (bytes.isNotEmpty()) {
                            call.response.header(HttpHeaders.ContentLength, bytes.size.toString())
                            call.respondBytes(bytes, ContentType.Image.JPEG)
                        } else {
                            call.respond(HttpStatusCode.NotFound)
                        }
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError)
                    }
                }
            }
        }
        
        try {
            server?.start(wait = false)
        } catch (e: Exception) {
            Log.e("PixStreamo", "Server Start Failed", e)
        }
    }

    private fun createLoadingImage(): ByteArray {
        val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        
        val paint = Paint().apply {
            color = Color.WHITE
            textSize = 60f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        
        canvas.drawText("PixStreamo: Loading Image...", 640f, 360f, paint)
        
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
        return stream.toByteArray()
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
    }

    fun getBaseUrl(): String = "http://${getIpAddress()}:$port"

    private fun getIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            val wifiAddr = interfaces.filter { it.name.lowercase().contains("wlan") }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is InetAddress && it.address.size == 4 }
            
            if (wifiAddr != null) return wifiAddr.hostAddress ?: "127.0.0.1"

            val anyAddr = interfaces.filter { !it.name.lowercase().contains("p2p") }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is InetAddress && it.address.size == 4 }
                
            return anyAddr?.hostAddress ?: "127.0.0.1"
        } catch (e: Exception) {
            return "127.0.0.1"
        }
    }
}
