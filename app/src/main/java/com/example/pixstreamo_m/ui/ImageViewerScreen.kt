/**
 * PixStreamo Full-Screen Image Viewer
 */
package com.example.pixstreamo_m.ui

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.ImageLoader
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.memory.MemoryCache
import com.example.pixstreamo_m.mega.MegaFetcher
import com.example.pixstreamo_m.mega.MegaImageNode
import com.example.pixstreamo_m.mega.MegaRepository
import com.example.pixstreamo_m.mega.StreamManager
import com.example.pixstreamo_m.mega.DecryptPriority
import kotlinx.coroutines.*
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    initialIndex: Int,
    nodes: List<MegaImageNode>,
    folderUrl: String,
    megaRepository: MegaRepository,
    streamManager: StreamManager,
    onBackClick: () -> Unit,
    sharedViewModel: SharedViewModel = viewModel()
) {
    var isSlideshowActive by rememberSaveable { mutableStateOf(false) }
    var isSlideshowPaused by rememberSaveable { mutableStateOf(false) }
    var areControlsVisible by rememberSaveable { mutableStateOf(true) }
    
    var isPreparing by rememberSaveable { mutableStateOf(false) }
    var preparedCount by rememberSaveable { mutableIntStateOf(0) }
    val targetPrepareCount = remember { minOf(nodes.size, 50) }
    
    var cacheStart by rememberSaveable { mutableIntStateOf(0) }
    var cacheEnd by rememberSaveable { mutableIntStateOf(targetPrepareCount) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val pagerState = rememberPagerState(initialPage = initialIndex) { nodes.size }
    val currentIndex = pagerState.currentPage

    // TV Remote Listener
    DisposableEffect(Unit) {
        sharedViewModel.initMediaSession(context) { command ->
            scope.launch {
                when (command) {
                    "NEXT" -> if (pagerState.currentPage < nodes.size - 1) pagerState.animateScrollToPage(pagerState.currentPage + 1)
                    "PREV" -> if (pagerState.currentPage > 0) pagerState.animateScrollToPage(pagerState.currentPage - 1)
                    "PLAY" -> { isSlideshowActive = true; isSlideshowPaused = false }
                    "PAUSE" -> isSlideshowPaused = true
                    "STOP" -> isSlideshowActive = false
                }
            }
        }
        onDispose { sharedViewModel.releaseMediaSession() }
    }

    // Auto-hide controls logic
    LaunchedEffect(areControlsVisible, isSlideshowActive, isSlideshowPaused) {
        if (areControlsVisible && !isPreparing) {
            if (!isSlideshowPaused) {
                delay(3000) 
                areControlsVisible = false
            }
        }
    }

    val imageLoader = remember(folderUrl) {
        sharedViewModel.fullImageLoader ?: run {
            val dispatcher = Executors.newFixedThreadPool(1).asCoroutineDispatcher()
            val loader = ImageLoader.Builder(context.applicationContext)
                .interceptorDispatcher(dispatcher)
                .fetcherDispatcher(dispatcher)
                .memoryCache { MemoryCache.Builder(context.applicationContext).maxSizePercent(0.20).build() }
                .components { add(MegaFetcher.Factory(megaRepository, folderUrl, isThumbnail = false)) }
                .build()
            sharedViewModel.fullImageLoader = loader
            loader
        }
    }

    LaunchedEffect(currentIndex) {
        Log.d("PixStreamo_Trace", "ImageViewer: Index changed to $currentIndex. Triggering cast...")
        sharedViewModel.castImage(context, nodes[currentIndex], folderUrl)
        
        if (currentIndex >= cacheEnd - 20 && cacheEnd < nodes.size) {
            val oldBatchSize = 20
            val nextBatchSize = 20
            val deleteEnd = minOf(cacheStart + oldBatchSize, currentIndex)
            if (deleteEnd > cacheStart) {
                val toDelete = nodes.subList(cacheStart, deleteEnd)
                val toDownload = nodes.subList(cacheEnd, minOf(cacheEnd + nextBatchSize, nodes.size))
                
                scope.launch(Dispatchers.IO) {
                    toDelete.forEach { megaRepository.removeImageFromCache(it.handle, isThumbnail = false) }
                    toDownload.chunked(2).forEach { batch ->
                        batch.map { node ->
                            async {
                                if (!megaRepository.isImageCached(node.handle, isThumbnail = false)) {
                                    try { withTimeout(30000L) { megaRepository.decryptImage(node.handle, node.key, folderUrl, isThumbnail = false, priority = DecryptPriority.LOW) } } catch (e: Exception) {}
                                }
                            }
                        }.awaitAll()
                    }
                    withContext(Dispatchers.Main) {
                        cacheStart = deleteEnd
                        cacheEnd += toDownload.size
                    }
                }
            }
        }
    }

    LaunchedEffect(isSlideshowActive) {
        if (isSlideshowActive) {
            areControlsVisible = false
            val alreadyCached = nodes.take(targetPrepareCount).count { megaRepository.isImageCached(it.handle, isThumbnail = false) }
            
            if (alreadyCached < targetPrepareCount) {
                isPreparing = true
                preparedCount = alreadyCached
                pagerState.scrollToPage(0)

                nodes.take(targetPrepareCount).forEach { node ->
                    if (!megaRepository.isImageCached(node.handle, isThumbnail = false)) {
                        try {
                            withContext(Dispatchers.IO) {
                                withTimeout(30000L) {
                                    megaRepository.decryptImage(node.handle, node.key, folderUrl, isThumbnail = false, priority = DecryptPriority.LOW)
                                }
                            }
                        } catch (e: Exception) {}
                        preparedCount++
                    }
                }
                delay(500)
                isPreparing = false
            }

            while (isSlideshowActive) {
                if (!isSlideshowPaused && !isPreparing) {
                    delay(4.seconds)
                    if (pagerState.currentPage < nodes.size - 1) {
                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                    } else {
                        isSlideshowActive = false 
                    }
                } else {
                    delay(500) 
                }
            }
        } else {
            isPreparing = false
            isSlideshowPaused = false
        }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            if (!isSlideshowActive) {
                AnimatedVisibility(visible = areControlsVisible || isPreparing, enter = fadeIn(), exit = fadeOut()) {
                    TopAppBar(
                        title = { 
                            Column {
                                Text(text = if (isPreparing) "Preparing Gallery..." else nodes[currentIndex].name, maxLines = 1, style = MaterialTheme.typography.titleMedium, color = Color.White)
                                if (!isPreparing) {
                                    Text(text = "${currentIndex + 1} / ${nodes.size}", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onBackClick) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White) }
                        },
                        actions = { 
                            CastButton(streamManager = streamManager, modifier = Modifier.size(40.dp)) 
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.6f), titleContentColor = Color.White)
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(if (!isSlideshowActive || isPreparing) padding else PaddingValues(0.dp))
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        areControlsVisible = !areControlsVisible
                    })
                },
            contentAlignment = Alignment.Center
        ) {
            if (isPreparing) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Your show will start soon...", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(24.dp))
                    LinearProgressIndicator(progress = { preparedCount.toFloat() / targetPrepareCount }, modifier = Modifier.width(240.dp), color = MaterialTheme.colorScheme.primary, trackColor = Color.White.copy(alpha = 0.2f))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("$preparedCount / $targetPrepareCount images ready", color = Color.White.copy(alpha = 0.7f))
                }
            } else {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), pageSpacing = 16.dp, userScrollEnabled = !isSlideshowActive) { index ->
                    SubcomposeAsyncImage(model = nodes[index], contentDescription = nodes[index].name, imageLoader = imageLoader, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit) {
                        when (painter.state) {
                            is AsyncImagePainter.State.Loading -> { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) } }
                            is AsyncImagePainter.State.Error -> {
                                Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Text("Image Unavailable", color = Color.White)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    IconButton(onClick = { }) { Icon(Icons.Default.Refresh, "Retry", tint = Color.White) }
                                }
                            }
                            else -> SubcomposeAsyncImageContent()
                        }
                    }
                }
            }

            // Normal Gallery Controls
            if (!isSlideshowActive && !isPreparing) {
                AnimatedVisibility(visible = areControlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                    Row(modifier = Modifier.padding(bottom = 48.dp, start = 16.dp, end = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } }, enabled = pagerState.currentPage > 0, modifier = Modifier.weight(1f).height(48.dp)) { Text("Prev") }
                        Button(onClick = { isSlideshowActive = true }, modifier = Modifier.weight(1.5f).height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Slideshow", maxLines = 1)
                        }
                        Button(onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } }, enabled = pagerState.currentPage < nodes.size - 1, modifier = Modifier.weight(1f).height(48.dp)) { Text("Next") }
                    }
                }
            }

            // Dedicated Slideshow Controls Overlay
            if (isSlideshowActive && !isPreparing) {
                AnimatedVisibility(visible = areControlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))) {
                        // Top Info & Casting
                        Row(
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp).fillMaxWidth().padding(horizontal = 24.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.size(40.dp)) 
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(text = nodes[currentIndex].name, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                Text(text = "${currentIndex + 1} / ${nodes.size}", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                            }
                            CastButton(streamManager = streamManager, modifier = Modifier.size(40.dp))
                        }

                        // Center Controls
                        Row(modifier = Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { isSlideshowPaused = !isSlideshowPaused },
                                modifier = Modifier.size(80.dp).background(Color.White.copy(alpha = 0.2f), shape = MaterialTheme.shapes.extraLarge)
                            ) {
                                Icon(
                                    imageVector = if (isSlideshowPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = if (isSlideshowPaused) "Resume" else "Pause",
                                    tint = Color.White,
                                    modifier = Modifier.size(48.dp)
                                )
                            }
                        }

                        // Exit Button at Bottom
                        Button(
                            onClick = { isSlideshowActive = false },
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp).height(56.dp).width(200.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Exit Slideshow")
                        }
                    }
                }
            }
        }
    }
}
