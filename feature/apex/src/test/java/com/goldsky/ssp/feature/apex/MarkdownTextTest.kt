package com.goldsky.ssp.feature.apex

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTextTest {

    @Test
    fun testBoldKeywordsAreParsedAndDelimitersStripped() {
        val input = "**Gross Sales**: $1,250.00"
        val result = parseMarkdownToAnnotatedString(input)

        // 1. Raw '**' syntax should be stripped
        assertEquals("Gross Sales: $1,250.00", result.text)

        // 2. "Gross Sales" should have bold span style
        val styles = result.spanStyles
        assertTrue("Expected at least one style span", styles.isNotEmpty())
        val boldSpan = styles.firstOrNull { it.start == 0 && it.end == 11 }
        assertTrue("Span 0..11 should exist", boldSpan != null)
        assertEquals(FontWeight.Bold, boldSpan?.item?.fontWeight)
    }

    @Test
    fun testMultipleBoldKeywordsInOneLine() {
        val input = "Today **Coffee** sold 20 cups and **Tea** sold 15 cups."
        val result = parseMarkdownToAnnotatedString(input)

        assertEquals("Today Coffee sold 20 cups and Tea sold 15 cups.", result.text)
        val coffeeSpan = result.spanStyles.firstOrNull { it.item.fontWeight == FontWeight.Bold && it.start == 6 && it.end == 12 }
        assertTrue("Coffee should be bold", coffeeSpan != null)

        val teaSpan = result.spanStyles.firstOrNull { it.item.fontWeight == FontWeight.Bold && it.start == 30 && it.end == 33 }
        assertTrue("Tea should be bold", teaSpan != null)
    }

    @Test
    fun testHeadersAndBullets() {
        val input = """
            # Sales Highlights
            * **Item A**: $50
            - **Item B**: $30
        """.trimIndent()

        val result = parseMarkdownToAnnotatedString(input)
        val lines = result.text.lines()

        assertEquals(3, lines.size)
        assertEquals("Sales Highlights", lines[0])
        assertEquals("• Item A: $50", lines[1])
        assertEquals("• Item B: $30", lines[2])

        // Check header style
        val headerSpan = result.spanStyles.firstOrNull { it.item.fontWeight == FontWeight.ExtraBold }
        assertTrue("Header should have ExtraBold style", headerSpan != null)
    }

    @Test
    fun testSnakeCaseNotMangledAsItalics() {
        val input = "Check order_id and sales_count_today."
        val result = parseMarkdownToAnnotatedString(input)

        // Snake case should remain intact without italic spans
        assertEquals("Check order_id and sales_count_today.", result.text)
        val italicSpans = result.spanStyles.filter { it.item.fontStyle != null }
        assertTrue("Should have no italic spans for snake_case", italicSpans.isEmpty())
    }

    @Test
    fun testInlineCodeFormatting() {
        val input = "Run code `REFUND_ORDER` now."
        val result = parseMarkdownToAnnotatedString(input)

        assertEquals("Run code REFUND_ORDER now.", result.text)
        val codeSpan = result.spanStyles.firstOrNull { it.item.fontFamily == FontFamily.Monospace }
        assertTrue("Inline code should have monospace font", codeSpan != null)
    }
}
