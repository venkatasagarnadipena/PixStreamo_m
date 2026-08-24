/**
 * PixStreamo Shared State ViewModel
 */
package com.example.pixstreamo_m.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import com.example.pixstreamo_m.mega.LocalStreamServer
import com.example.pixstreamo_m.mega.MegaImageNode
import com.example.pixstreamo_m.mega.StreamManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

class SharedViewModel(application: Application) : AndroidViewModel(application) {
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
    
    private var slideshowJob: Job? = null
    
    init {
        // Observe index changes and trigger cast automatically if connected
        viewModelScope.launch {
            _currentIndex.collectLatest { index ->
                val nodes = _currentNodes.value
                val folderUrl = activeFolderUrl
                if (folderUrl != null && index in nodes.indices) {
                    castImage(getApplication(), nodes[index], folderUrl)
                }
            }
        }
        
        // Deep Dive Fix: Re-trigger cast when connection is established
        viewModelScope.launch {
            streamManager?.isConnected?.collectLatest { connected ->
                if (connected) {
                    val nodes = _currentNodes.value
                    val index = _currentIndex.value
                    val folderUrl = activeFolderUrl
                    if (folderUrl != null && index in nodes.indices) {
                        castImage(getApplication(), nodes[index], folderUrl)
                    }
                }
            }
        }
    }

    fun setNodes(nodes: List<MegaImageNode>) {
        _currentNodes.value = nodes
    }

    fun setCurrentIndex(index: Int) {
        _currentIndex.value = index
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
                delay(10.seconds)
                if (!_isSlideshowPaused.value && _isSlideshowActive.value) {
                    val nextIndex = _currentIndex.value + 1
                    if (nextIndex < _currentNodes.value.size) {
                        _currentIndex.value = nextIndex
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

    fun castImage(context: Context, node: MegaImageNode, folderUrl: String) {
        val server = localStreamServer ?: return
        val manager = streamManager ?: return
        
        // Only cast if actually connected to a device
        if (manager.isConnected.value) {
            // Show loading screen on TV immediately
            val loadingUrl = "${server.getBaseUrl()}/loading.jpg"
            manager.sendCustomUrl(loadingUrl, "Loading...")
            
            // Then send actual image
            manager.sendImage(context, node, folderUrl, server.getBaseUrl())
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopSlideshowTimer()
    }
}
