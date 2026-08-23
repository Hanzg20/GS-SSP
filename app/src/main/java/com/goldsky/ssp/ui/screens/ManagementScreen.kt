package com.goldsky.ssp.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManagementScreen() {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("MANAGEMENT", fontWeight = FontWeight.Black) })
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.Business, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
            Spacer(modifier = Modifier.height(16.dp))
            Text("Catalog & Staff Management", color = MaterialTheme.colorScheme.outline)
        }
    }
}
