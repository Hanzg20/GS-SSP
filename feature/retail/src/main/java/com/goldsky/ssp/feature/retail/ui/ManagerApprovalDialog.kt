package com.goldsky.ssp.feature.retail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.StaffMember
import com.goldsky.ssp.payment.StaffRepository
import com.goldsky.ssp.ui.components.NumericKeypad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A manager approves [action] on the spot with their PIN -- also when the
 * manager is the one signed in, so the approval is a deliberate act. Used for
 * refunds; meant for price overrides / removing scanned items too.
 */
@Composable
fun ManagerApprovalDialog(
    action: String,
    onApproved: (StaffMember) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    fun submit(entered: String) {
        checking = true
        scope.launch {
            val result = withContext(Dispatchers.Default) { StaffRepository.verify(entered) }
            checking = false
            pin = ""
            when {
                result is StaffRepository.PinResult.Match && result.staff.isManager -> onApproved(result.staff)
                result is StaffRepository.PinResult.Match -> error = "${result.staff.name} is not a manager"
                result == StaffRepository.PinResult.Ambiguous -> error = "PIN shared by several staff -- reset it in CMP"
                else -> error = "Wrong PIN"
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline),
            tonalElevation = 6.dp
        ) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.AdminPanelSettings, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Manager approval",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    action,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    repeat(4) { i ->
                        Box(
                            Modifier.size(16.dp).clip(CircleShape).background(
                                if (i < pin.length) (MaterialTheme.colorScheme.primary)
                                else (MaterialTheme.colorScheme.outline)
                            )
                        )
                    }
                }
                Box(Modifier.height(36.dp), contentAlignment = Alignment.Center) {
                    when {
                        checking -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                        error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    }
                }
                NumericKeypad(
                    onNumberClick = { n ->
                        if (checking) return@NumericKeypad
                        error = null
                        if (pin.length < 4) { pin += n; if (pin.length == 4) submit(pin) }
                    },
                    onBackSpace = { if (pin.isNotEmpty()) pin = pin.dropLast(1) },
                    onConfirm = { if (pin.length == 4 && !checking) submit(pin) }
                )
                TextButton(onClick = onDismiss) { Text("CANCEL", color = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

