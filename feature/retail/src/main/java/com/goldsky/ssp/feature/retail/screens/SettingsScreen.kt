package com.goldsky.ssp.feature.retail.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
    var taxRateText by remember { mutableStateOf(DeviceRepository.getTaxRate().let { if (it == 0f) "" else it.toString() }) }
    var tipsEnabled by remember { mutableStateOf(DeviceRepository.isTipsEnabled()) }

    var selectedRegion by remember { mutableStateOf(DeviceRepository.getRegion()) }
    var nuveiAppId by remember { mutableStateOf(DeviceRepository.getNuveiAppId()) }
    val onboardingStatus by NuveiOnboardingRepository.currentStatus.collectAsState()
    var isCheckingStatus by remember { mutableStateOf(false) }

    val isCyber = DeviceRepository.getThemeStyle() == DeviceRepository.UiThemeStyle.CYBER

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SETTINGS", fontWeight = FontWeight.Black) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (isCyber) Color(0xFF0F172A) else MaterialTheme.colorScheme.background,
                    titleContentColor = if (isCyber) Color.White else MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(if (isCyber) Color(0xFF0B0F19) else MaterialTheme.colorScheme.background)
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            
            Text("RECEIPT SETTINGS", style = MaterialTheme.typography.labelMedium, color = if (isCyber) com.goldsky.ssp.feature.retail.ui.GoldSkyCyan else MaterialTheme.colorScheme.primary)
            
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (isCyber) Color(0xFF162032) else MaterialTheme.colorScheme.surface,
                border = BorderStroke(0.5.dp, if (isCyber) Color(0xFF26354F) else MaterialTheme.colorScheme.outline),
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
                    OutlinedTextField(
                        value = taxRateText,
                        onValueChange = { text ->
                            taxRateText = text
                            if (text.isBlank()) DeviceRepository.persistTaxRate(0f)
                            else text.toFloatOrNull()?.takeIf { it in 0f..100f }?.let { DeviceRepository.persistTaxRate(it) }
                        },
                        label = { Text("Default tax rate (%)") },
                        supportingText = { Text("For keypad amounts and products without their own rate") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Ask for tip", fontWeight = FontWeight.Bold)
                            Text("Show the tip screen before payment", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = tipsEnabled, onCheckedChange = { tipsEnabled = it; DeviceRepository.persistTipsEnabled(it) })
                    }
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

            // Theme Options at the Bottom (Cyber Dark Mode vs Classic Light Mode)
            var themeStyle by remember { mutableStateOf(DeviceRepository.getThemeStyle()) }

            Text("UI THEME", style = MaterialTheme.typography.labelMedium, color = if (isCyber) com.goldsky.ssp.feature.retail.ui.GoldSkyCyan else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (isCyber) Color(0xFF162032) else MaterialTheme.colorScheme.surface,
                border = BorderStroke(0.5.dp, if (isCyber) Color(0xFF26354F) else MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            themeStyle = DeviceRepository.UiThemeStyle.CYBER
                            DeviceRepository.persistThemeStyle(DeviceRepository.UiThemeStyle.CYBER)
                        },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Cyber Dark Mode", fontWeight = FontWeight.Bold, color = if (isCyber) Color.White else MaterialTheme.colorScheme.onSurface)
                            Text("Dark futuristic theme with glowing pulse borders", style = MaterialTheme.typography.bodySmall, color = if (isCyber) Color.LightGray else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        RadioButton(
                            selected = themeStyle == DeviceRepository.UiThemeStyle.CYBER,
                            onClick = {
                                themeStyle = DeviceRepository.UiThemeStyle.CYBER
                                DeviceRepository.persistThemeStyle(DeviceRepository.UiThemeStyle.CYBER)
                            }
                        )
                    }
                    Divider()
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            themeStyle = DeviceRepository.UiThemeStyle.CLASSIC
                            DeviceRepository.persistThemeStyle(DeviceRepository.UiThemeStyle.CLASSIC)
                        },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Classic Light Mode", fontWeight = FontWeight.Bold, color = if (isCyber) Color.White else MaterialTheme.colorScheme.onSurface)
                            Text("Clean minimalist layout with standard light colors", style = MaterialTheme.typography.bodySmall, color = if (isCyber) Color.LightGray else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        RadioButton(
                            selected = themeStyle == DeviceRepository.UiThemeStyle.CLASSIC,
                            onClick = {
                                themeStyle = DeviceRepository.UiThemeStyle.CLASSIC
                                DeviceRepository.persistThemeStyle(DeviceRepository.UiThemeStyle.CLASSIC)
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun ModeSelectionItem(title: String, selected: Boolean, onClick: () -> Unit) {
    val isCyber = DeviceRepository.getThemeStyle() == DeviceRepository.UiThemeStyle.CYBER
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            if (isCyber) Color(0xFF1E293B) else MaterialTheme.colorScheme.primaryContainer
        } else {
            if (isCyber) Color(0xFF162032) else MaterialTheme.colorScheme.surface
        },
        border = if (isCyber) BorderStroke(0.5.dp, Color(0xFF26354F)) else null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, color = if (isCyber) Color.White else MaterialTheme.colorScheme.onSurface)
            RadioButton(selected = selected, onClick = null)
        }
    }
}
