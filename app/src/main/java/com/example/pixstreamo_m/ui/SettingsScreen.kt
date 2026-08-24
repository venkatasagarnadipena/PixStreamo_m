/**
 * PixStreamo Modern Settings Screen
 */
package com.example.pixstreamo_m.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.pixstreamo_m.R
import com.example.pixstreamo_m.data.PreferenceManager
import com.example.pixstreamo_m.mega.CacheManager
import com.example.pixstreamo_m.data.AppDatabase
import com.example.pixstreamo_m.data.FolderEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    cacheManager: CacheManager,
    preferenceManager: PreferenceManager,
    database: AppDatabase,
    onBackClick: () -> Unit,
    onAddNewClick: () -> Unit,
    onResetConfig: () -> Unit
) {
    var showFolderManager by remember { mutableStateOf(false) }
    var cacheSize by remember { mutableLongStateOf(cacheManager.getCacheSize()) }
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = "Back")
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Main Actions Section
            Text("Management", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            
            SettingsActionCard(
                title = "Folder Management",
                subtitle = "View and remove synced folders",
                icon = painterResource(R.drawable.ic_filemanager),
                onClick = { showFolderManager = true }
            )
            
            SettingsActionCard(
                title = "Add New URL",
                subtitle = "Append more MEGA folders to your stream",
                icon = Icons.Default.Add,
                onClick = onAddNewClick
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color.DarkGray)

            Text("Storage", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            
            SettingsActionCard(
                title = "Clear Cache",
                subtitle = "Current usage: ${formatFileSize(cacheSize)}",
                icon = Icons.Default.Delete,
                onClick = { 
                    cacheManager.clearAllCache()
                    cacheSize = 0
                }
            )

            Spacer(modifier = Modifier.weight(1f))

            // Danger Zone
            Text("Danger Zone", style = MaterialTheme.typography.labelLarge, color = Color.Red)
            SettingsActionCard(
                title = "Config Reset",
                subtitle = "Wipe all URLs and folders from database",
                icon = painterResource(R.drawable.ic_reset),
                iconTint = Color.Red,
                onClick = onResetConfig
            )
        }
    }

    if (showFolderManager) {
        FolderManagerDialog(
            database = database,
            onDismiss = { showFolderManager = false }
        )
    }
}

@Composable
fun SettingsActionCard(
    title: String,
    subtitle: String,
    icon: Any,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121212))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(iconTint.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                when (icon) {
                    is ImageVector -> Icon(icon, null, tint = iconTint)
                    is androidx.compose.ui.graphics.painter.Painter -> Icon(icon, null, tint = iconTint)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
    }
}

@Composable
fun FolderManagerDialog(database: AppDatabase, onDismiss: () -> Unit) {
    val folders by database.folderDao().getAllFolders().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1A1A),
        title = { Text("Manage Folders", color = Color.White) },
        text = {
            if (folders.isEmpty()) {
                Text("No folders found.", color = Color.Gray)
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(folders) { folder ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(folder.name, color = Color.White, fontWeight = FontWeight.Medium)
                                Text(folder.url, color = Color.Gray, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                            IconButton(onClick = { 
                                scope.launch(Dispatchers.IO) { database.folderDao().deleteFolder(folder) }
                            }) {
                                Icon(Icons.Default.Close, null, tint = Color.Red)
                            }
                        }
                        HorizontalDivider(color = Color.DarkGray.copy(alpha = 0.5f))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}
