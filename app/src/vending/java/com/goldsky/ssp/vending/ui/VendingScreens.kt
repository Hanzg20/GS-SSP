package com.goldsky.ssp.vending.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
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

/**
 * The main container for IM25 Vending UI.
 * High contrast, large fonts, and clear NFC guidance.
 */
@Composable
fun VendingMainContainer(viewModel: VendingViewModel) {
    val state by viewModel.uiState.collectAsState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF121212) // High contrast dark theme
    ) {
        Crossfade(targetState = state, label = "ScreenTransition") { currentState ->
            when (currentState) {
                is VendingViewModel.UiState.Idle -> IdleScreen()
                is VendingViewModel.UiState.AwaitingPayment -> PaymentScreen(
                    currentState.amountCents, 
                    currentState.productLabel
                )
                is VendingViewModel.UiState.Authorizing -> LoadingScreen("AUTHORIZING...")
                is VendingViewModel.UiState.Dispensing -> LoadingScreen("DISPENSING...")
                is VendingViewModel.UiState.Success -> ResultScreen(true, currentState.orderId)
                is VendingViewModel.UiState.Error -> ResultScreen(false, currentState.message)
            }
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
            modifier = Modifier.size(60.dp).clip(CircleShape).background(Color(0xFF2E86DE)),
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
            color = Color.Gray,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )
    }
}

@Composable
fun PaymentScreen(amountCents: Int, label: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = Color.Gray, fontSize = 16.sp)
        
        Text(
            text = "$${String.format("%.2f", amountCents / 100.0)}",
            color = Color(0xFFF1C40F), // Gold price
            fontSize = 48.sp,
            fontWeight = FontWeight.Black
        )
        
        Spacer(modifier = Modifier.weight(1f))
        
        // NFC Tap Animation Placeholder
        NfcHotzoneAnimation()
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "TAP TO PAY",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        
        Text(
            text = "VISA / MASTER / INTERAC",
            color = Color.DarkGray,
            fontSize = 10.sp
        )
    }
}

@Composable
fun NfcHotzoneAnimation() {
    // A pulsing circle to guide the user to the reader
    Box(contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.size(80.dp),
            shape = CircleShape,
            color = Color(0xFF2E86DE).copy(alpha = 0.2f)
        ) {}
        Surface(
            modifier = Modifier.size(50.dp),
            shape = CircleShape,
            color = Color(0xFF2E86DE)
        ) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(android.R.drawable.stat_sys_upload), // Placeholder
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

@Composable
fun LoadingScreen(text: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = Color(0xFF2E86DE), strokeWidth = 6.dp)
        Spacer(modifier = Modifier.height(24.dp))
        Text(text, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ResultScreen(isSuccess: Boolean, info: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val color = if (isSuccess) Color(0xFF2ECC71) else Color(0xFFE74C3C)
        Surface(
            modifier = Modifier.size(60.dp),
            shape = CircleShape,
            color = color
        ) {
            // Icon Placeholder
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = if (isSuccess) "THANK YOU" else "ERROR",
            color = color,
            fontSize = 28.sp,
            fontWeight = FontWeight.Black
        )
        
        Text(
            text = info,
            color = Color.Gray,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
    }
}
