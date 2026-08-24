/**
 * PixStreamo Modern Image Grid Screen with Strict Isolation
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.ImageLoader
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.memory.MemoryCache
import com.example.pixstreamo_m.R
import com.example.pixstreamo_m.mega.*
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
    val allNodes by sharedViewModel.gridNodes.collectAsState()
    val isCleaningUp by sharedViewModel.isCleaningUp.collectAsState()
    
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()

    // Folder Entry Sync
    LaunchedEffect(folderUrl) {
        if (sharedViewModel.activeFolderUrl != folderUrl) {
            sharedViewModel.activeFolderUrl = folderUrl
            sharedViewModel.setNodes(emptyList())
            
            isLoading = true
            try {
                val nodes = withContext(Dispatchers.IO) {
                    megaRepository.getFolderNodes(folderUrl)
                }
                sharedViewModel.setNodes(nodes)
                sharedViewModel.startInitialLoad()
            } catch (e: Exception) {
                errorMessage = e.message
            } finally {
                isLoading = false
            }
        }
    }

    val imageLoader = remember(folderUrl, isCleaningUp) {
        if (isCleaningUp) return@remember null
        
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
                    IconButton(onClick = {
                        sharedViewModel.exitFolderCleanup { onBackClick() }
                    }) { Icon(painterResource(R.drawable.ic_back), "Back", tint = Color.White) }
                },
                actions = {
                    // Initial Preload Progress (First 50 images)
                    val preloadCount by sharedViewModel.gridPreloadCount.collectAsState()
                    val targetCount = remember(allNodes.size) { minOf(allNodes.size, 50) }
                    
                    if (targetCount > 0 && preloadCount < targetCount) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(end = 4.dp).size(40.dp)) {
                            CircularProgressIndicator(
                                progress = { preloadCount.toFloat() / 50f }, // Based on initial 50-image batch
                                modifier = Modifier.size(26.dp),
                                strokeWidth = 3.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            // Progress calculated as 2% per image (based on 50-image batch)
                            val displayPercentage = (preloadCount * 2).coerceAtMost(100)
                            Text(
                                text = "${displayPercentage}%",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 7.sp),
                                color = Color.White
                            )
                        }
                    }
                    
                    CastButton(streamManager = streamManager, modifier = Modifier.size(40.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.8f))
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            if ((isLoading || isCleaningUp) && allNodes.isEmpty()) {
                Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    if (isCleaningUp) Text("Cleaning up...", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                }
            } else if (errorMessage != null && allNodes.isEmpty()) {
                Text(errorMessage!!, color = Color.White, modifier = Modifier.align(Alignment.Center))
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(110.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(allNodes) { index, node ->
                        if (imageLoader != null) {
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
            Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.4f)), startY = 150f)))
        }
    }
}
