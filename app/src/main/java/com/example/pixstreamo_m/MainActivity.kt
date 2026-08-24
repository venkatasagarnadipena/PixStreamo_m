/**
 * PixStreamo Main Entry Activity
 */
package com.example.pixstreamo_m

import android.os.Bundle
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
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.pixstreamo_m.ui.SharedViewModel
import com.example.pixstreamo_m.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions()

        val app = application as PixStreamoApplication
        val megaRepository = app.megaRepository
        val preferenceManager = app.preferenceManager
        val database = app.database
        val streamManager = app.streamManager

        setContent {
            PixStreamoTheme {
                val navController = rememberNavController()
                val sharedViewModel: SharedViewModel = viewModel()
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                
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
                        database = database,
                        onBackClick = { navController.popBackStack() },
                        onAddNewClick = { navController.navigate("add_new_source") },
                        onResetConfig = {
                            scope.launch(Dispatchers.IO) {
                                database.folderDao().deleteAllFolders()
                                preferenceManager.clearConfig()
                                withContext(Dispatchers.Main) {
                                    navController.navigate("config") { popUpTo(0) }
                                }
                            }
                        }
                    )
                }
                composable("add_new_source") {
                    ConfigScreen(
                        preferenceManager = preferenceManager,
                        database = database,
                        megaRepository = megaRepository,
                        isAppendMode = true,
                        onConfigComplete = { navController.popBackStack() },
                        onBackClick = { navController.popBackStack() }
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
                            val folder = withContext(Dispatchers.IO) { database.folderDao().getFolderById(folderId) }
                            if (folder != null) {
                                folderName = folder.name
                                folderUrl = folder.url
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
                                },
                                sharedViewModel = sharedViewModel
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
                            val folder = withContext(Dispatchers.IO) { database.folderDao().getFolderById(folderId) }
                            folderUrl = folder?.url
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
                                onBackClick = { navController.popBackStack() },
                                sharedViewModel = sharedViewModel
                            )
                        }
                    }
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        val requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { _ ->
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissionLauncher.launch(missing.toTypedArray())
        }
    }
}
