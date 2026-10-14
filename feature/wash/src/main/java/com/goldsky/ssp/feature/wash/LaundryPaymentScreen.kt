package com.goldsky.ssp.feature.wash

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.goldsky.ssp.ui.theme.GoldSkyTheme

data class LaundryMachine(
    val id: String,
    val name: String,
    val type: String, // "Washer" or "Dryer"
    val priceCents: Int,
    val icon: String = "🧺"
) {
    val formattedPrice: String
        get() = "$${String.format("%.2f", priceCents / 100.0)}"
}

data class LaundryCycle(
    val id: String,
    val name: String,
    val durationMinutes: Int,
    val extraCostCents: Int = 0
)

@Composable
fun LaundryPaymentScreen(
    machines: List<LaundryMachine> = listOf(
        LaundryMachine("m1", "Washer 1", "Washer", 300),
        LaundryMachine("m2", "Washer 2", "Washer", 300),
        LaundryMachine("m3", "Dryer 1", "Dryer", 250),
        LaundryMachine("m4", "Dryer 2", "Dryer", 250),
    ),
    cycles: List<LaundryCycle> = listOf(
        LaundryCycle("normal", "Normal", 30, 0),
        LaundryCycle("delicate", "Delicate", 40, 50),
        LaundryCycle("quick", "Quick Wash", 20, 0),
    ),
    onStartCycle: (machine: LaundryMachine, cycle: LaundryCycle) -> Unit = { _, _ -> }
) {
    var selectedMachine by remember { mutableStateOf(machines.firstOrNull()) }
    var selectedCycle by remember { mutableStateOf(cycles.firstOrNull()) }

    GoldSkyTheme(dark = true) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0D1117)) // Sleek industrial dark terminal background
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Header
                Column(horizontalAlignment = Alignment.Start) {
                    Text(
                        text = "LAUNDRY PAYMENT",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Select machine and cycle to begin",
                        color = Color(0xFF8B949E),
                        fontSize = 13.sp
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Select Machine Section
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "SELECT MACHINE",
                        color = Color(0xFFC9D1D9),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Grid of 2x2 machines
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        machines.take(2).forEach { machine ->
                            MachineCard(
                                machine = machine,
                                isSelected = selectedMachine?.id == machine.id,
                                modifier = Modifier.weight(1.f),
                                onClick = { selectedMachine = machine }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        machines.drop(2).take(2).forEach { machine ->
                            MachineCard(
                                machine = machine,
                                isSelected = selectedMachine?.id == machine.id,
                                modifier = Modifier.weight(1.f),
                                onClick = { selectedMachine = machine }
                            )
                        }
                    }
                }

                // Cycle Section
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "SELECT CYCLE",
                        color = Color(0xFFC9D1D9),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        cycles.forEach { cycle ->
                            CyclePill(
                                cycle = cycle,
                                isSelected = selectedCycle?.id == cycle.id,
                                modifier = Modifier.weight(1.f),
                                onClick = { selectedCycle = cycle }
                            )
                        }
                    }
                }

                // Action Button & Footer
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val totalPrice = (selectedMachine?.priceCents ?: 0) + (selectedCycle?.extraCostCents ?: 0)
                    val formattedTotal = "$${String.format("%.2f", totalPrice / 100.0)}"

                    Button(
                        onClick = {
                            if (selectedMachine != null && selectedCycle != null) {
                                onStartCycle(selectedMachine!!, selectedCycle!!)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clip(RoundedCornerShape(28.dp)),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00D2FF) // Vibrant Cyan / Teal
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "START CYCLE",
                                color = Color(0xFF0A0E14),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.1.sp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Surface(
                                color = Color(0xFF0A0E14).copy(alpha = 0.15f),
                                shape = CircleShape
                            ) {
                                Text(
                                    text = formattedTotal,
                                    color = Color(0xFF0A0E14),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Tap card or scan QR",
                        color = Color(0xFF8B949E),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun MachineCard(
    machine: LaundryMachine,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSelected) Color(0xFF1F6FEB) else Color(0xFF161B22) // Vibrant blue when selected
    val borderColor = if (isSelected) Color(0xFF58A6FF) else Color(0xFF30363D)

    Surface(
        modifier = modifier
            .height(80.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(width = if (isSelected) 2.dp else 1.dp, color = borderColor, shape = RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = backgroundColor,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = machine.name,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .background(Color.White, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "✓", color = Color(0xFF1F6FEB), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Text(
                text = machine.formattedPrice,
                color = if (isSelected) Color(0xFFE6EDF3) else Color(0xFF8B949E),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun CyclePill(
    cycle: LaundryCycle,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSelected) Color(0xFF00D2FF) else Color(0xFF161B22)
    val textColor = if (isSelected) Color(0xFF0A0E14) else Color(0xFFC9D1D9)

    Surface(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .border(
                width = if (isSelected) 0.dp else 1.dp,
                color = Color(0xFF30363D),
                shape = RoundedCornerShape(24.dp)
            )
            .clickable(onClick = onClick),
        color = backgroundColor,
        shape = RoundedCornerShape(24.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = cycle.name,
                color = textColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                textAlign = TextAlign.Center
            )
        }
    }
}
