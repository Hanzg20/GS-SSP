package com.goldsky.ssp.feature.retail.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One tab of a top tab row (Iris: Quick's Tables / KEYPAD / LIBRARY / DELIVERY, the
 * Journal). A 360 dp handheld (Q2) with the dine-in Tables tab has four ~90 dp tabs:
 * Tab's own 16 dp side padding left "DELIVERY" too little room and it wrapped onto two
 * lines. Narrow padding and one line, shrinking the text only if a label still doesn't fit.
 */
@Composable
fun PosTab(selected: Boolean, label: String, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Tab(selected = selected, onClick = onClick, modifier = Modifier.height(52.dp)) {
        var size by remember(label) { mutableStateOf(15.sp) }
        Text(
            label, fontWeight = FontWeight.Bold, color = color, fontSize = size, maxLines = 1, softWrap = false,
            modifier = Modifier.padding(horizontal = 4.dp),
            onTextLayout = { if (it.didOverflowWidth && size > 11.sp) size = (size.value - 1).sp }
        )
    }
}
