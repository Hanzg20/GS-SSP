package com.goldsky.ssp.vending.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goldsky.ssp.vending.VendingViewModel
import com.goldsky.ssp.vending.logic.VendingState
import com.goldsky.ssp.vending.ui.components.NfcRadar
import com.goldsky.ssp.vending.ui.theme.*

/**
 * The main container for IM25 Vending UI.
 * High contrast, large fonts, and clear NFC guidance.
 * Modernized via Logic Crowdsourcing from Nayax/Cantaloupe/Ingenico.
 */
@Composable
fun VendingMainContainer(viewModel: VendingViewModel) {
    val state by viewModel.uiState.collectAsState()

    VendingTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Crossfade(targetState = state, label = "ScreenTransition") { currentState ->
                    when (currentState) {
                        is VendingState.Idle -> IdleScreen()
                        is VendingState.VendRequestReceived -> PaymentScreen(
                            currentState.amountCents, 
                            currentState.itemSlot,
                            onPayClick = { viewModel.startPayment() }
                        )
                        is VendingState.PaymentAuthorizing -> LoadingScreen("AUTHORIZING...")
                        is VendingState.Dispensing -> LoadingScreen("DISPENSING...")
                        is VendingState.Success -> ResultScreen(true, currentState.orderId)
                        is VendingState.Error -> ResultScreen(false, currentState.message)
                        else -> LoadingScreen("BOOTING...")
                    }
                }
                
                // One-Tap Localization & Branding Header (Insights from Moneris/Nayax)
                VendingHeader()
                
                // Debug Overlay
                if (state is VendingState.Idle) {
                    Column(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Button(
                            onClick = { viewModel.debugFailNextVend() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.5f))
                        ) {
                            Text("DEBUG: FAIL NEXT VEND", fontSize = 10.sp)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.debugTriggerMachineRequest(200, "A1") },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray.copy(alpha = 0.5f))
                        ) {
                            Text("DEBUG: SIMULATE SELECTION", fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VendingHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "GOLDSKY", 
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.6f)
        )
        
        // Language Toggle (Insights from Nayax)
        Surface(
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.1f)
        ) {
            Text(
                text = "EN | FR",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                fontSize = 10.sp,
                color = Color.White
            )
        }
    }
}

@Composable
fun IdleScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Logo Placeholder
        Box(
            modifier = Modifier.size(60.dp).clip(CircleShape).background(GoldskyPrimary),
            contentAlignment = Alignment.Center
        ) {
            Text("G", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "WELCOME",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 2.sp
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "PLEASE SELECT PRODUCT\nON MACHINE PANEL",
            color = TextSecondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )
    }
}

@Composable
fun PaymentScreen(amountCents: Int, label: String, onPayClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        
        Text(label, color = TextSecondary, fontSize = 14.sp)
        
        Text(
            text = "$${String.format("%.2f", amountCents / 100.0)}",
            color = MaterialTheme.colorScheme.secondary,
            fontSize = 42.sp,
            fontWeight = FontWeight.Black
        )
        
        Spacer(modifier = Modifier.weight(1f))
        
        // Modern NFC Radar Animation (Insights from Cantaloupe)
        Box(modifier = Modifier.clickable { onPayClick() }) {
            NfcRadar()
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        Text(
            text = "TAP TO PAY",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        
        Text(
            text = "DEBIT / CREDIT / MOBILE",
            color = Color.DarkGray,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun LoadingScreen(text: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = GoldskyPrimary, strokeWidth = 4.dp)
        Spacer(modifier = Modifier.height(24.dp))
        Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ResultScreen(isSuccess: Boolean, info: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val color = if (isSuccess) GoldskySuccess else GoldskyError
        
        // Success/Error Feedback (Insights from Ingenico)
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(color.copy(alpha = 0.1f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .background(color, CircleShape)
            )
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Text(
            text = if (isSuccess) "APPROVED" else "DECLINED",
            color = color,
            fontSize = 32.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = info.uppercase(),
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            lineHeight = 16.sp
        )
        
        if (isSuccess) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "PLEASE COLLECT YOUR ITEM",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
