/**
 * PixStreamo Configuration Screen
 *
 * The first screen shown to a new user. 
 * Responsible for:
 * 1. Taking a Master MEGA/JSON URL.
 * 2. Fetching and parsing the initial folder list.
 * 3. Initializing the Room Database with the folder configuration.
 */
package com.example.pixstreamo_m.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.pixstreamo_m.data.PreferenceManager
import com.example.pixstreamo_m.data.AppDatabase
import com.example.pixstreamo_m.mega.MegaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    preferenceManager: PreferenceManager,
    database: AppDatabase,
    megaRepository: MegaRepository,
    onConfigComplete: () -> Unit
) {
    var url by remember { mutableStateOf(preferenceManager.getConfigUrl() ?: "") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("PixStreamo Setup") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Connect to your Stream",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Enter a MEGA folder link, file link, or JSON URL to load your gallery.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            OutlinedTextField(
                value = url,
                onValueChange = { 
                    url = it
                    errorMessage = null 
                },
                label = { Text("MEGA Folder or Config URL") },
                placeholder = { Text("https://mega.nz/folder/...") },
                modifier = Modifier.fillMaxWidth(),
                isError = errorMessage != null,
                singleLine = false, // Allow multi-line for long URLs
                maxLines = 3,
                enabled = !isLoading
            )
            
            if (errorMessage != null) {
                Text(
                    text = errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                    textAlign = TextAlign.Start
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Button(
                onClick = {
                    val trimmedUrl = url.trim().replace("\n", "").replace("\r", "")
                    if (trimmedUrl.isBlank()) {
                        errorMessage = "URL cannot be empty"
                        return@Button
                    }
                    isLoading = true
                    errorMessage = null
                    scope.launch {
                        try {
                            val folders = withContext(Dispatchers.IO) {
                                megaRepository.fetchConfig(trimmedUrl, context.cacheDir.absolutePath)
                            }
                            withContext(Dispatchers.IO) {
                                database.folderDao().deleteAllFolders()
                                database.folderDao().insertFolders(folders)
                            }
                            preferenceManager.setConfigUrl(trimmedUrl)
                            onConfigComplete()
                        } catch (e: Exception) {
                            errorMessage = "${e.message}"
                        } finally {
                            isLoading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Initialize Project")
                }
            }
            
            if (preferenceManager.isConfigured()) {
                TextButton(
                    onClick = onConfigComplete,
                    modifier = Modifier.padding(top = 16.dp),
                    enabled = !isLoading
                ) {
                    Text("Skip to Folders")
                }
            }
        }
    }
}
