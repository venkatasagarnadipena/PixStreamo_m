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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.semantics.Role
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
import java.util.Locale

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
    var showStorageDialog by remember { mutableStateOf(false) }
    var cacheSize by remember { mutableLongStateOf(cacheManager.getCacheSize()) }
    val context = LocalContext.current

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(it, flags)
            preferenceManager.setCacheUri(it.toString())
            preferenceManager.setCacheMode(CacheManager.StorageMode.CUSTOM.name)
            cacheSize = cacheManager.getCacheSize()
        }
    }

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
            Text("GALLERY MANAGEMENT", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            
            SettingsActionCard(
                title = "Folder Management",
                subtitle = "Edit or remove existing sources",
                icon = Icons.Default.List,
                onClick = { showFolderManager = true }
            )
            
            SettingsActionCard(
                title = "Add New Source",
                subtitle = "Sync another MEGA folder or JSON URL",
                icon = Icons.Default.Add,
                onClick = onAddNewClick
            )

            SettingsActionCard(
                title = "Config Reset",
                subtitle = "Wipe all data and restart setup",
                icon = painterResource(R.drawable.ic_reset),
                iconTint = MaterialTheme.colorScheme.primary,
                onClick = onResetConfig
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color.DarkGray)

            Text("STORAGE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            
            SettingsActionCard(
                title = "Storage Location",
                subtitle = run {
                    val mode = preferenceManager.getCacheMode()
                    if (mode == CacheManager.StorageMode.CUSTOM.name) {
                        val uri = preferenceManager.getCacheUri() ?: "Not selected"
                        // Clean up URI for display (remove document/%3A etc)
                        val displayName = Uri.decode(uri).split("/").lastOrNull() ?: uri
                        "Custom: $displayName"
                    } else {
                        "Current: $mode"
                    }
                },
                icon = Icons.Default.Storage,
                onClick = { showStorageDialog = true }
            )

            SettingsActionCard(
                title = "Clear Decryption Cache",
                subtitle = "Current usage: ${formatFileSize(cacheSize)}",
                icon = Icons.Default.Delete,
                onClick = { 
                    cacheManager.clearAllCache()
                    cacheSize = 0
                }
            )
        }
    }

    if (showFolderManager) {
        FolderManagerDialog(
            database = database,
            onDismiss = { showFolderManager = false }
        )
    }

    if (showStorageDialog) {
        StorageLocationDialog(
            preferenceManager = preferenceManager,
            onDismiss = { 
                showStorageDialog = false
                cacheSize = cacheManager.getCacheSize()
            },
            onPickFolder = { folderPicker.launch(null) }
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
                    .size(44.dp)
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
        title = { Text("Synced Folders", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            if (folders.isEmpty()) {
                Text("No folders synced yet.", color = Color.Gray)
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(folders) { folder ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(folder.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                                Text(folder.url, color = Color.Gray, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                            IconButton(onClick = { 
                                scope.launch(Dispatchers.IO) { database.folderDao().deleteFolder(folder) }
                            }) {
                                Icon(Icons.Default.Delete, "Remove", tint = Color.Red.copy(alpha = 0.7f))
                            }
                        }
                        HorizontalDivider(color = Color.DarkGray.copy(alpha = 0.3f))
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun StorageLocationDialog(
    preferenceManager: PreferenceManager,
    onDismiss: () -> Unit,
    onPickFolder: () -> Unit
) {
    val modes = listOf(
        CacheManager.StorageMode.AUTO to "Auto (External > Internal)",
        CacheManager.StorageMode.INTERNAL to "Internal Storage Only",
        CacheManager.StorageMode.CUSTOM to "Custom Folder (Select...)"
    )
    val selectedMode = preferenceManager.getCacheMode()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1A1A),
        title = { Text("Storage Location", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.selectableGroup()) {
                modes.forEach { (mode, label) ->
                    Row(
                        Modifier.fillMaxWidth().height(56.dp)
                            .selectable(
                                selected = (selectedMode == mode.name),
                                onClick = { 
                                    if (mode == CacheManager.StorageMode.CUSTOM) {
                                        onPickFolder()
                                        onDismiss()
                                    } else {
                                        preferenceManager.setCacheMode(mode.name)
                                        onDismiss()
                                    }
                                },
                                role = Role.RadioButton
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = (selectedMode == mode.name), onClick = null)
                        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = Color.White, modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format(Locale.US, "%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}
