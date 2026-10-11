package com.goldsky.ssp.feature.retail.screens

import androidx.compose.foundation.background
import com.goldsky.ssp.feature.retail.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.goldsky.ssp.feature.retail.ui.GoldSkyCyan
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.StaffMember
import com.goldsky.ssp.payment.StaffRepository
import com.goldsky.ssp.ui.components.NumericKeypad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PIN_LENGTH = 4

/**
 * Staff sign-in: a 4-digit PIN, unique per store, looked up in the staff list
 * from CMP (cached on the terminal, so it works offline). Refreshes that list
 * each time it's shown. No staff set up = no way in (the old shared PIN 1234
 * is gone); the screen says where to add them.
 */
@Composable
fun StaffPinLockScreen(onSignedIn: (StaffMember) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var syncFailed by remember { mutableStateOf(false) }
    var staffCount by remember { mutableStateOf(StaffRepository.cached()?.size) }

    fun refresh() {
        syncing = true
        scope.launch {
            syncFailed = !StaffRepository.sync()
            staffCount = StaffRepository.cached()?.size
            syncing = false
        }
    }
    // Again once the device is linked to its org: on a first launch the
    // first fetch runs before that and comes back empty.
    val identityVersion by DeviceRepository.identityVersion.collectAsState()
    LaunchedEffect(identityVersion) { refresh() }

    fun submit(entered: String) {
        checking = true
        scope.launch {
            // PBKDF2 per staff member: off the main thread.
            val result = withContext(Dispatchers.Default) { StaffRepository.verify(entered) }
            checking = false
            pin = ""
            when (result) {
                is StaffRepository.PinResult.Match -> {
                    StaffRepository.signIn(result.staff)
                    onSignedIn(result.staff)
                }
                StaffRepository.PinResult.NoMatch -> error = context.getString(R.string.retail_wrong_pin)
                StaffRepository.PinResult.Ambiguous -> error = context.getString(R.string.retail_pin_ambiguous)
            }
        }
    }

    val notice: String? = when {
        staffCount == null && syncing -> stringResource(R.string.retail_downloading_staff)
        staffCount == null -> stringResource(R.string.retail_staff_unreachable)
        staffCount == 0 -> stringResource(R.string.retail_no_staff)
        else -> null
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.Lock, null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(stringResource(R.string.retail_staff_login),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(24.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(PIN_LENGTH) { i ->
                    Box(
                        Modifier.size(18.dp).clip(CircleShape).background(
                            if (i < pin.length) (MaterialTheme.colorScheme.primary)
                            else (MaterialTheme.colorScheme.outline)
                        )
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Box(Modifier.height(48.dp), contentAlignment = Alignment.Center) {
                when {
                    checking -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                    notice != null -> Text(notice, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium)
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    syncFailed -> Text(stringResource(R.string.retail_offline_using_the_saved_staff), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (staffCount == null || staffCount == 0) {
                TextButton(onClick = { refresh() }, enabled = !syncing) {
                    Icon(Icons.Default.Refresh, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.retail_retry), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))

            NumericKeypad(
                onNumberClick = { num ->
                    if (checking || notice != null) return@NumericKeypad
                    error = null
                    if (pin.length < PIN_LENGTH) {
                        pin += num
                        if (pin.length == PIN_LENGTH) submit(pin)
                    }
                },
                onBackSpace = { if (pin.isNotEmpty()) pin = pin.dropLast(1) },
                onConfirm = { if (pin.length == PIN_LENGTH && !checking) submit(pin) }
            )
        }
    }
}

