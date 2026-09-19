package com.goldsky.ssp.feature.retail.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goldsky.ssp.model.RetailMode
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.NuveiOnboardingRepository
import com.goldsky.ssp.feature.retail.RetailRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var selectedMode by remember { mutableStateOf(DeviceRepository.getPersistedRetailMode()) }
    var storeName by remember { mutableStateOf(DeviceRepository.getStoreName()) }
    var storeAddr by remember { mutableStateOf(DeviceRepository.getStoreAddress()) }
    var storePhone by remember { mutableStateOf(DeviceRepository.getStorePhone()) }
    var taxRate by remember { mutableStateOf(DeviceRepository.getTaxRate()) }

    var selectedRegion by remember { mutableStateOf(DeviceRepository.getRegion()) }
    var nuveiAppId by remember { mutableStateOf(DeviceRepository.getNuveiAppId()) }
    val onboardingStatus by NuveiOnboardingRepository.currentStatus.collectAsState()
    var isCheckingStatus by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SETTINGS", fontWeight = FontWeight.Black) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            
            Text("RECEIPT SETTINGS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedTextField(
                        value = storeName,
                        onValueChange = { storeName = it; DeviceRepository.persistStoreName(it) },
                        label = { Text("Store Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }

            Button(
                onClick = { 
                    scope.launch {
                        RetailRepository.syncWithCloud(context)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Sync, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("FORCE SYNC CATALOG")
            }
            
            RetailMode.values().forEach { mode ->
                ModeSelectionItem(
                    title = mode.name,
                    selected = selectedMode == mode,
                    onClick = {
                        selectedMode = mode
                        DeviceRepository.persistRetailMode(mode)
                    }
                )
            }
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
