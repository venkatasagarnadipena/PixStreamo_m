/**
 * PixStreamo Streaming Abstraction Layer
 */
package com.example.pixstreamo_m.mega

import android.content.Context
import android.util.Log
import androidx.mediarouter.media.MediaControlIntent
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
    
    // Default Media Receiver ID
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
        try {
            castContext = CastContext.getSharedInstance(context)
            mediaRouter = MediaRouter.getInstance(context)
            
            castContext?.sessionManager?.addSessionManagerListener(object : SessionManagerListener<CastSession> {
                override fun onSessionStarted(s: CastSession, id: String) { _isConnected.value = true }
                override fun onSessionEnded(s: CastSession, e: Int) { _isConnected.value = false }
                override fun onSessionResumed(s: CastSession, was: Boolean) { _isConnected.value = true }
                override fun onSessionResumeFailed(s: CastSession, e: Int) { _isConnected.value = false }
                override fun onSessionStarting(s: CastSession) {}
                override fun onSessionStartFailed(s: CastSession, e: Int) {}
                override fun onSessionEnding(s: CastSession) {}
                override fun onSessionResuming(s: CastSession, id: String) {}
                override fun onSessionSuspended(s: CastSession, reason: Int) {}
            }, CastSession::class.java)
            
            _isConnected.value = castContext?.sessionManager?.currentCastSession?.isConnected ?: false
        } catch (e: Exception) {
            Log.e("PixStreamo_Trace", "Manual Cast Manager Init Failed", e)
        }
    }

    override fun startDiscovery() {
        Log.d("PixStreamo_Trace", "Cast: Starting device discovery")
        mediaRouter?.addCallback(selector, routerCallback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
    }

    override fun stopDiscovery() {
        Log.d("PixStreamo_Trace", "Cast: Stopping device discovery")
        mediaRouter?.removeCallback(routerCallback)
    }

    override fun selectRoute(route: MediaRouter.RouteInfo) {
        Log.d("PixStreamo_Trace", "Cast: Selecting route: ${route.name}")
        route.select()
    }
    
    override fun disconnect() {
        castContext?.sessionManager?.endCurrentSession(true)
    }

    override fun sendImage(context: Context, node: MegaImageNode, folderUrl: String, baseUrl: String) {
        val session = castContext?.sessionManager?.currentCastSession
        if (session == null || !session.isConnected) return

        try {
            val localUrl = "$baseUrl/stream?h=${node.handle}&k=${node.key}&f=${URLEncoder.encode(folderUrl, "UTF-8")}"
            val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_PHOTO)
            metadata.putString(MediaMetadata.KEY_TITLE, node.name)
            
            val mediaInfo = MediaInfo.Builder(localUrl)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType("image/jpeg")
                .setMetadata(metadata)
                .build()
                
            session.remoteMediaClient?.load(mediaInfo)
        } catch (e: Exception) {
            Log.e("PixStreamo_Trace", "Manual Cast Send Failed", e)
        }
    }
}
