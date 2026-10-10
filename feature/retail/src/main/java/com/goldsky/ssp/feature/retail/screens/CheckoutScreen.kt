package com.goldsky.ssp.feature.retail.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.goldsky.ssp.feature.retail.ui.GoldSkyCyan
import com.goldsky.ssp.feature.retail.viewmodel.RetailViewModel
import com.goldsky.ssp.payment.DeviceRepository
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun CheckoutScreen(viewModel: RetailViewModel = viewModel()) {
    val cart = viewModel.cart
    val total by viewModel.totalCents.collectAsState()
    val isCyber = DeviceRepository.getThemeStyle() == DeviceRepository.UiThemeStyle.CYBER

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isCyber) Color(0xFF0B0F19) else MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        Text(
            "CHECKOUT",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = if (isCyber) GoldSkyCyan else MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        cart.forEach { item ->
            Text(
                "${item.product.name} x${item.quantity}",
                color = if (isCyber) Color.White else MaterialTheme.colorScheme.onBackground
            )
        }
        
        Spacer(modifier = Modifier.weight(1f))
        Text(
            "Total: $${"%.2f".format(total / 100.0)}",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = if (isCyber) Color.White else MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { /* Handle payment */ },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = if (isCyber) ButtonDefaults.buttonColors(containerColor = GoldSkyCyan, contentColor = Color.Black)
            else ButtonDefaults.buttonColors()
        ) {
            Text("PAY NOW", fontWeight = FontWeight.Bold)
        }
    }
}

