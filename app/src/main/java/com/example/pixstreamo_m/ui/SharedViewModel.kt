/**
 * PixStreamo Shared State ViewModel
 */
package com.example.pixstreamo_m.ui

import android.content.Context
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.lifecycle.ViewModel
import coil.ImageLoader
import com.example.pixstreamo_m.mega.LocalStreamServer
import com.example.pixstreamo_m.mega.MegaImageNode
import com.example.pixstreamo_m.mega.StreamManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SharedViewModel : ViewModel() {
    private val _currentNodes = MutableStateFlow<List<MegaImageNode>>(emptyList())
    val currentNodes: StateFlow<List<MegaImageNode>> = _currentNodes

    var activeFolderUrl: String? = null
    var gridImageLoader: ImageLoader? = null
    var fullImageLoader: ImageLoader? = null
    
    var localStreamServer: LocalStreamServer? = null
    var streamManager: StreamManager? = null
    
    private var mediaSession: MediaSessionCompat? = null
    
    fun initMediaSession(context: Context, onCommand: (String) -> Unit) {
        if (mediaSession != null) return
        
        Log.d("PixStreamo_Trace", "SharedViewModel: Initializing MediaSession for TV Remote")
        try {
            mediaSession = MediaSessionCompat(context, "PixStreamo").apply {
                setCallback(object : MediaSessionCompat.Callback() {
                    override fun onSkipToNext() { 
                        Log.d("PixStreamo_Trace", "Remote: NEXT pressed on TV Remote")
                        onCommand("NEXT") 
                    }
                    override fun onSkipToPrevious() { 
                        Log.d("PixStreamo_Trace", "Remote: PREV pressed on TV Remote")
                        onCommand("PREV") 
                    }
                    override fun onPlay() { 
                        Log.d("PixStreamo_Trace", "Remote: PLAY pressed on TV Remote")
                        onCommand("PLAY") 
                    }
                    override fun onPause() { 
                        Log.d("PixStreamo_Trace", "Remote: PAUSE pressed on TV Remote")
                        onCommand("PAUSE") 
                    }
                    override fun onStop() { 
                        Log.d("PixStreamo_Trace", "Remote: STOP pressed on TV Remote")
                        onCommand("STOP") 
                    }
                })
                
                val state = PlaybackStateCompat.Builder()
                    .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or 
                               PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
                    .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1.0f)
                    .build()
                setPlaybackState(state)
                isActive = true
            }
            Log.d("PixStreamo_Trace", "SharedViewModel: MediaSession Active")
        } catch (e: Exception) {
            Log.e("PixStreamo_Trace", "SharedViewModel: Failed to init MediaSession", e)
        }
    }
    
    fun releaseMediaSession() {
        Log.d("PixStreamo_Trace", "SharedViewModel: Releasing MediaSession")
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
    }

    fun castImage(context: Context, node: MegaImageNode, folderUrl: String) {
        val server = localStreamServer ?: return
        val manager = streamManager ?: return
        manager.sendImage(context, node, folderUrl, server.getBaseUrl())
    }

    fun setNodes(nodes: List<MegaImageNode>) {
        _currentNodes.value = nodes
    }
    
    override fun onCleared() {
        super.onCleared()
        releaseMediaSession()
    }
}
