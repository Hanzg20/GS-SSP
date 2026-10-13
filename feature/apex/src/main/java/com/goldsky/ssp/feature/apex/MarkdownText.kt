package com.goldsky.ssp.feature.apex

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Renders Markdown-formatted text with bold keywords, italics, headers, code,
 * and bullet points cleanly parsed into an [AnnotatedString], eliminating raw
 * markdown syntax symbols like '**'.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
    boldColor: Color = Color.Unspecified
) {
    val annotatedString = parseMarkdownToAnnotatedString(
        text = markdown,
        boldColor = boldColor
    )
    Text(
        text = annotatedString,
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        lineHeight = lineHeight
    )
}

/**
 * Parses a markdown string into Jetpack Compose [AnnotatedString].
 */
fun parseMarkdownToAnnotatedString(
    text: String,
    boldColor: Color = Color.Unspecified
): AnnotatedString = buildAnnotatedString {
    val lines = text.lines()
    lines.forEachIndexed { index, rawLine ->
        var line = rawLine
        var isHeader = false
        var headerLevel = 0

        // 1. Detect and strip Header markers (# Title)
        val headerMatch = Regex("""^(#{1,6})\s+(.*)""").find(line)
        if (headerMatch != null) {
            isHeader = true
            headerLevel = headerMatch.groupValues[1].length
            line = headerMatch.groupValues[2]
        } else {
            // 2. Normalize bullet points (- item, * item, + item)
            val bulletMatch = Regex("""^(\s*)[*+-]\s+(.*)""").find(line)
            if (bulletMatch != null) {
                val indent = bulletMatch.groupValues[1]
                line = "$indent• " + bulletMatch.groupValues[2]
            }
        }

        val lineStart = length
        parseInlineMarkdown(line, boldColor)
        val lineEnd = length

        if (isHeader) {
            val scale = when (headerLevel) {
                1 -> 1.25f
                2 -> 1.15f
                else -> 1.08f
            }
            addStyle(
                SpanStyle(
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = (15 * scale).sp
                ),
                lineStart,
                lineEnd
            )
        }

        if (index < lines.size - 1) {
            append("\n")
        }
    }
}

private val inlineRegex = Regex("""(\*\*\*(.+?)\*\*\*|\*\*(.+?)\*\*|__(.+?)__|`([^`]+?)`|\*([^*]+?)\*|\b_([^_]+?)_\b)""")

private fun androidx.compose.ui.text.AnnotatedString.Builder.parseInlineMarkdown(
    line: String,
    boldColor: Color
) {
    var cursor = 0
    val matches = inlineRegex.findAll(line)

    for (match in matches) {
        val range = match.range
        if (range.first > cursor) {
            append(line.substring(cursor, range.first))
        }

        val raw = match.value
        val (innerText, spanStyle) = when {
            raw.startsWith("***") && raw.endsWith("***") -> {
                val content = raw.substring(3, raw.length - 3)
                content to SpanStyle(
                    fontWeight = FontWeight.Bold,
                    fontStyle = FontStyle.Italic,
                    color = if (boldColor != Color.Unspecified) boldColor else Color.Unspecified
                )
            }
            raw.startsWith("**") && raw.endsWith("**") -> {
                val content = raw.substring(2, raw.length - 2)
                content to SpanStyle(
                    fontWeight = FontWeight.Bold,
                    color = if (boldColor != Color.Unspecified) boldColor else Color.Unspecified
                )
            }
            raw.startsWith("__") && raw.endsWith("__") -> {
                val content = raw.substring(2, raw.length - 2)
                content to SpanStyle(
                    fontWeight = FontWeight.Bold,
                    color = if (boldColor != Color.Unspecified) boldColor else Color.Unspecified
                )
            }
            raw.startsWith("`") && raw.endsWith("`") -> {
                val content = raw.substring(1, raw.length - 1)
                content to SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    background = Color(0x22888888)
                )
            }
            raw.startsWith("*") && raw.endsWith("*") -> {
                val content = raw.substring(1, raw.length - 1)
                content to SpanStyle(fontStyle = FontStyle.Italic)
            }
            raw.startsWith("_") && raw.endsWith("_") -> {
                val content = raw.substring(1, raw.length - 1)
                content to SpanStyle(fontStyle = FontStyle.Italic)
            }
            else -> raw to SpanStyle()
        }

        val start = length
        append(innerText)
        val end = length
        addStyle(spanStyle, start, end)

        cursor = range.last + 1
    }

    if (cursor < line.length) {
        append(line.substring(cursor))
    }
}
