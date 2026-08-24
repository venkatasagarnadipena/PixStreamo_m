/**
 * PixStreamo Shared State ViewModel
 */
package com.example.pixstreamo_m.ui

import android.content.Context
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.lifecycle.ViewModel
import coil.ImageLoader
import com.example.pixstreamo_m.mega.LocalStreamServer
import com.example.pixstreamo_m.mega.MegaImageNode
import com.example.pixstreamo_m.mega.StreamManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

class SharedViewModel : ViewModel() {
    private val _currentNodes = MutableStateFlow<List<MegaImageNode>>(emptyList())
    val currentNodes: StateFlow<List<MegaImageNode>> = _currentNodes

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex

    private val _isSlideshowActive = MutableStateFlow(false)
    val isSlideshowActive: StateFlow<Boolean> = _isSlideshowActive

    private val _isSlideshowPaused = MutableStateFlow(false)
    val isSlideshowPaused: StateFlow<Boolean> = _isSlideshowPaused

    var activeFolderUrl: String? = null
    var gridImageLoader: ImageLoader? = null
    var fullImageLoader: ImageLoader? = null
    
    var localStreamServer: LocalStreamServer? = null
    var streamManager: StreamManager? = null
    
    private var mediaSession: MediaSessionCompat? = null
    private var slideshowJob: Job? = null
    
    fun setNodes(nodes: List<MegaImageNode>) {
        _currentNodes.value = nodes
    }

    fun setCurrentIndex(index: Int, context: Context) {
        _currentIndex.value = index
        castCurrentImage(context)
    }

    fun toggleSlideshow(active: Boolean) {
        _isSlideshowActive.value = active
        if (active) startSlideshowTimer() else stopSlideshowTimer()
    }

    fun togglePause(paused: Boolean) {
        _isSlideshowPaused.value = paused
    }

    private fun startSlideshowTimer() {
        slideshowJob?.cancel()
        slideshowJob = viewModelScope.launch {
            while (true) {
                delay(5.seconds)
                if (!_isSlideshowPaused.value) {
                    val nextIndex = _currentIndex.value + 1
                    if (nextIndex < _currentNodes.value.size) {
                        _currentIndex.value = nextIndex
                        // Casting will be triggered by a collector in the UI or here
                    } else {
                        _isSlideshowActive.value = false
                        break
                    }
                }
            }
        }
    }

    private fun stopSlideshowTimer() {
        slideshowJob?.cancel()
        slideshowJob = null
    }

    private fun castCurrentImage(context: Context) {
        val nodes = _currentNodes.value
        val index = _currentIndex.value
        val folderUrl = activeFolderUrl ?: return
        if (index in nodes.indices) {
            castImage(context, nodes[index], folderUrl)
        }
    }

    fun initMediaSession(context: Context) {
        if (mediaSession != null) return
        
        try {
            mediaSession = MediaSessionCompat(context, "PixStreamo").apply {
                setCallback(object : MediaSessionCompat.Callback() {
                    override fun onSkipToNext() { 
                        val next = _currentIndex.value + 1
                        if (next < _currentNodes.value.size) _currentIndex.value = next
                    }
                    override fun onSkipToPrevious() { 
                        val prev = _currentIndex.value - 1
                        if (prev >= 0) _currentIndex.value = prev
                    }
                    override fun onPlay() { toggleSlideshow(true); togglePause(false) }
                    override fun onPause() { togglePause(true) }
                    override fun onStop() { toggleSlideshow(false) }
                })
                
                val state = PlaybackStateCompat.Builder()
                    .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or 
                               PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                               PlaybackStateCompat.ACTION_STOP)
                    .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1.0f)
                    .build()
                setPlaybackState(state)
                isActive = true
            }
        } catch (e: Exception) {}
    }
    
    fun releaseMediaSession() {
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
    }

    fun castImage(context: Context, node: MegaImageNode, folderUrl: String) {
        val server = localStreamServer ?: return
        val manager = streamManager ?: return
        
        // Show loading screen on TV immediately
        val loadingUrl = "${server.getBaseUrl()}/loading.jpg"
        manager.sendCustomUrl(loadingUrl, "Loading...")
        
        // Then send actual image
        manager.sendImage(context, node, folderUrl, server.getBaseUrl())
    }

    override fun onCleared() {
        super.onCleared()
        stopSlideshowTimer()
        releaseMediaSession()
    }
}
