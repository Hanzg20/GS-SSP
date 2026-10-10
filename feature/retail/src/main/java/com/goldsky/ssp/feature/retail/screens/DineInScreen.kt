package com.goldsky.ssp.feature.retail.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goldsky.ssp.feature.retail.ui.GoldSkyCyan
import com.goldsky.ssp.payment.DeviceRepository

data class Table(val id: String, val name: String, var isOccupied: Boolean = false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DineInScreen(onTableSelected: (Table) -> Unit) {
    val isCyber = DeviceRepository.getThemeStyle() == DeviceRepository.UiThemeStyle.CYBER
    val tables = remember {
        mutableStateListOf(
            Table("1", "T-01"), Table("2", "T-02", true),
            Table("3", "T-03"), Table("4", "T-04"),
            Table("5", "T-05", true), Table("6", "T-06"),
            Table("7", "T-07"), Table("8", "T-08"),
            Table("9", "VIP")
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("DINE-IN MANAGEMENT", fontWeight = FontWeight.Black, letterSpacing = 1.sp) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = if (isCyber) Color(0xFF151D30) else MaterialTheme.colorScheme.background,
                    titleContentColor = if (isCyber) GoldSkyCyan else MaterialTheme.colorScheme.primary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(if (isCyber) Color(0xFF0B0F19) else MaterialTheme.colorScheme.background)
        ) {
            Text(
                "SELECT TABLE",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.labelSmall,
                color = if (isCyber) GoldSkyCyan else MaterialTheme.colorScheme.secondary,
                letterSpacing = 2.sp
            )
            
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(tables) { table ->
                    TableCard(table = table, isCyber = isCyber, onClick = { onTableSelected(table) })
                }
            }
        }
    }
}

@Composable
fun TableCard(table: Table, isCyber: Boolean = false, onClick: () -> Unit) {
    val cardBg = when {
        table.isOccupied -> if (isCyber) Color(0xFF3B1219) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
        isCyber -> Color(0xFF162032)
        else -> MaterialTheme.colorScheme.surface
    }
    val borderCol = when {
        table.isOccupied -> Color(0xFFFF5252)
        isCyber -> Color(0xFF26354F)
        else -> MaterialTheme.colorScheme.outline
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = cardBg,
        border = BorderStroke(0.5.dp, borderCol),
        modifier = Modifier.aspectRatio(1f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    table.name, 
                    fontWeight = FontWeight.Light, 
                    fontSize = 22.sp,
                    color = if (table.isOccupied) Color(0xFFFF5252) else if (isCyber) Color.White else MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    if (table.isOccupied) "OCCUPIED" else "AVAILABLE",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    letterSpacing = 1.sp,
                    color = if (table.isOccupied) Color(0xFFFF5252) else if (isCyber) GoldSkyCyan else MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

