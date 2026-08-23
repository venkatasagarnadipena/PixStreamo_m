/**
 * PixStreamo Global Settings Screen
 *
 * Provides user control over storage and application state.
 * Responsible for:
 * 1. Selecting Cache Storage mode (Internal, SD Card, etc).
 * 2. Launching the System Folder Picker for custom cache locations.
 * 3. Displaying current cache usage and providing a cleanup tool.
 * 4. Allowing the user to reset the entire Master URL configuration.
 */
package com.example.pixstreamo_m.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.pixstreamo_m.data.PreferenceManager
import com.example.pixstreamo_m.mega.CacheManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    cacheManager: CacheManager,
    preferenceManager: PreferenceManager,
    onBackClick: () -> Unit,
    onResetConfig: () -> Unit
) {
    val context = LocalContext.current
    var selectedMode by remember { mutableStateOf(preferenceManager.getCacheMode()) }
    var cacheUri by remember { mutableStateOf(preferenceManager.getCacheUri()) }
    var cacheSize by remember { mutableLongStateOf(cacheManager.getCacheSize()) }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(it, flags)
            
            preferenceManager.setCacheUri(it.toString())
            preferenceManager.setCacheMode(CacheManager.StorageMode.CUSTOM.name)
            cacheUri = it.toString()
            selectedMode = CacheManager.StorageMode.CUSTOM.name
            cacheSize = cacheManager.getCacheSize()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
        ) {
            Text(text = "Cache Storage", style = MaterialTheme.typography.titleMedium)
            Text(text = "Select where to store decrypted images to save internal space.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            
            Spacer(modifier = Modifier.height(16.dp))

            val modes = listOf(
                CacheManager.StorageMode.AUTO to "Auto (External > Internal)",
                CacheManager.StorageMode.EXTERNAL to "External Storage (SD Card)",
                CacheManager.StorageMode.INTERNAL to "Internal Storage",
                CacheManager.StorageMode.CUSTOM to "Custom Folder (Select...)",
                CacheManager.StorageMode.RAM to "No Cache (RAM Only)"
            )

            Column(Modifier.selectableGroup()) {
                modes.forEach { (mode, label) ->
                    Row(
                        Modifier.fillMaxWidth().height(56.dp)
                            .selectable(
                                selected = (selectedMode == mode.name),
                                onClick = { if (mode == CacheManager.StorageMode.CUSTOM) { folderPicker.launch(null) } else { selectedMode = mode.name; preferenceManager.setCacheMode(mode.name); cacheSize = cacheManager.getCacheSize() } },
                                role = Role.RadioButton
                            )
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = (selectedMode == mode.name), onClick = null)
                        Column(modifier = Modifier.padding(start = 16.dp)) {
                            Text(text = label, style = MaterialTheme.typography.bodyLarge)
                            if (mode == CacheManager.StorageMode.CUSTOM && cacheUri != null) {
                                Text(text = cacheUri!!, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                            }
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(text = "Cache Usage", style = MaterialTheme.typography.titleMedium)
                    Text(text = formatFileSize(cacheSize), style = MaterialTheme.typography.bodyMedium)
                }
                Button(onClick = { cacheManager.clearAllCache(); cacheSize = 0 }) { Text("Clear Cache") }
            }

            Spacer(modifier = Modifier.weight(1f))

            OutlinedButton(onClick = onResetConfig, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Reset Configuration") }
        }
    }
}

fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}
