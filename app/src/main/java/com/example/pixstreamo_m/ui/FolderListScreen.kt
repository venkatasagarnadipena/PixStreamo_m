/**
 * PixStreamo Folder List (Home) Screen
 *
 * Displays the list of available image categories/folders stored in the local DB.
 * Responsible for:
 * 1. Reading the Room Database to show current folders.
 * 2. Providing access to Global Settings.
 * 3. Handling navigation to the Image Grid for a specific folder.
 */
package com.example.pixstreamo_m.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.pixstreamo_m.data.AppDatabase
import com.example.pixstreamo_m.data.FolderEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderListScreen(
    database: AppDatabase,
    onFolderClick: (FolderEntity) -> Unit,
    onSettingsClick: () -> Unit
) {
    val folders by database.folderDao().getAllFolders().collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PixStreamo") },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            items(folders) { folder ->
                ListItem(
                    headlineContent = { Text(folder.name) },
                    modifier = Modifier.clickable { onFolderClick(folder) }
                )
                HorizontalDivider()
            }
        }
    }
}
