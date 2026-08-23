package com.goldsky.ssp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
            Table("1", "Table 01"), Table("2", "Table 02", true),
            Table("3", "Table 03"), Table("4", "Table 04"),
            Table("5", "Table 05", true), Table("6", "Table 06"),
            Table("7", "Table 07"), Table("8", "Table 08"),
            Table("9", "VIP Room")
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text("DINE-IN SERVICE", fontWeight = FontWeight.Bold) })
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            Text(
                "Select a Table",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.titleLarge
            )
            
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize().padding(8.dp),
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
        shape = RoundedCornerShape(16.dp),
        color = if (table.isOccupied) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.aspectRatio(1f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(table.name, fontWeight = FontWeight.Black, fontSize = 18.sp)
                Text(
                    if (table.isOccupied) "BUSY" else "FREE",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (table.isOccupied) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
