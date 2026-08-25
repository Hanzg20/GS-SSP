package com.goldsky.ssp.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.goldsky.ssp.R
import com.goldsky.ssp.model.RetailMode
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.ui.theme.GoldSkyBlue
import com.goldsky.ssp.viewmodel.RetailViewModel

/**
 * Main Checkout container with Tab switching between Keypad and Library.
 */
@Composable
fun CheckoutScreen(
    onCheckout: (Int) -> Unit,
    retailViewModel: RetailViewModel = viewModel()
) {
    var selectedTab by remember { mutableStateOf(0) }
    val retailMode = remember { DeviceRepository.getPersistedRetailMode() }

    Column(modifier = Modifier.fillMaxSize()) {
        // Branding Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Image(
                painter = painterResource(id = R.drawable.goldsky_logo),
                contentDescription = null,
                modifier = Modifier.height(24.dp)
            )
            Text(
                text = DeviceRepository.getStoreName().uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = GoldSkyBlue,
                letterSpacing = 1.sp
            )
        }

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("KEYPAD", fontWeight = FontWeight.Bold) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("LIBRARY", fontWeight = FontWeight.Bold) }
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            if (selectedTab == 0) {
                // Keypad View (Quick Pay logic)
                ManualAmountScreen(onInitiatePayment = { amountStr ->
                    val cents = amountStr.replace(".", "").toIntOrNull() ?: 0
                    if (cents > 0) onCheckout(cents)
                })
            } else {
                // Library View (Mode-specific)
                when (retailMode) {
                    RetailMode.RESTAURANT -> DineInScreen(onTableSelected = { /* Mock subtotal */ onCheckout(5000) })
                    RetailMode.DELIVERY -> DeliveryScreen(onCollect = { onCheckout(it) })
                    else -> RetailStoreScreen(
                        onCheckout = { onCheckout(it) },
                        viewModel = retailViewModel
                    )
                }
            }
        }
    }
}
