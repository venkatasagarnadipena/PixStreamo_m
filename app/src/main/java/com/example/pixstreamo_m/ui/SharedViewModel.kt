/**
 * PixStreamo Shared State ViewModel with Strict Folder Lifecycle & 3-Concurrent Queue
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
import com.example.pixstreamo_m.PixStreamoApplication
import com.example.pixstreamo_m.mega.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

class SharedViewModel(application: Application) : AndroidViewModel(application) {
    
    private val TAG = "PixStreamo_Lifecycle"

    // --- Isolated Gallery State ---
    private val _gridNodes = MutableStateFlow<List<MegaImageNode>>(emptyList())
    val gridNodes: StateFlow<List<MegaImageNode>> = _gridNodes

    private val _activeFolderUrlFlow = MutableStateFlow<String?>(null)
    var activeFolderUrl: String? 
        get() = _activeFolderUrlFlow.value
        set(value) { _activeFolderUrlFlow.value = value }

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex

    // --- Slideshow & Preloading ---
    private val _isSlideshowActive = MutableStateFlow(false)
    val isSlideshowActive: StateFlow<Boolean> = _isSlideshowActive

    private val _isSlideshowPaused = MutableStateFlow(false)
    val isSlideshowPaused: StateFlow<Boolean> = _isSlideshowPaused

    private val _isPreparing = MutableStateFlow(false)
    val isPreparing: StateFlow<Boolean> = _isPreparing

    private val _preparedCount = MutableStateFlow(0)
    val preparedCount: StateFlow<Int> = _preparedCount

    // --- UI Lifecycle State ---
    private val _isCastDialogOpen = MutableStateFlow(false)
    val isCastDialogOpen: StateFlow<Boolean> = _isCastDialogOpen

    private val _isCleaningUp = MutableStateFlow(false)
    val isCleaningUp: StateFlow<Boolean> = _isCleaningUp

    // --- Services ---
    var gridImageLoader: ImageLoader? = null
    var fullImageLoader: ImageLoader? = null
    var localStreamServer: LocalStreamServer? = null
    var streamManager: StreamManager? = null
    var megaRepository: MegaRepository? = null

    private val _streamManagerFlow = MutableStateFlow<StreamManager?>(null)
    
    private var slideshowService: SlideshowService? = null
    private var isServiceBound = false
    
    // --- Rolling Queue Core ---
    private var queueJob: Job? = null
    private var cacheStart = 0
    private var cacheEnd = 50

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            slideshowService = (service as SlideshowService.LocalBinder).getService()
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

        // Reactive Casting Observer: Ensures TV updates on index or connect
        viewModelScope.launch {
            _currentIndex.collectLatest { index ->
                if (!_isCleaningUp.value) {
                    triggerCast(index)
                }
            }
        }

        // Rolling Cache Management: Triggers when reaching image 30
        _currentIndex.onEach { index ->
            if (index >= cacheEnd - 20 && !_isCleaningUp.value && _gridNodes.value.isNotEmpty()) {
                manageRollingCache(index)
            }
        }.launchIn(viewModelScope)
    }

    private fun triggerCast(index: Int) {
        val nodes = _gridNodes.value
        val url = activeFolderUrl
        if (url != null && index in nodes.indices) {
            castImage(getApplication(), nodes[index], url)
        }
    }

    /**
     * Wipes current folder state and allows 2s for backend cleanup.
     */
    fun exitFolderCleanup(onComplete: () -> Unit) {
        viewModelScope.launch {
            _isCleaningUp.value = true
            Log.d(TAG, "CLEANUP: Folder Exit. Purging all states.")

            queueJob?.cancelAndJoin()
            slideshowService?.stopSlideshow()
            
            _isSlideshowActive.value = false
            _isPreparing.value = false
            _gridNodes.value = emptyList()
            _currentIndex.value = 0
            cacheStart = 0
            cacheEnd = 50
            
            gridImageLoader = null
            fullImageLoader = null
            
            withContext(Dispatchers.IO) {
                (getApplication() as? PixStreamoApplication)?.cacheManager?.clearAllCache()
            }

            delay(1500) 
            activeFolderUrl = null
            _isCleaningUp.value = false
            onComplete()
        }
    }

    fun startInitialLoad() {
        if (_gridNodes.value.isEmpty() || _isCleaningUp.value) return
        runQueue(0, 50)
    }

    private fun manageRollingCache(currentIndex: Int) {
        val nodes = _gridNodes.value
        val nextEnd = minOf(cacheEnd + 20, nodes.size)
        val nextStart = currentIndex - 10 
        
        if (nextEnd > cacheEnd) {
            Log.d(TAG, "ROLLING: Moving window to [$nextStart, $nextEnd]")
            
            viewModelScope.launch(Dispatchers.IO) {
                nodes.subList(cacheStart, maxOf(cacheStart, nextStart)).forEach { node ->
                    megaRepository?.removeImageFromCache(node.handle, isThumbnail = false)
                }
                cacheStart = nextStart
                cacheEnd = nextEnd
            }
            
            runQueue(cacheEnd - 20, cacheEnd)
        }
    }

    private fun runQueue(start: Int, end: Int) {
        val nodes = _gridNodes.value
        val url = activeFolderUrl ?: return
        val repo = megaRepository ?: return
        val subList = nodes.subList(maxOf(0, start), minOf(end, nodes.size))

        queueJob = viewModelScope.launch {
            val semaphore = Semaphore(3) 
            subList.forEach { node ->
                if (_isCleaningUp.value) return@launch
                
                launch {
                    semaphore.withPermit {
                        if (!repo.isImageCached(node.handle, isThumbnail = false)) {
                            try {
                                repo.decryptImage(node.handle, node.key, url, isThumbnail = false)
                                repo.decryptImage(node.handle, node.key, url, isThumbnail = true, priority = DecryptPriority.LOW)
                            } catch (e: Exception) {}
                        }
                        if (_isPreparing.value) _preparedCount.value += 1
                    }
                }
            }
        }
    }

    fun toggleSlideshow(active: Boolean) {
        if (active) {
            startSlideshowSequence()
        } else {
            _isSlideshowActive.value = false
            _isPreparing.value = false
            slideshowService?.stopSlideshow()
        }
    }

    private fun startSlideshowSequence() {
        val nodes = _gridNodes.value
        val url = activeFolderUrl ?: return
        val repo = megaRepository ?: return
        val targetCount = minOf(nodes.size, 50)

        viewModelScope.launch {
            _isPreparing.value = true
            _preparedCount.value = nodes.take(targetCount).count { repo.isImageCached(it.handle, isThumbnail = false) }
            
            if (_preparedCount.value < targetCount) {
                runQueue(0, 50)
                while (_preparedCount.value < targetCount && !_isCleaningUp.value && _isPreparing.value) {
                    delay(500)
                }
            }
            
            if (_isPreparing.value) {
                _isPreparing.value = false
                _isSlideshowActive.value = true
                triggerCast(_currentIndex.value)
                
                slideshowService?.startSlideshow(10000L) {
                    if (!_isSlideshowPaused.value && _isSlideshowActive.value) {
                        val nextIndex = _currentIndex.value + 1
                        if (nextIndex < nodes.size && nextIndex < 50) {
                            _currentIndex.value = nextIndex
                        } else {
                            toggleSlideshow(false)
                        }
                    }
                }
            }
        }
    }

    fun setNodes(nodes: List<MegaImageNode>) {
        if (!_isCleaningUp.value) _gridNodes.value = nodes
    }

    fun setCurrentIndex(index: Int) {
        if (_currentIndex.value != index && !_isCleaningUp.value) {
            _currentIndex.value = index
        }
    }

    fun setCastDialogOpen(open: Boolean) {
        _isCastDialogOpen.value = open
    }

    fun togglePause() {
        _isSlideshowPaused.value = !_isSlideshowPaused.value
    }

    fun castImage(context: Context, node: MegaImageNode, folderUrl: String) {
        if (_isCleaningUp.value) return
        val server = localStreamServer ?: return
        val manager = streamManager ?: return
        if (manager.isConnected.value) {
            manager.sendImage(context, node, folderUrl, server.getBaseUrl())
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopSlideshow()
        if (isServiceBound) {
            getApplication<Application>().unbindService(serviceConnection)
        }
    }

    private fun stopSlideshow() {
        queueJob?.cancel()
        _isPreparing.value = false
        _isSlideshowActive.value = false
        _isSlideshowPaused.value = false
        slideshowService?.stopSlideshow()
    }
}
