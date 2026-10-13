package com.goldsky.ssp.feature.retail.screens

import androidx.compose.foundation.BorderStroke
import com.goldsky.ssp.feature.retail.R
import androidx.compose.ui.res.stringResource
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
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.NuveiOnboardingRepository
import com.goldsky.ssp.feature.retail.RetailRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(showBusinessType: Boolean = false) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var storeName by remember { mutableStateOf(DeviceRepository.getStoreName()) }
    var storeAddr by remember { mutableStateOf(DeviceRepository.getStoreAddress()) }
    var storePhone by remember { mutableStateOf(DeviceRepository.getStorePhone()) }
    var taxRateText by remember { mutableStateOf(DeviceRepository.getTaxRate().let { if (it == 0f) "" else it.toString() }) }
    var tipsEnabled by remember { mutableStateOf(DeviceRepository.isTipsEnabled()) }

    var hardwareVendor by remember { mutableStateOf(DeviceRepository.getPersistedHardwareVendor()) }
    var terminalIpText by remember { mutableStateOf(DeviceRepository.getTerminalIp()) }
    var eyeRotation by remember { mutableStateOf(DeviceRepository.getEyeRotation()) }

    var selectedRegion by remember { mutableStateOf(DeviceRepository.getRegion()) }
    var nuveiAppId by remember { mutableStateOf(DeviceRepository.getNuveiAppId()) }
    val onboardingStatus by NuveiOnboardingRepository.currentStatus.collectAsState()
    var isCheckingStatus by remember { mutableStateOf(false) }


    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.retail_settings), fontWeight = FontWeight.Black) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Counter terminals (Ourea) only: a quick-service counter adds the kitchen steps.
            if (showBusinessType) {
                var businessType by remember { mutableStateOf(DeviceRepository.getBusinessType()) }
                Text(stringResource(R.string.retail_business_type), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        listOf(
                            Triple(DeviceRepository.BusinessType.RETAIL, R.string.retail_business_retail, R.string.retail_business_retail_desc),
                            Triple(DeviceRepository.BusinessType.QUICK_SERVICE, R.string.retail_business_qsr, R.string.retail_business_qsr_desc)
                        ).forEach { (type, title, desc) ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    businessType = type; DeviceRepository.persistBusinessType(type)
                                }.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(title), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                    Text(stringResource(desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                RadioButton(selected = businessType == type, onClick = {
                                    businessType = type; DeviceRepository.persistBusinessType(type)
                                })
                            }
                        }
                    }
                }
            }

            Text(stringResource(R.string.retail_receipt_settings), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedTextField(
                        value = storeName,
                        onValueChange = { storeName = it; DeviceRepository.persistStoreName(it) },
                        label = { Text(stringResource(R.string.retail_store_name)) },
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
                        label = { Text(stringResource(R.string.retail_default_tax_rate)) },
                        supportingText = { Text(stringResource(R.string.retail_for_keypad_amounts_and_products)) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.retail_ask_for_tip), fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.retail_show_the_tip_screen_before), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = tipsEnabled, onCheckedChange = { tipsEnabled = it; DeviceRepository.persistTipsEnabled(it) })
                    }
                }
            }

            Text(stringResource(R.string.retail_hardware_payment_terminal), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.retail_payment_hardware_vendor), fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("WIZARPOS", "PAX", "IDTECH").forEach { vendor ->
                            FilterChip(
                                selected = hardwareVendor == vendor,
                                onClick = {
                                    hardwareVendor = vendor
                                    DeviceRepository.persistHardwareVendor(vendor)
                                },
                                label = { Text(vendor) }
                            )
                        }
                    }
                    if (hardwareVendor == "WIZARPOS") {
                        OutlinedTextField(
                            value = terminalIpText,
                            onValueChange = { ip ->
                                terminalIpText = ip
                                DeviceRepository.persistTerminalIp(ip.trim())
                            },
                            label = { Text(stringResource(R.string.retail_wizarpos_terminal_ip_e_g)) },
                            supportingText = { Text(stringResource(R.string.retail_ip_address_of_customer_facing)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(stringResource(R.string.retail_ourea_eye_display_rotation), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Upright, or upside down for a display mounted facing the customer.
                            listOf(0, 180).forEach { deg ->
                                FilterChip(
                                    selected = eyeRotation == deg,
                                    onClick = {
                                        eyeRotation = deg
                                        DeviceRepository.persistEyeRotation(deg)
                                        val provider = com.goldsky.ssp.payment.PaymentProviderFactory.getPaymentProvider(context, "WIZARPOS")
                                        provider.showEyeRotation(deg)
                                    },
                                    label = { Text("$deg°") }
                                )
                            }
                        }
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
                Text(stringResource(R.string.retail_force_sync_catalog))
            }
            
            // (The old mode list -- QUICK_PAY, AI_INSIGHTS, RECORDS... RESTAURANT -- mixed
            // page names with business types and only ever switched the library tab to a
            // fixed $50 "table". Business type at the top replaces it.)

            // Interface language (UiLanguage). Applying it recreates the activity;
            // Arabic / Persian also mirror the layout once their translations ship.
            val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
            val currentLang = remember { activity?.let { com.goldsky.ssp.payment.UiLanguage.current(it) } ?: "en" }
            Text(stringResource(R.string.retail_language), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    com.goldsky.ssp.payment.UiLanguage.available.forEach { lang ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable(enabled = lang.code != currentLang) {
                                activity?.let { com.goldsky.ssp.payment.UiLanguage.set(it, lang.code); it.recreate() }
                            }.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(lang.nativeName, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                            RadioButton(selected = lang.code == currentLang, onClick = {
                                activity?.let { com.goldsky.ssp.payment.UiLanguage.set(it, lang.code); it.recreate() }
                            })
                        }
                    }
                }
            }

            // Theme Options at the Bottom (Cyber Dark Mode vs Classic Light Mode)
            var themeStyle by remember { mutableStateOf(DeviceRepository.getThemeStyle()) }

            Text(stringResource(R.string.retail_ui_theme), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline),
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
                            Text(stringResource(R.string.retail_cyber_dark_mode), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Text(stringResource(R.string.retail_dark_futuristic_theme_with_glowing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            Text(stringResource(R.string.retail_classic_light_mode), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Text(stringResource(R.string.retail_clean_minimalist_layout_with_standard), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, color = MaterialTheme.colorScheme.onSurface)
            RadioButton(selected = selected, onClick = null)
        }
    }
}
