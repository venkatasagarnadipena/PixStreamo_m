/**
 * PixStreamo Streaming Abstraction Layer
 */
package com.example.pixstreamo_m.mega

import android.content.Context
import android.util.Log
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.URLEncoder

interface StreamManager {
    val isConnected: StateFlow<Boolean>
    val discoveredRoutes: StateFlow<List<MediaRouter.RouteInfo>>
    fun initialize(context: Context)
    fun startDiscovery()
    fun stopDiscovery()
    fun selectRoute(route: MediaRouter.RouteInfo)
    fun disconnect()
    fun sendImage(context: Context, node: MegaImageNode, folderUrl: String, baseUrl: String)
}

class GoogleCastManager : StreamManager {
    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected
    
    private val _discoveredRoutes = MutableStateFlow<List<MediaRouter.RouteInfo>>(emptyList())
    override val discoveredRoutes: StateFlow<List<MediaRouter.RouteInfo>> = _discoveredRoutes
    
    private var castContext: CastContext? = null
    private var mediaRouter: MediaRouter? = null
    
    private val selector = MediaRouteSelector.Builder()
        .addControlCategory(CastMediaControlIntent.categoryForCast("CC1AD845"))
        .build()

    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) { updateRoutes() }
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) { updateRoutes() }
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) { updateRoutes() }
        
        private fun updateRoutes() {
            mediaRouter?.let { router ->
                _discoveredRoutes.value = router.routes.filter { 
                    it.matchesSelector(selector) && !it.isDefault && it.isEnabled
                }
            }
        }
    }

    override fun initialize(context: Context) {
        Log.d("PixStreamo_Trace", "CastManager: initialize")
        try {
            castContext = CastContext.getSharedInstance(context)
            mediaRouter = MediaRouter.getInstance(context)
            
            castContext?.sessionManager?.addSessionManagerListener(object : SessionManagerListener<CastSession> {
                override fun onSessionStarted(s: CastSession, id: String) { 
                    Log.d("PixStreamo_Trace", "CastManager: Session Started. Receiver ID: $id")
                    _isConnected.value = true 
                }
                override fun onSessionEnded(s: CastSession, e: Int) { 
                    Log.d("PixStreamo_Trace", "CastManager: Session Ended. Error code: $e")
                    _isConnected.value = false 
                }
                override fun onSessionResumed(s: CastSession, was: Boolean) { 
                    Log.d("PixStreamo_Trace", "CastManager: Session Resumed")
                    _isConnected.value = true 
                }
                override fun onSessionResumeFailed(s: CastSession, e: Int) { 
                    Log.d("PixStreamo_Trace", "CastManager: Session Resume Failed: $e")
                    _isConnected.value = false 
                }
                override fun onSessionStarting(s: CastSession) {
                    Log.d("PixStreamo_Trace", "CastManager: Session Starting...")
                }
                override fun onSessionStartFailed(s: CastSession, e: Int) {
                    Log.e("PixStreamo_Trace", "CastManager: Session Start Failed: $e")
                }
                override fun onSessionEnding(s: CastSession) {
                    Log.d("PixStreamo_Trace", "CastManager: Session Ending...")
                }
                override fun onSessionResuming(s: CastSession, id: String) {}
                override fun onSessionSuspended(s: CastSession, reason: Int) { Log.w("PixStreamo_Trace", "Cast: Suspended $reason") }
            }, CastSession::class.java)
            
            _isConnected.value = castContext?.sessionManager?.currentCastSession?.isConnected ?: false
        } catch (e: Exception) {
            Log.e("PixStreamo_Trace", "CastManager Init Failed", e)
        }
    }

    override fun startDiscovery() {
        mediaRouter?.addCallback(selector, routerCallback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
    }

    override fun stopDiscovery() {
        mediaRouter?.removeCallback(routerCallback)
    }

    override fun selectRoute(route: MediaRouter.RouteInfo) {
        route.select()
    }
    
    override fun disconnect() {
        castContext?.sessionManager?.endCurrentSession(true)
    }

    override fun sendImage(context: Context, node: MegaImageNode, folderUrl: String, baseUrl: String) {
        val session = castContext?.sessionManager?.currentCastSession
        if (session == null) {
            Log.e("PixStreamo_Trace", "CastManager: [SEND] ERROR - currentCastSession is NULL")
            return
        }
        if (!session.isConnected) {
            Log.e("PixStreamo_Trace", "CastManager: [SEND] ERROR - Session not connected.")
            return
        }

        val remoteClient = session.remoteMediaClient
        if (remoteClient == null) {
            Log.e("PixStreamo_Trace", "CastManager: [SEND] ERROR - remoteMediaClient is NULL")
            return
        }

        Log.d("PixStreamo_Trace", "CastManager: [SEND] Preparing ${node.name}")
        try {
            val encH = URLEncoder.encode(node.handle, "UTF-8")
            val encK = URLEncoder.encode(node.key, "UTF-8")
            val encF = URLEncoder.encode(folderUrl, "UTF-8")
            
            val localUrl = "$baseUrl/stream?h=$encH&k=$encK&f=$encF"
            Log.d("PixStreamo_Trace", "CastManager: [SEND] Final URL being sent to TV: $localUrl")
            
            val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_PHOTO)
            metadata.putString(MediaMetadata.KEY_TITLE, node.name)
            
            val mediaInfo = MediaInfo.Builder(localUrl)
                .setStreamType(MediaInfo.STREAM_TYPE_NONE)
                .setContentType("image/jpeg")
                .setMetadata(metadata)
                .build()
                
            Log.d("PixStreamo_Trace", "CastManager: [SEND] Triggering load() call...")
            remoteClient.load(mediaInfo).setResultCallback { result ->
                if (result.status.isSuccess) {
                    Log.d("PixStreamo_Trace", "CastManager: [SEND] TV ACCEPTED the command.")
                } else {
                    Log.e("PixStreamo_Trace", "CastManager: [SEND] TV REJECTED command. Status: ${result.status.statusMessage} (${result.status.statusCode})")
                }
            }
        } catch (e: Exception) {
            Log.e("PixStreamo_Trace", "CastManager: [SEND] CRITICAL EXCEPTION", e)
        }
    }
}
