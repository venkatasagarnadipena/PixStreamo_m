/**
 * PixStreamo Main Application Class
 */
package com.example.pixstreamo_m

import android.app.Application
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.example.pixstreamo_m.data.AppDatabase
import com.example.pixstreamo_m.data.PreferenceManager
import com.example.pixstreamo_m.mega.*
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PixStreamoApplication : Application() {

    lateinit var preferenceManager: PreferenceManager
    lateinit var cacheManager: CacheManager
    lateinit var megaRepository: MegaRepository
    lateinit var localStreamServer: LocalStreamServer
    lateinit var database: AppDatabase
    lateinit var streamManager: StreamManager
    
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()

    override fun onCreate() {
        super.onCreate()
        
        // 1. Pre-warm Python Environment
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        // 2. Initialize core persistence
        database = AppDatabase.getDatabase(this)
        preferenceManager = PreferenceManager(this)
        
        // 2. Initialize backend components
        cacheManager = CacheManager(this, preferenceManager)
        megaRepository = MegaRepository(gson, cacheManager)
        localStreamServer = LocalStreamServer(megaRepository)
        
        // 3. Initialize Production Streaming
        streamManager = GoogleCastManager()
        
        // 4. Start Local Broadcaster
        try {
            localStreamServer.start()
        } catch (e: Exception) {
            Log.e("PixStreamo", "Failed to start local broadcaster", e)
        }
        
        // 5. Perform Startup Maintenance
        applicationScope.launch {
            Log.d("PixStreamo", "Application launch: Starting cache cleanup...")
            try {
                cacheManager.clearAllCache()
            } catch (e: Exception) {
                Log.e("PixStreamo", "Cache cleanup failed", e)
            }
        }
    }
}
