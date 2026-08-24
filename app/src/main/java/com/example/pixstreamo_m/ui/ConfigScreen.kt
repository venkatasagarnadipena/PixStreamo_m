/**
 * PixStreamo Modern Configuration Screen
 */
package com.example.pixstreamo_m.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.pixstreamo_m.R
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
    isAppendMode: Boolean = false,
    onConfigComplete: () -> Unit,
    onBackClick: (() -> Unit)? = null
) {
    var url by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text(if (isAppendMode) "Add Source" else "Project Setup", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (onBackClick != null) {
                        IconButton(onClick = onBackClick) { Icon(painterResource(R.drawable.ic_back), "Back") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black, titleContentColor = Color.White)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Spacer(Modifier.height(40.dp))
            
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
            }
            
            Spacer(Modifier.height(24.dp))
            
            Text(
                text = if (isAppendMode) "Add New Stream Source" else "Connect to your Stream",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(Modifier.height(8.dp))
            
            Text(
                text = "Enter a MEGA folder link or configuration URL.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
            
            Spacer(Modifier.height(40.dp))
            
            OutlinedTextField(
                value = url,
                onValueChange = { 
                    url = it
                    errorMessage = null 
                },
                label = { Text("URL") },
                placeholder = { Text("https://mega.nz/folder/...") },
                modifier = Modifier.fillMaxWidth(),
                isError = errorMessage != null,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.DarkGray,
                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                    unfocusedLabelColor = Color.Gray
                ),
                shape = RoundedCornerShape(16.dp),
                enabled = !isLoading
            )
            
            if (errorMessage != null) {
                Text(
                    text = errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth()
                )
            }
            
            Spacer(Modifier.height(32.dp))
            
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
                                if (!isAppendMode) database.folderDao().deleteAllFolders()
                                database.folderDao().insertFolders(folders)
                            }
                            if (!isAppendMode) preferenceManager.setConfigUrl(trimmedUrl)
                            onConfigComplete()
                        } catch (e: Exception) {
                            errorMessage = "${e.message}"
                        } finally {
                            isLoading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                enabled = !isLoading,
                shape = RoundedCornerShape(16.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.Black, strokeWidth = 2.dp)
                } else {
                    Text(if (isAppendMode) "Add Source" else "Initialize Project", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
