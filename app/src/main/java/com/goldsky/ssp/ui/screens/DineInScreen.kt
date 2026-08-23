package com.goldsky.ssp.ui.screens

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

data class Table(val id: String, val name: String, var isOccupied: Boolean = false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DineInScreen(onTableSelected: (Table) -> Unit) {
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
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Text(
                "SELECT TABLE",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                letterSpacing = 2.sp
            )
            
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(tables) { table ->
                    TableCard(table = table, onClick = { onTableSelected(table) })
                }
            }
        }
    }
}

@Composable
fun TableCard(table: Table, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (table.isOccupied) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            0.5.dp, 
            if (table.isOccupied) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
        ),
        modifier = Modifier.aspectRatio(1f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    table.name, 
                    fontWeight = FontWeight.Light, 
                    fontSize = 22.sp,
                    color = if (table.isOccupied) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    if (table.isOccupied) "OCCUPIED" else "AVAILABLE",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    letterSpacing = 1.sp,
                    color = if (table.isOccupied) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
