package com.goldsky.ssp.feature.retail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.goldsky.ssp.feature.retail.R
import com.goldsky.ssp.feature.retail.viewmodel.RetailViewModel
import com.goldsky.ssp.payment.DeviceRepository

/** Settings > Business type is a quick-service restaurant. */
fun isQuickService() = DeviceRepository.getBusinessType() == DeviceRepository.BusinessType.QUICK_SERVICE

/**
 * Quick-service checkout extras in the Material theme (Iris): dine-in /
 * take-out and the order note, both printed on the kitchen ticket.
 */
@Composable
fun QuickServiceOrderBar(viewModel: RetailViewModel, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    var editingNote by remember { mutableStateOf(false) }
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.clip(RoundedCornerShape(10.dp)).background(scheme.surfaceVariant).padding(3.dp)) {
            listOf(false to R.string.retail_dine_in, true to R.string.retail_takeout).forEach { (takeout, label) ->
                val selected = viewModel.takeout == takeout
                Box(
                    Modifier.height(36.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (selected) scheme.primary else Color.Transparent)
                        .clickable { viewModel.takeout = takeout }.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) { Text(stringResource(label), color = if (selected) scheme.onPrimary else scheme.onSurface, fontWeight = FontWeight.Bold) }
            }
        }
        Row(
            Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, scheme.outline, RoundedCornerShape(10.dp))
                .clickable { editingNote = true }.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Edit, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            val note = viewModel.orderNote
            Text(
                note.ifBlank { stringResource(R.string.retail_add_note) },
                color = if (note.isBlank()) scheme.onSurfaceVariant else scheme.onSurface,
                fontWeight = if (note.isBlank()) FontWeight.Normal else FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp
            )
        }
    }
    if (editingNote) OrderNoteDialog(initial = viewModel.orderNote, onDone = { viewModel.orderNote = it.trim(); editingNote = false })
}

/** The order note: free text plus one-tap common requests ("no green onion"). */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun OrderNoteDialog(initial: String, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val quick = stringArrayResource(R.array.retail_quick_notes)
    Dialog(onDismissRequest = { onDone(text) }) {
        Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 8.dp) {
            Column(Modifier.widthIn(max = 520.dp).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.retail_order_note), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(120) },
                    placeholder = { Text(stringResource(R.string.retail_note_hint)) },
                    modifier = Modifier.fillMaxWidth(), minLines = 2
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    quick.forEach { q ->
                        AssistChip(onClick = {
                            text = when {
                                text.isBlank() -> q
                                text.contains(q) -> text
                                else -> text.trimEnd().trimEnd(',', '，') + ", " + q
                            }
                        }, label = { Text(q) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { text = "" }, modifier = Modifier.weight(1f).height(48.dp)) { Text(stringResource(R.string.retail_clear)) }
                    Button(onClick = { onDone(text) }, modifier = Modifier.weight(2f).height(48.dp)) { Text(stringResource(R.string.retail_done), fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

/** The call number, big, for the payment-success dialog. */
@Composable
fun OrderNumberBlock(number: Int, color: Color = Color.Unspecified) {
    Column {
        Text(stringResource(R.string.retail_order_number), fontSize = 16.sp, color = color)
        Text("%03d".format(number), fontSize = 56.sp, fontWeight = FontWeight.Black, color = color)
    }
}
