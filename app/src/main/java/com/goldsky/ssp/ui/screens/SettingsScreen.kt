package com.goldsky.ssp.ui.screens

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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goldsky.ssp.model.RetailMode
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.NuveiOnboardingRepository
import com.goldsky.ssp.payment.RetailRepository
import kotlinx.coroutines.launch

/**
 * Settings screen for merchant configuration.
 */
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
            
            Text("RECEIPT & INVOICE SETTINGS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
            
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
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    
                    OutlinedTextField(
                        value = storeAddr,
                        onValueChange = { storeAddr = it; DeviceRepository.persistStoreAddress(it) },
                        label = { Text("Business Address") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    OutlinedTextField(
                        value = storePhone,
                        onValueChange = { storePhone = it; DeviceRepository.persistStorePhone(it) },
                        label = { Text("Contact Phone") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Sales Tax", style = MaterialTheme.typography.bodyMedium)
                            Text("${"%.1f".format(taxRate)}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(
                            value = taxRate,
                            onValueChange = { taxRate = it; DeviceRepository.persistTaxRate(it) },
                            valueRange = 0f..25f,
                            steps = 50,
                            modifier = Modifier.weight(2f)
                        )
                    }
                }
            }

            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            Text("NUVEI APPLINK ONBOARDING", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
            
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Region Toggle
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("Region", fontWeight = FontWeight.Bold)
                        Row {
                            FilterChip(
                                selected = selectedRegion == "CA",
                                onClick = { selectedRegion = "CA"; DeviceRepository.persistRegion("CA") },
                                label = { Text("Canada") }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            FilterChip(
                                selected = selectedRegion == "US",
                                onClick = { selectedRegion = "US"; DeviceRepository.persistRegion("US") },
                                label = { Text("USA") }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = nuveiAppId,
                        onValueChange = { nuveiAppId = it; DeviceRepository.persistNuveiAppId(it) },
                        label = { Text("Application ID") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    // Onboarding Status Row
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Column {
                            Text("Approval Status", style = MaterialTheme.typography.labelSmall)
                            Text(onboardingStatus.name, fontWeight = FontWeight.Black, color = if (onboardingStatus == NuveiOnboardingRepository.OnboardingStatus.APPROVED) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary)
                        }
                        FilledTonalButton(
                            onClick = { 
                                scope.launch {
                                    isCheckingStatus = true
                                    NuveiOnboardingRepository.refreshStatus(nuveiAppId)
                                    isCheckingStatus = false
                                }
                            },
                            enabled = !isCheckingStatus && nuveiAppId.isNotBlank()
                        ) {
                            if (isCheckingStatus) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            else Text("REFRESH")
                        }
                    }
                }
            }

            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            Text("DATA MANAGEMENT", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
            
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

            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            Text("WORK MODE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
            
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
            
            Spacer(modifier = Modifier.height(24.dp))
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
