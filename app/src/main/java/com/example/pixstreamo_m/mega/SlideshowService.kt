/**
 * PixStreamo Foreground Service for Background Slideshow
 */
package com.example.pixstreamo_m.mega

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.pixstreamo_m.MainActivity
import com.example.pixstreamo_m.R
import kotlinx.coroutines.*

class SlideshowService : Service() {

    private val TAG = "PixStreamo_Debug"
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob + CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "SlideshowService: Uncaught Exception", e)
    })
    
    private var slideshowTimer: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    
    private val CHANNEL_ID = "slideshow_channel"
    private val NOTIFICATION_ID = 101

    inner class LocalBinder : Binder() {
        fun getService(): SlideshowService = this@SlideshowService
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "SlideshowService: onBind")
        return LocalBinder()
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SlideshowService: onCreate")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "SlideshowService: onStartCommand action=$action")
        if (action == "STOP") {
            stopSlideshow()
            stopSelf()
        }
        return START_STICKY
    }

    fun startSlideshow(intervalMs: Long, onTick: () -> Unit) {
        Log.d(TAG, "SlideshowService: Starting timer loop interval=$intervalMs")
        showNotification()
        acquireWakeLock()
        
        slideshowTimer?.cancel()
        slideshowTimer = serviceScope.launch {
            while (isActive) {
                delay(intervalMs)
                Log.d(TAG, "SlideshowService: Timer TICK")
                try {
                    onTick()
                } catch (e: Exception) {
                    Log.e(TAG, "SlideshowService: Error during onTick callback", e)
                }
            }
        }
    }

    fun stopSlideshow() {
        Log.d(TAG, "SlideshowService: Stopping slideshow")
        slideshowTimer?.cancel()
        slideshowTimer = null
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            Log.d(TAG, "SlideshowService: Acquiring WakeLock")
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PixStreamo::SlideshowLock")
            wakeLock?.acquire(3600000L) 
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            Log.d(TAG, "SlideshowService: Releasing WakeLock")
            wakeLock?.release()
        }
        wakeLock = null
    }

    private fun showNotification() {
        val stopIntent = Intent(this, SlideshowService::class.java).apply { action = "STOP" }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        
        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PixStreamo Slideshow")
            .setContentText("Casting gallery to TV...")
            .setSmallIcon(R.drawable.ic_play)
            .setContentIntent(mainPendingIntent)
            .addAction(0, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "SlideshowService: startForeground success")
        } catch (e: Exception) {
            Log.e(TAG, "SlideshowService: Failed to startForeground", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Slideshow Service", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "SlideshowService: onDestroy")
        super.onDestroy()
        releaseWakeLock()
        serviceJob.cancel()
    }
}
