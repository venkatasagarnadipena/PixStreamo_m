/**
 * PixStreamo Full-Screen Image Viewer with Strict Lifecycle
 */
package com.example.pixstreamo_m.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.ImageLoader
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.memory.MemoryCache
import com.example.pixstreamo_m.R
import com.example.pixstreamo_m.mega.*
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
    val isSlideshowActive by sharedViewModel.isSlideshowActive.collectAsState()
    val isSlideshowPaused by sharedViewModel.isSlideshowPaused.collectAsState()
    val isPreparing by sharedViewModel.isPreparing.collectAsState()
    val preparedCount by sharedViewModel.preparedCount.collectAsState()
    val currentIndex by sharedViewModel.currentIndex.collectAsState()
    val isCastDialogOpen by sharedViewModel.isCastDialogOpen.collectAsState()
    val isCleaningUp by sharedViewModel.isCleaningUp.collectAsState()
    
    var areControlsVisible by rememberSaveable { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = initialIndex) { nodes.size }

    // Manual Swipe -> VM
    LaunchedEffect(pagerState.currentPage) {
        if (!isSlideshowActive && !isCleaningUp) {
            sharedViewModel.setCurrentIndex(pagerState.currentPage)
        }
    }

    // VM (Timer) -> Pager
    LaunchedEffect(currentIndex) {
        if (pagerState.currentPage != currentIndex && !isCleaningUp) {
            pagerState.animateScrollToPage(currentIndex)
        }
    }

    // Auto-hide controls
    LaunchedEffect(areControlsVisible, isSlideshowActive, isSlideshowPaused, isCastDialogOpen) {
        if (areControlsVisible && isSlideshowActive && !isSlideshowPaused && !isPreparing && !isCastDialogOpen) {
            delay(3000)
            areControlsVisible = false
        }
    }

    val imageLoader = remember(folderUrl, isCleaningUp) {
        if (isCleaningUp) return@remember null
        sharedViewModel.fullImageLoader ?: run {
            val dispatcher = Executors.newFixedThreadPool(1).asCoroutineDispatcher()
            val loader = ImageLoader.Builder(sharedViewModel.getApplication())
                .interceptorDispatcher(dispatcher)
                .fetcherDispatcher(dispatcher)
                .memoryCache { MemoryCache.Builder(sharedViewModel.getApplication()).maxSizePercent(0.20).build() }
                .components { add(MegaFetcher.Factory(megaRepository, folderUrl, isThumbnail = false)) }
                .build()
            sharedViewModel.fullImageLoader = loader
            loader
        }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            if ((areControlsVisible || isCastDialogOpen) && !isSlideshowActive && !isPreparing && !isCleaningUp) {
                TopAppBar(
                    title = { 
                        if (nodes.isNotEmpty() && pagerState.currentPage in nodes.indices) {
                            Column {
                                Text(text = nodes[pagerState.currentPage].name, maxLines = 1, style = MaterialTheme.typography.titleMedium, color = Color.White)
                                Text(text = "${pagerState.currentPage + 1} / ${nodes.size}", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) { Icon(painterResource(R.drawable.ic_back), "Back", tint = Color.White) }
                    },
                    actions = { 
                        CastButton(streamManager = streamManager, modifier = Modifier.size(40.dp)) 
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.6f), titleContentColor = Color.White)
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(if (!isSlideshowActive && !isPreparing) padding else PaddingValues(0.dp))
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { areControlsVisible = !areControlsVisible })
                },
            contentAlignment = Alignment.Center
        ) {
            if (isPreparing) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.95f)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            progress = { preparedCount.toFloat() / 50f },
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 6.dp,
                            modifier = Modifier.size(80.dp)
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Text("Optimizing Gallery for TV...", color = Color.White, fontWeight = FontWeight.Bold)
                        Text("$preparedCount / 50 ready", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        
                        Spacer(modifier = Modifier.height(64.dp))
                        Button(
                            onClick = { sharedViewModel.toggleSlideshow(false) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.1f)),
                            modifier = Modifier.height(56.dp).width(200.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.Close, null, tint = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text("Cancel Loading", color = Color.White)
                        }
                    }
                }
            } else {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), pageSpacing = 16.dp, userScrollEnabled = !isSlideshowActive) { index ->
                    if (imageLoader != null) {
                        SubcomposeAsyncImage(model = nodes[index], contentDescription = nodes[index].name, imageLoader = imageLoader, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit) {
                            when (painter.state) {
                                is AsyncImagePainter.State.Loading -> { 
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { 
                                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) 
                                    } 
                                }
                                is AsyncImagePainter.State.Error -> {
                                    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                        Icon(Icons.Default.Refresh, "Retry", tint = Color.White)
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text("Image Unavailable", color = Color.White)
                                    }
                                }
                                else -> SubcomposeAsyncImageContent()
                            }
                        }
                    }
                }
            }

            // Normal Controls
            if (!isSlideshowActive && !isPreparing) {
                AnimatedVisibility(visible = areControlsVisible || isCastDialogOpen, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                    Row(modifier = Modifier.padding(bottom = 48.dp, start = 16.dp, end = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } }, enabled = pagerState.currentPage > 0, modifier = Modifier.weight(1f).height(48.dp)) { Text("Prev") }
                        Button(onClick = { sharedViewModel.toggleSlideshow(true) }, modifier = Modifier.weight(1.5f).height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                            Icon(painterResource(R.drawable.ic_play), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Slideshow")
                        }
                        Button(onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } }, enabled = pagerState.currentPage < nodes.size - 1, modifier = Modifier.weight(1f).height(48.dp)) { Text("Next") }
                    }
                }
            }

            // Slideshow Overlay
            if (isSlideshowActive && !isPreparing) {
                AnimatedVisibility(visible = areControlsVisible || isCastDialogOpen, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))) {
                        Row(modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp).fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(40.dp)) 
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (nodes.isNotEmpty() && currentIndex in nodes.indices) {
                                    Text(text = nodes[currentIndex].name, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                    Text(text = "${currentIndex + 1} / ${nodes.size}", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            CastButton(streamManager = streamManager, modifier = Modifier.size(40.dp))
                        }

                        IconButton(onClick = { sharedViewModel.togglePause() }, modifier = Modifier.align(Alignment.Center).size(80.dp).background(Color.White.copy(alpha = 0.2f), shape = MaterialTheme.shapes.extraLarge)) {
                            Icon(imageVector = if (isSlideshowPaused) Icons.Default.PlayArrow else Icons.Default.Pause, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
                        }

                        Button(onClick = { sharedViewModel.toggleSlideshow(false) }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp).height(56.dp).width(200.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
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
