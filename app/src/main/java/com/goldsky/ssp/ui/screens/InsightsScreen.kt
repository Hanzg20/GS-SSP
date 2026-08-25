package com.goldsky.ssp.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goldsky.ssp.ui.theme.GoldSkyBlue

data class AiMessage(val text: String, val isUser: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen() {
    var inputText by remember { mutableStateOf("") }
    val messages = remember { 
        mutableStateListOf(
            AiMessage("Hello! I'm your GoldSky AI assistant. How can I help you with your business today?", false)
        ) 
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, null, tint = GoldSkyBlue)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("AI INSIGHTS", fontWeight = FontWeight.ExtraBold, color = GoldSkyBlue)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.padding(16.dp),
                shape = RoundedCornerShape(32.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text("Ask about sales, trends...") },
                        modifier = Modifier.weight(1f),
                        colors = TextFieldDefaults.textFieldColors(
                            containerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        )
                    )
                    IconButton(onClick = { /* Simple intent matching mock */
                        if (inputText.isNotBlank()) {
                            messages.add(AiMessage(inputText, true))
                            val reply = processMockIntent(inputText)
                            messages.add(AiMessage(reply, false))
                            inputText = ""
                        }
                    }) {
                        Icon(Icons.Default.Send, null, tint = GoldSkyBlue)
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                QuickPrompts { prompt ->
                    messages.add(AiMessage(prompt, true))
                    messages.add(AiMessage(processMockIntent(prompt), false))
                }
            }
            items(messages) { msg ->
                ChatBubble(msg)
            }
        }
    }
}

@Composable
fun ChatBubble(message: AiMessage) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (message.isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Surface(
            color = if (message.isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (message.isUser) 16.dp else 0.dp,
                bottomEnd = if (message.isUser) 0.dp else 16.dp
            ),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Text(
                text = message.text,
                modifier = Modifier.padding(12.dp),
                color = if (message.isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun QuickPrompts(onSelect: (String) -> Unit) {
    val prompts = listOf("Today's Summary", "Top Selling Product", "Tip Trends")
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        prompts.forEach { text ->
            AssistChip(
                onClick = { onSelect(text) },
                label = { Text(text, fontSize = 10.sp) },
                colors = AssistChipDefaults.assistChipColors(labelColor = GoldSkyBlue)
            )
        }
    }
}

private fun processMockIntent(input: String): String {
    val low = input.lowercase()
    return when {
        low.contains("summary") || low.contains("营收") -> "Total sales: $1,245.00. Total expenses: $230.50. Net: $1,014.50."
        low.contains("top") || low.contains("最好") -> "Your top selling product is 'Coffee Latte' with 42 units sold."
        low.contains("tip") || low.contains("小费") -> "Average tip percentage today is 18.5%. Great job!"
        low.contains("expense") || low.contains("支出") -> "Most recent expense: $45.20 at 'Office Supply Co'."
        else -> "I'm analyzing that trend for you. Based on recent data, evening sales are peak."
    }
}
