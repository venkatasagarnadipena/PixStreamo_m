/**
 * PixStreamo Image Grid Screen
 */
package com.example.pixstreamo_m.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.memory.MemoryCache
import com.example.pixstreamo_m.mega.MegaFetcher
import com.example.pixstreamo_m.mega.MegaImageNode
import com.example.pixstreamo_m.mega.MegaRepository
import com.example.pixstreamo_m.mega.StreamManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageGridScreen(
    folderName: String,
    folderUrl: String,
    megaRepository: MegaRepository,
    streamManager: StreamManager,
    onBackClick: () -> Unit,
    onImageClick: (List<MegaImageNode>, Int) -> Unit,
    sharedViewModel: SharedViewModel = viewModel()
) {
    var allNodes by remember { mutableStateOf<List<MegaImageNode>>(emptyList()) }
    var displayedNodes by remember { mutableStateOf<List<MegaImageNode>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()

    val imageLoader = remember(folderUrl) {
        if (sharedViewModel.activeFolderUrl != folderUrl) {
            sharedViewModel.gridImageLoader = null
            sharedViewModel.fullImageLoader = null
            sharedViewModel.activeFolderUrl = folderUrl
        }
        sharedViewModel.gridImageLoader ?: run {
            val dispatcher = Executors.newFixedThreadPool(3).asCoroutineDispatcher()
            val loader = ImageLoader.Builder(context.applicationContext)
                .interceptorDispatcher(dispatcher)
                .fetcherDispatcher(dispatcher)
                .memoryCache { MemoryCache.Builder(context.applicationContext).maxSizePercent(0.10).build() }
                .components { add(MegaFetcher.Factory(megaRepository, folderUrl, isThumbnail = true)) }
                .build()
            sharedViewModel.gridImageLoader = loader
            loader
        }
    }

    fun loadNodes() {
        scope.launch {
            isLoading = true
            errorMessage = null
            try {
                val nodes = withContext(Dispatchers.IO) {
                    megaRepository.getFolderNodes(folderUrl)
                }
                allNodes = nodes
                if (allNodes.isEmpty()) {
                    errorMessage = "No images found."
                } else {
                    displayedNodes = allNodes.take(50)
                }
            } catch (e: Exception) {
                errorMessage = "Error: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(folderUrl) {
        if (allNodes.isEmpty()) loadNodes()
    }

    val shouldLoadMore = remember {
        derivedStateOf {
            val totalItems = displayedNodes.size
            val lastVisibleItem = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 0 && lastVisibleItem >= totalItems - 10 && totalItems < allNodes.size
        }
    }

    LaunchedEffect(shouldLoadMore.value) {
        if (shouldLoadMore.value) {
            val nextBatch = allNodes.drop(displayedNodes.size).take(50)
            displayedNodes = displayedNodes + nextBatch
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text(folderName, style = MaterialTheme.typography.titleMedium)
                        if (allNodes.isNotEmpty()) {
                            Text("${displayedNodes.size} / ${allNodes.size} items", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    CastButton(streamManager = streamManager, modifier = Modifier.size(40.dp))
                    IconButton(onClick = { loadNodes() }, enabled = !isLoading) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            if (isLoading && displayedNodes.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (errorMessage != null && displayedNodes.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(text = errorMessage!!)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { loadNodes() }) { Text("Retry") }
                }
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(110.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(4.dp)
                ) {
                    itemsIndexed(displayedNodes) { index, node ->
                        Card(
                            modifier = Modifier.padding(4.dp).aspectRatio(1f).clickable { onImageClick(allNodes, index) },
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            AsyncImage(
                                model = node,
                                contentDescription = node.name,
                                imageLoader = imageLoader,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
            }

            // Debug IP Overlay
            val baseUrl = sharedViewModel.localStreamServer?.getBaseUrl() ?: "Server Not Initialized"
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.7f))
                    .padding(4.dp)
            ) {
                Text(
                    text = "Base: $baseUrl",
                    color = androidx.compose.ui.graphics.Color.Green,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
