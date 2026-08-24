/**
 * PixStreamo MEGA Data Repository
 */
package com.example.pixstreamo_m.mega

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.chaquo.python.Python
import com.example.pixstreamo_m.data.FolderEntity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

enum class DecryptPriority {
    URGENT,   // TV Stream / Current Image
    NORMAL,   // Manual Swipe
    LOW       // Grid Thumbnails / Pre-buffering
}

class MegaRepository(private val gson: Gson, private val cacheManager: CacheManager) {

    private val engine by lazy { Python.getInstance().getModule("mega_engine") }
    
    // The Python Lock: Only one thread can talk to Python at a time.
    private val pythonLock = Mutex()
    
    // Tracking how many high-priority tasks are waiting to help low-priority tasks yield.
    private val urgentWaitingCount = AtomicInteger(0)

    fun fetchConfig(url: String, destPath: String): List<FolderEntity> {
        val body = try {
            val pyObj = engine.callAttr("fetch_config", url, destPath)
            pyObj?.toString()?.trim() ?: ""
        } catch (e: Exception) {
            Log.e("PixStreamo_Trace", "fetch_config Python Crash", e)
            throw Exception("Python Error: ${e.message}")
        }
        
        val folderPairs = mutableListOf<FolderEntity>()
        if (body.isEmpty()) return folderPairs
        
        try {
            if (body.startsWith("[")) {
                val jsonArray = JSONArray(body)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val name = obj.optString("folder", obj.optString("name", "Folder $i"))
                    folderPairs.add(FolderEntity(name = name, url = obj.getString("url"), sourceUrl = url))
                }
            } else if (body.startsWith("{")) {
                val jsonObj = JSONObject(body)
                if (jsonObj.has("Folders")) {
                    val array = jsonObj.getJSONArray("Folders")
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        folderPairs.add(FolderEntity(name = obj.optString("name", "Unknown"), url = obj.getString("url"), sourceUrl = url))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("PixStreamo_Trace", "JSON Parsing failed for config", e)
        }
        return folderPairs
    }

    suspend fun getFolderNodes(url: String): List<MegaImageNode> = withContext(Dispatchers.IO) {
        try {
            withTimeout(30000L) {
                pythonLock.withLock {
                    val pyObj = engine.callAttr("get_folder_nodes", url)
                    val jsonStr = pyObj?.toString() ?: "[]"
                    val type = object : TypeToken<List<MegaImageNode>>() {}.type
                    gson.fromJson(jsonStr, type)
                }
            }
        } catch (e: Exception) {
            Log.e("PixStreamo_Trace", "get_folder_nodes failed", e)
            emptyList()
        }
    }

    fun isImageCached(handle: String, isThumbnail: Boolean): Boolean {
        val cacheKey = if (isThumbnail) "thumb_$handle" else "full_$handle"
        return cacheManager.isCached(cacheKey)
    }

    fun removeImageFromCache(handle: String, isThumbnail: Boolean) {
        val cacheKey = if (isThumbnail) "thumb_$handle" else "full_$handle"
        cacheManager.removeFromCache(cacheKey)
    }

    /**
     * Decrypts an image with Priority-Aware locking.
     * Urgent tasks (TV/Viewer) can jump ahead of background tasks.
     */
    suspend fun decryptImage(
        handle: String, 
        nodeKey: String, 
        folderUrl: String, 
        isThumbnail: Boolean,
        priority: DecryptPriority = DecryptPriority.NORMAL
    ): ByteArray = withContext(Dispatchers.IO) {
        val cacheKey = if (isThumbnail) "thumb_$handle" else "full_$handle"
        
        val cached = cacheManager.getFromCache(cacheKey)
        if (cached != null) return@withContext cached

        try {
            val rawBytes = withTimeout(60000L) {
                pythonLock.withLock {
                    val secondCheck = cacheManager.getFromCache(cacheKey)
                    if (secondCheck != null) return@withLock secondCheck
                    
                    val pyBytes = engine.callAttr("decrypt_image_bytes", handle, nodeKey, folderUrl)
                    pyBytes.toJava(ByteArray::class.java)
                }
            }

            if (rawBytes.isEmpty()) return@withContext ByteArray(0)

            // OPTIMIZATION: Only process thumbnails. Full images stay raw to save CPU/Battery.
            val finalBytes = if (isThumbnail) {
                createThumbnail(rawBytes)
            } else {
                rawBytes
            }
            
            cacheManager.saveToCache(cacheKey, finalBytes)
            finalBytes
        } catch (e: Exception) {
            ByteArray(0)
        }
    }

    private fun createThumbnail(data: ByteArray): ByteArray {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, options)
            
            var sampleSize = 1
            // Target ~400px for thumbnails
            while (options.outWidth / (sampleSize * 2) >= 400) { sampleSize *= 2 }
            
            val thumbOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565 
            }
            
            val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, thumbOptions)
            val outputStream = ByteArrayOutputStream()
            // JPEG is faster to compress than WEBP on many devices
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
            val result = outputStream.toByteArray()
            bitmap?.recycle()
            result
        } catch (e: Exception) {
            data 
        }
    }
}

data class MegaImageNode(
    val name: String,
    val handle: String,
    val key: String
)
