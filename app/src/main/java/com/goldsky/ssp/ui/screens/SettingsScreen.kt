package com.goldsky.ssp.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.goldsky.ssp.model.RetailMode
import com.goldsky.ssp.payment.DeviceRepository

/**
 * Settings screen for merchant configuration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    var selectedMode by remember { mutableStateOf(DeviceRepository.getPersistedRetailMode()) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("SETTINGS", fontWeight = FontWeight.Black) })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("WORK MODE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            
            RetailMode.values().forEach { mode ->
                ModeSelectionItem(
                    title = mode.name.replace("_", " "),
                    selected = selectedMode == mode,
                    onClick = {
                        selectedMode = mode
                        DeviceRepository.persistRetailMode(mode)
                    }
                )
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            
            Text("PREFERENCES", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            
            PreferenceToggleItem(title = "Auto-Print Receipt", icon = Icons.Default.Print, checked = true)
            PreferenceToggleItem(title = "Sound Feedback", icon = Icons.Default.VolumeUp, checked = true)
            PreferenceToggleItem(title = "Haptic Feedback", icon = Icons.Default.Vibration, checked = true)
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text("DEVICE INFO", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text("SN: ${DeviceRepository.getPersistedDeviceSn()}", style = MaterialTheme.typography.bodySmall)
            Text("ORG ID: ${DeviceRepository.getPersistedOrgId()}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun ModeSelectionItem(title: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            RadioButton(selected = selected, onClick = null)
        }
    }
}

@Composable
fun PreferenceToggleItem(title: String, icon: ImageVector, checked: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(16.dp))
            Text(title)
        }
        Switch(checked = checked, onCheckedChange = {})
    }
}
