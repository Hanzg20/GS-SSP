package com.goldsky.ssp.ev.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goldsky.ssp.ev.EvViewModel
import com.goldsky.ssp.ev.ui.components.ChargingGauge
import com.goldsky.ssp.ev.ui.theme.*

@Composable
fun EvMainContainer(viewModel: EvViewModel) {
    val state by viewModel.uiState.collectAsState()

    EvChargingTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Background futuristic pattern or subtle gradient could go here
                
                Crossfade(targetState = state, label = "EvScreenTransition") { currentState ->
                    when (currentState) {
                        is EvViewModel.EvState.Idle -> IdleScreen(onPlugIn = { viewModel.onCablePlugged() })
                        is EvViewModel.EvState.CablePlugged -> PluggedInScreen(onPay = { viewModel.startAuthorization() })
                        is EvViewModel.EvState.Authorizing -> AuthorizingScreen()
                        is EvViewModel.EvState.Charging -> ChargingDashboard(currentState, onStop = { viewModel.stopChargingManual() })
                        is EvViewModel.EvState.Finishing -> SummaryScreen(currentState.finalKwh, currentState.finalCost)
                        is EvViewModel.EvState.Error -> Text("Error: ${currentState.message}")
                    }
                }

                // Top Branding Header
                EvHeader()
            }
        }
    }
}

@Composable
fun EvHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("GOLDSKY EV", color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp)
            Text("STATION #08 - OTTAWA", color = EvNeonGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        
        Surface(
            color = Color.White.copy(alpha = 0.05f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                "EN | FR", 
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                color = Color.White,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
fun IdleScreen(onPlugIn: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("READY TO CHARGE", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black)
        Spacer(modifier = Modifier.height(16.dp))
        Text("PLEASE CONNECT THE CABLE\nTO YOUR VEHICLE", color = Color.Gray, textAlign = TextAlign.Center)
        
        Spacer(modifier = Modifier.height(48.dp))
        
        Button(
            onClick = onPlugIn,
            colors = ButtonDefaults.buttonColors(containerColor = EvElectricBlue)
        ) {
            Text("SIMULATE PLUG-IN", modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}

@Composable
fun PluggedInScreen(onPay: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("CABLE CONNECTED", color = EvNeonGreen, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        Text("PLEASE TAP YOUR CARD\nTO AUTHORIZE $50.00 DEPOSIT", color = Color.White, textAlign = TextAlign.Center)
        
        Spacer(modifier = Modifier.height(48.dp))
        
        Button(
            onClick = onPay,
            modifier = Modifier.height(60.dp).fillMaxWidth(0.6f),
            colors = ButtonDefaults.buttonColors(containerColor = EvNeonGreen)
        ) {
            Text("TAP TO START", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 18.sp)
        }
    }
}

@Composable
fun AuthorizingScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(color = EvNeonGreen, strokeWidth = 6.dp, modifier = Modifier.size(64.dp))
        Spacer(modifier = Modifier.height(24.dp))
        Text("AUTHORIZING...", color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ChargingDashboard(data: EvViewModel.EvState.Charging, onStop: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left: Gauges & Main Info
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            ChargingGauge(data.progress, data.powerKw)
        }
        
        // Right: Detailed Telemetry Cards
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TelemetryCard("SESSION COST", "$${String.format("%.2f", data.cost)}", EvGold)
            TelemetryCard("ENERGY DELIVERED", "${String.format("%.3f", data.kwh)} kWh", EvNeonGreen)
            
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(modifier = Modifier.weight(1f)) { SmallMetric("VOLTAGE", "${data.voltageV.toInt()} V") }
                Box(modifier = Modifier.weight(1f)) { SmallMetric("CURRENT", "${data.currentA.toInt()} A") }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("STOP CHARGING", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun TelemetryCard(label: String, value: String, accentColor: Color) {
    Surface(
        color = EvSurface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(label, color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(value, color = accentColor, fontSize = 32.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
fun SmallMetric(label: String, value: String) {
    Surface(
        color = EvSurface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun SummaryScreen(kwh: Double, cost: Double) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("CHARGING COMPLETE", color = EvNeonGreen, fontSize = 28.sp, fontWeight = FontWeight.Black)
        Spacer(modifier = Modifier.height(32.dp))
        
        Surface(color = EvSurface, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth(0.5f)) {
            Column(modifier = Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("TOTAL COST", color = Color.Gray, fontSize = 14.sp)
                Text("$${String.format("%.2f", cost)}", color = EvGold, fontSize = 48.sp, fontWeight = FontWeight.Black)
                
                Divider(modifier = Modifier.padding(vertical = 16.dp), color = Color.White.copy(alpha = 0.1f))
                
                Text("TOTAL ENERGY", color = Color.Gray, fontSize = 12.sp)
                Text("${String.format("%.2f", kwh)} kWh", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
        
        Spacer(modifier = Modifier.height(48.dp))
        Text("THANK YOU FOR USING GOLDSKY", color = Color.Gray, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
