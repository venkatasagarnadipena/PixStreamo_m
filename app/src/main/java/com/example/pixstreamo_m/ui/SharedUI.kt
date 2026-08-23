/**
 * PixStreamo Shared UI Components
 */
package com.example.pixstreamo_m.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.pixstreamo_m.mega.StreamManager

/**
 * A Custom Compose-Based Cast Button and Device Picker.
 * Bypasses the buggy native MediaRouter dialogs to prevent theme-related crashes.
 */
@Composable
fun CastButton(
    streamManager: StreamManager?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) {
    if (streamManager == null) return
    
    val isConnected by streamManager.isConnected.collectAsState()
    val discoveredRoutes by streamManager.discoveredRoutes.collectAsState()
    var showDialog by remember { mutableStateOf(false) }

    IconButton(
        onClick = { 
            showDialog = true 
            streamManager.startDiscovery()
        },
        modifier = modifier
    ) {
        Icon(
            imageVector = if (isConnected) Icons.Default.CastConnected else Icons.Default.Cast,
            contentDescription = "Cast",
            tint = if (isConnected) MaterialTheme.colorScheme.primary else tint
        )
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { 
                showDialog = false
                streamManager.stopDiscovery()
            },
            title = { Text("Connect to Device") },
            text = {
                if (discoveredRoutes.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("Searching for TVs...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                        items(discoveredRoutes) { route ->
                            ListItem(
                                headlineContent = { Text(route.name) },
                                leadingContent = { Icon(Icons.Default.Tv, null) },
                                modifier = Modifier.clickable {
                                    streamManager.selectRoute(route)
                                    showDialog = false
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (isConnected) {
                    TextButton(onClick = { 
                        streamManager.disconnect()
                        showDialog = false
                    }) {
                        Text("Disconnect", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Cancel") }
            }
        )
    }
}
