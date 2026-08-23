/**
 * PixStreamo Main Entry Activity
 */
package com.example.pixstreamo_m

import android.os.Bundle
import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.pixstreamo_m.ui.SharedViewModel
import com.example.pixstreamo_m.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("PixStreamo_Trace", "MainActivity: onCreate")

        val app = application as PixStreamoApplication
        val megaRepository = app.megaRepository
        val preferenceManager = app.preferenceManager
        val database = app.database
        val streamManager = app.streamManager

        setContent {
            val navController = rememberNavController()
            val sharedViewModel: SharedViewModel = viewModel()
            val context = LocalContext.current
            
            // Sync services to VM
            if (sharedViewModel.localStreamServer == null) {
                sharedViewModel.localStreamServer = app.localStreamServer
                sharedViewModel.streamManager = app.streamManager
            }
            
            LaunchedEffect(Unit) {
                streamManager.initialize(context)
            }
            
            val currentNodes by sharedViewModel.currentNodes.collectAsState()
            val isConfigured = preferenceManager.isConfigured()
            val startDestination = if (isConfigured) "folder_list" else "config"
            
            Log.d("PixStreamo_Trace", "MainUI: Rendering NavHost. Configured: $isConfigured, Start: $startDestination")
            
            NavHost(navController = navController, startDestination = startDestination) {
                composable("config") {
                    ConfigScreen(
                        preferenceManager = preferenceManager,
                        database = database,
                        megaRepository = megaRepository,
                        onConfigComplete = {
                            navController.navigate("folder_list") { popUpTo("config") { inclusive = true } }
                        }
                    )
                }
                composable("folder_list") {
                    FolderListScreen(
                        database = database,
                        onFolderClick = { folder ->
                            navController.navigate("image_grid/${folder.id}")
                        },
                        onSettingsClick = { navController.navigate("settings") }
                    )
                }
                composable("settings") {
                    SettingsScreen(
                        cacheManager = app.cacheManager,
                        preferenceManager = preferenceManager,
                        onBackClick = { navController.popBackStack() },
                        onResetConfig = {
                            preferenceManager.clearConfig()
                            navController.navigate("config") { popUpTo(0) }
                        }
                    )
                }
                composable(
                    "image_grid/{folderId}",
                    arguments = listOf(navArgument("folderId") { type = NavType.IntType })
                ) { backStackEntry ->
                    val folderId = backStackEntry.arguments?.getInt("folderId") ?: 0
                    var folderUrl by remember { mutableStateOf<String?>(null) }
                    var folderName by remember { mutableStateOf("") }
                    
                    LaunchedEffect(folderId) {
                        Log.d("PixStreamo_Trace", "MainUI: Looking up folder $folderId")
                        val folder = withContext(Dispatchers.IO) { database.folderDao().getFolderById(folderId) }
                        if (folder != null) {
                            folderName = folder.name
                            folderUrl = folder.url
                            Log.d("PixStreamo_Trace", "MainUI: Folder found: ${folder.name}")
                        } else {
                            Log.e("PixStreamo_Trace", "MainUI: Folder NOT FOUND for ID: $folderId")
                        }
                    }

                    if (folderUrl == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        ImageGridScreen(
                            folderName = folderName,
                            folderUrl = folderUrl!!,
                            megaRepository = megaRepository,
                            streamManager = streamManager,
                            onBackClick = { navController.popBackStack() },
                            onImageClick = { nodes, index ->
                                sharedViewModel.setNodes(nodes)
                                navController.navigate("image_viewer/$folderId/$index")
                            }
                        )
                    }
                }
                composable(
                    "image_viewer/{folderId}/{index}",
                    arguments = listOf(
                        navArgument("folderId") { type = NavType.IntType },
                        navArgument("index") { type = NavType.IntType }
                    )
                ) { backStackEntry ->
                    val folderId = backStackEntry.arguments?.getInt("folderId") ?: 0
                    val index = backStackEntry.arguments?.getInt("index") ?: 0
                    var folderUrl by remember { mutableStateOf<String?>(null) }

                    LaunchedEffect(folderId) {
                        Log.d("PixStreamo_Trace", "MainUI: Viewer looking up folder $folderId")
                        val folder = withContext(Dispatchers.IO) { database.folderDao().getFolderById(folderId) }
                        folderUrl = folder?.url
                        if (folderUrl == null) Log.e("PixStreamo_Trace", "MainUI: Viewer folder NOT FOUND for ID: $folderId")
                    }
                    
                    if (folderUrl == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        ImageViewerScreen(
                            initialIndex = index,
                            nodes = currentNodes,
                            folderUrl = folderUrl!!,
                            megaRepository = megaRepository,
                            streamManager = streamManager,
                            onBackClick = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}
