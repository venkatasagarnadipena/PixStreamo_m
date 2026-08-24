/**
 * PixStreamo Modern Image Grid Screen
 */
package com.example.pixstreamo_m.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.memory.MemoryCache
import com.example.pixstreamo_m.R
import com.example.pixstreamo_m.mega.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent

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
            val dispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
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
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text(folderName, style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
                        if (allNodes.isNotEmpty()) {
                            Text("${allNodes.size} items", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) { Icon(painterResource(R.drawable.ic_back), "Back", tint = Color.White) }
                },
                actions = {
                    CastButton(streamManager = streamManager, modifier = Modifier.size(40.dp), tint = Color.White)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.8f))
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            if (isLoading && displayedNodes.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.primary)
            } else if (errorMessage != null && displayedNodes.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(text = errorMessage!!, color = Color.White)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { loadNodes() }) { Text("Retry") }
                }
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(110.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(displayedNodes) { index, node ->
                        ImageCard(
                            node = node, 
                            imageLoader = imageLoader,
                            onClick = { onImageClick(allNodes, index) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ImageCard(node: MegaImageNode, imageLoader: ImageLoader, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121212))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            SubcomposeAsyncImage(
                model = node,
                contentDescription = node.name,
                imageLoader = imageLoader,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            ) {
                when (painter.state) {
                    is AsyncImagePainter.State.Loading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                        }
                    }
                    is AsyncImagePainter.State.Error -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Close, null, tint = Color.DarkGray)
                        }
                    }
                    else -> SubcomposeAsyncImageContent()
                }
            }
            
            // Minimal Overlay for text readability if needed
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.4f)),
                            startY = 150f
                        )
                    )
            )
        }
    }
}
