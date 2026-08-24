/**
 * PixStreamo Shared State ViewModel
 */
package com.example.pixstreamo_m.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import com.example.pixstreamo_m.mega.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class SharedViewModel(application: Application) : AndroidViewModel(application) {
    
    private val TAG = "PixStreamo_Debug"

    // Core Gallery Data
    private val _gridNodes = MutableStateFlow<List<MegaImageNode>>(emptyList())
    val gridNodes: StateFlow<List<MegaImageNode>> = _gridNodes

    private val _activeFolderUrlFlow = MutableStateFlow<String?>(null)
    var activeFolderUrl: String? 
        get() = _activeFolderUrlFlow.value
        set(value) { _activeFolderUrlFlow.value = value }

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex

    // Slideshow States
    private val _isSlideshowActive = MutableStateFlow(false)
    val isSlideshowActive: StateFlow<Boolean> = _isSlideshowActive

    private val _isSlideshowPaused = MutableStateFlow(false)
    val isSlideshowPaused: StateFlow<Boolean> = _isSlideshowPaused

    private val _isPreparing = MutableStateFlow(false)
    val isPreparing: StateFlow<Boolean> = _isPreparing

    private val _preparedCount = MutableStateFlow(0)
    val preparedCount: StateFlow<Int> = _preparedCount

    var gridImageLoader: ImageLoader? = null
    var fullImageLoader: ImageLoader? = null

    var localStreamServer: LocalStreamServer? = null
    var megaRepository: MegaRepository? = null

    // Use a flow to track streamManager changes
    private val _streamManagerFlow = MutableStateFlow<StreamManager?>(null)
    var streamManager: StreamManager?
        get() = _streamManagerFlow.value
        set(value) { _streamManagerFlow.value = value }
    
    private var slideshowService: SlideshowService? = null
    private var isServiceBound = false
    private var preloadJob: Job? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as SlideshowService.LocalBinder
            slideshowService = binder.getService()
            isServiceBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            slideshowService = null
            isServiceBound = false
        }
    }

    init {
        val intent = Intent(application, SlideshowService::class.java)
        application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Index change -> Cast command
        viewModelScope.launch {
            _currentIndex.collectLatest { index ->
                triggerCast(index)
            }
        }

        // Connection established -> Cast current image immediately
        viewModelScope.launch {
            _streamManagerFlow.filterNotNull().flatMapLatest { it.isConnected }
                .collectLatest { connected ->
                    if (connected) {
                        triggerCast(_currentIndex.value)
                    }
                }
        }
    }

    private fun triggerCast(index: Int) {
        val nodes = _gridNodes.value
        val url = activeFolderUrl
        if (url != null && index in nodes.indices) {
            castImage(getApplication(), nodes[index], url)
        }
    }

    fun setNodes(nodes: List<MegaImageNode>) {
        if (nodes.isNotEmpty() || _gridNodes.value.isEmpty()) {
            _gridNodes.value = nodes
        }
    }

    fun setCurrentIndex(index: Int) {
        if (_currentIndex.value != index) {
            _currentIndex.value = index
        }
    }

    fun toggleSlideshow(active: Boolean) {
        if (active) {
            startPreloadingSequence()
        } else {
            stopSlideshowInternal()
        }
    }
    
    fun togglePause() {
        _isSlideshowPaused.value = !_isSlideshowPaused.value
    }

    private fun startPreloadingSequence() {
        val nodes = _gridNodes.value
        val url = activeFolderUrl ?: return
        val repo = megaRepository ?: return
        val targetCount = minOf(nodes.size, 50)

        preloadJob?.cancel()
        preloadJob = viewModelScope.launch {
            _isPreparing.value = true
            _preparedCount.value = 0
            
            nodes.take(targetCount).forEach { node ->
                if (!repo.isImageCached(node.handle, isThumbnail = false)) {
                    try {
                        withContext(kotlinx.coroutines.Dispatchers.IO) {
                            withTimeout(30000L) {
                                while (repo.isUrgentWaiting()) { delay(500) }
                                repo.decryptImage(node.handle, node.key, url, isThumbnail = false)
                                repo.decryptImage(node.handle, node.key, url, isThumbnail = true)
                            }
                        }
                    } catch (e: Exception) {}
                }
                _preparedCount.value += 1
            }
            
            _isPreparing.value = false
            _isSlideshowActive.value = true
            startServiceTimer()
        }
    }

    private fun stopSlideshowInternal() {
        preloadJob?.cancel()
        _isPreparing.value = false
        _isSlideshowActive.value = false
        _isSlideshowPaused.value = false
        slideshowService?.stopSlideshow()
    }

    private fun startServiceTimer() {
        slideshowService?.startSlideshow(10000L) {
            if (!_isSlideshowPaused.value && _isSlideshowActive.value) {
                val nextIndex = _currentIndex.value + 1
                if (nextIndex < _gridNodes.value.size && nextIndex < 50) {
                    _currentIndex.value = nextIndex
                } else {
                    stopSlideshowInternal()
                }
            }
        }
    }

    fun castImage(context: Context, node: MegaImageNode, folderUrl: String) {
        val server = localStreamServer ?: return
        val manager = streamManager ?: return
        if (manager.isConnected.value) {
            manager.sendImage(context, node, folderUrl, server.getBaseUrl())
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopSlideshowInternal()
        if (isServiceBound) {
            getApplication<Application>().unbindService(serviceConnection)
        }
    }
}
