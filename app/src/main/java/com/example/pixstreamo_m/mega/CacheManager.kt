/**
 * PixStreamo Intelligent Cache Manager
 *
 * Handles the logic for where decrypted images are stored (Internal, SD Card, or RAM).
 * Support for Storage Access Framework (SAF) to allow user-selected custom folders.
 * Includes fallback logic to internal storage if external media is removed.
 */
package com.example.pixstreamo_m.mega

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.example.pixstreamo_m.data.PreferenceManager
import java.io.File

class CacheManager(context: Context, private val preferenceManager: PreferenceManager) {

    private val appContext = context.applicationContext

    enum class StorageMode {
        AUTO,      // Prefer External -> Internal
        INTERNAL,  // Force Internal
        EXTERNAL,  // Force External
        CUSTOM,    // User selected folder via SAF
        RAM        // No disk cache
    }

    /**
     * Resolves the current active cache directory File (Internal/External).
     * Returns null if using CUSTOM or RAM modes.
     */
    fun getCacheDirectory(): File? {
        val mode = getEffectiveMode()
        if (mode == StorageMode.CUSTOM || mode == StorageMode.RAM) return null

        return when (mode) {
            StorageMode.INTERNAL -> getInternalCache()
            StorageMode.EXTERNAL -> getExternalCache() ?: getInternalCache()
            else -> getExternalCache() ?: getInternalCache()
        }
    }

    fun getEffectiveMode(): StorageMode {
        return try {
            StorageMode.valueOf(preferenceManager.getCacheMode())
        } catch (e: Exception) {
            StorageMode.AUTO
        }
    }

    fun getCustomCacheUri(): Uri? = preferenceManager.getCacheUri()?.let { Uri.parse(it) }

    private fun getInternalCache(): File {
        val dir = File(appContext.cacheDir, "mega_image_cache")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getExternalCache(): File? {
        val externalDirs = ContextCompat.getExternalCacheDirs(appContext)
        val externalDir = externalDirs.firstOrNull { it != null && Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }
        
        return externalDir?.let {
            val dir = File(it, "mega_image_cache")
            if (!dir.exists()) dir.mkdirs()
            dir
        }
    }
    
    fun getCacheSize(): Long {
        val mode = getEffectiveMode()
        if (mode == StorageMode.CUSTOM) {
            val uri = getCustomCacheUri() ?: return 0L
            val root = DocumentFile.fromTreeUri(appContext, uri) ?: return 0L
            return getDocumentDirSize(root)
        }
        
        val dir = getCacheDirectory() ?: return 0L
        return getDirSize(dir)
    }
    
    private fun getDirSize(dir: File): Long {
        var size = 0L
        dir.listFiles()?.forEach {
            size += if (it.isDirectory) getDirSize(it) else it.length()
        }
        return size
    }

    private fun getDocumentDirSize(dir: DocumentFile): Long {
        var size = 0L
        val files = dir.listFiles()
        for (i in files.indices) {
            val file = files[i]
            size += if (file.isDirectory) getDocumentDirSize(file) else file.length()
        }
        return size
    }
    
    /**
     * Wipes all image cache files from all known storage locations.
     */
    fun clearAllCache() {
        getInternalCache().deleteRecursively()
        getExternalCache()?.deleteRecursively()
        
        getCustomCacheUri()?.let { uri ->
            try {
                val root = DocumentFile.fromTreeUri(appContext, uri)
                root?.listFiles()?.forEach { it.delete() }
            } catch (e: Exception) {
                Log.e("CacheManager", "Failed to clear custom cache", e)
            }
        }
    }

    fun isCached(handle: String): Boolean {
        val mode = getEffectiveMode()
        if (mode == StorageMode.CUSTOM) {
            getCustomCacheUri()?.let { uri ->
                val root = DocumentFile.fromTreeUri(appContext, uri)
                val file = root?.findFile(handle)
                return file?.exists() == true && file.length() > 0
            }
        }
        val file = getCacheDirectory()?.let { File(it, handle) }
        return file?.exists() == true && file.length() > 0
    }

    fun getFromCache(handle: String): ByteArray? {
        val mode = getEffectiveMode()
        
        if (mode == StorageMode.CUSTOM) {
            getCustomCacheUri()?.let { uri ->
                try {
                    val root = DocumentFile.fromTreeUri(appContext, uri)
                    val file = root?.findFile(handle)
                    if (file?.exists() == true && file.length() > 0) {
                        return appContext.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
                    }
                } catch (e: Exception) {
                    Log.e("CacheManager", "Error reading custom cache", e)
                }
            }
        }

        val file = getCacheDirectory()?.let { File(it, handle) }
        if (file?.exists() == true && file.length() > 0) {
            return file.readBytes()
        }

        return null
    }

    fun saveToCache(handle: String, bytes: ByteArray) {
        val mode = getEffectiveMode()
        if (mode == StorageMode.RAM) return

        if (mode == StorageMode.CUSTOM) {
            getCustomCacheUri()?.let { uri ->
                try {
                    val root = DocumentFile.fromTreeUri(appContext, uri) ?: return@let
                    val existing = root.findFile(handle)
                    existing?.delete()
                    val file = root.createFile("application/octet-stream", handle)
                    file?.let { doc ->
                        appContext.contentResolver.openOutputStream(doc.uri)?.use { os ->
                            os.write(bytes)
                        }
                    }
                    return
                } catch (e: Exception) {
                    Log.e("CacheManager", "Failed to save to custom cache", e)
                }
            }
        }

        val dir = getCacheDirectory() ?: getInternalCache()
        val file = File(dir, handle)
        file.writeBytes(bytes)
    }

    fun removeFromCache(handle: String) {
        val mode = getEffectiveMode()
        if (mode == StorageMode.CUSTOM) {
            getCustomCacheUri()?.let { uri ->
                val root = DocumentFile.fromTreeUri(appContext, uri)
                root?.findFile(handle)?.delete()
            }
        }
        val file = getCacheDirectory()?.let { File(it, handle) }
        if (file?.exists() == true) file.delete()
        
        val internalFile = File(getInternalCache(), handle)
        if (internalFile.exists()) internalFile.delete()
    }
}
