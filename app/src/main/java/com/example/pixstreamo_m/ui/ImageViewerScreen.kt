/**
 * PixStreamo Full-Screen Image Viewer
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
import androidx.compose.ui.res.painterResource
import com.example.pixstreamo_m.R
import com.example.pixstreamo_m.mega.*
import kotlinx.coroutines.*
import java.util.concurrent.Executors

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
    val currentIndex by sharedViewModel.currentIndex.collectAsState()
    
    var areControlsVisible by rememberSaveable { mutableStateOf(true) }
    
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val pagerState = rememberPagerState(initialPage = initialIndex) { nodes.size }

    // Sync Pager <-> ViewModel
    LaunchedEffect(currentIndex) {
        if (pagerState.currentPage != currentIndex) {
            pagerState.animateScrollToPage(currentIndex)
        }
    }
    
    LaunchedEffect(pagerState.currentPage) {
        sharedViewModel.setCurrentIndex(pagerState.currentPage, context)
    }

    // TV Remote & Session
    DisposableEffect(Unit) {
        sharedViewModel.activeFolderUrl = folderUrl
        sharedViewModel.initMediaSession(context)
        onDispose { 
            sharedViewModel.releaseMediaSession()
            sharedViewModel.toggleSlideshow(false)
        }
    }

    // Auto-hide controls
    LaunchedEffect(areControlsVisible, isSlideshowActive, isSlideshowPaused) {
        if (areControlsVisible && isSlideshowActive && !isSlideshowPaused) {
            delay(3000) 
            areControlsVisible = false
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

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            if (!isSlideshowActive) {
                AnimatedVisibility(visible = areControlsVisible, enter = fadeIn(), exit = fadeOut()) {
                    TopAppBar(
                        title = { 
                            Column {
                                Text(text = nodes[pagerState.currentPage].name, maxLines = 1, style = MaterialTheme.typography.titleMedium, color = Color.White)
                                Text(text = "${pagerState.currentPage + 1} / ${nodes.size}", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onBackClick) { Icon(painterResource(R.drawable.ic_back), contentDescription = "Back", tint = Color.White) }
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
            modifier = Modifier.fillMaxSize().padding(if (!isSlideshowActive) padding else PaddingValues(0.dp))
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { areControlsVisible = !areControlsVisible })
                },
            contentAlignment = Alignment.Center
        ) {
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

            // Normal Gallery Controls
            if (!isSlideshowActive) {
                AnimatedVisibility(visible = areControlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                    Row(modifier = Modifier.padding(bottom = 48.dp, start = 16.dp, end = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } }, enabled = pagerState.currentPage > 0, modifier = Modifier.weight(1f).height(48.dp)) { Text("Prev") }
                        Button(onClick = { sharedViewModel.toggleSlideshow(true) }, modifier = Modifier.weight(1.5f).height(48.dp)) {
                            Icon(painterResource(R.drawable.ic_play), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Slideshow")
                        }
                        Button(onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } }, enabled = pagerState.currentPage < nodes.size - 1, modifier = Modifier.weight(1f).height(48.dp)) { Text("Next") }
                    }
                }
            }

            // Slideshow Overlay
            if (isSlideshowActive) {
                AnimatedVisibility(visible = areControlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))) {
                        Row(modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp).fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Box(modifier = Modifier.size(40.dp)) 
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(text = nodes[pagerState.currentPage].name, color = Color.White, style = MaterialTheme.typography.titleMedium)
                                Text(text = "${pagerState.currentPage + 1} / ${nodes.size}", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                            }
                            CastButton(streamManager = streamManager, modifier = Modifier.size(40.dp))
                        }

                        IconButton(onClick = { sharedViewModel.togglePause(!isSlideshowPaused) }, modifier = Modifier.align(Alignment.Center).size(80.dp).background(Color.White.copy(alpha = 0.2f), shape = MaterialTheme.shapes.extraLarge)) {
                            Icon(imageVector = if (isSlideshowPaused) Icons.Default.PlayArrow else Icons.Default.Pause, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
                        }

                        Button(onClick = { sharedViewModel.toggleSlideshow(false) }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp).height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
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
