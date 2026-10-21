package com.goldsky.ssp.payment

import java.util.Calendar
import java.util.Locale

/**
 * Reads the four bookkeeping fields off one receipt photo's text (the Journal, 2026-10-20):
 * store, date, total and GST/HST, plus the store's GST/HST number. A long receipt is folded
 * so its head meets its foot -- one photo, no stitching, no line items.
 *
 * Input: the OCR's text lines with their boxes. ML Kit often reads a right-aligned price
 * column as a block of its own, so "TOTAL" and "45.20" arrive as separate lines; [rows]
 * puts the pieces at the same height back into one row before anything is looked for.
 * Nothing here guesses: what isn't found stays null and is flagged for the owner to fill in.
 */
object ReceiptParser {

    /** One OCR text line and its box, in image pixels. */
    data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val centerY: Int get() = (top + bottom) / 2
        val height: Int get() = (bottom - top).coerceAtLeast(1)
    }

    enum class Flag {
        /** No total line found: the owner types the amount. */
        TOTAL_MISSING,
        /** Subtotal + tax doesn't come to the total. */
        TOTAL_MISMATCH,
        /** No GST/HST line: tax unknown (owner fills it in, or there was none). */
        TAX_MISSING,
        /** Tax isn't a usual share of the amount before tax (4-16%): misread? */
        TAX_ODD,
        /** No date on it: today's date was put in. */
        DATE_MISSING,
        /** $100 or more without a GST/HST number: not enough for an input tax credit. */
        HST_NUMBER_MISSING,
        /** $500 or more: an input tax credit also needs the items -- keep the paper or photograph it unfolded. */
        LARGE_PURCHASE,
        /** Some fields came from 小海狸 (the rules found no total): check them against the photo. */
        AI_FILLED,
        /** A return / refund slip (negative total, "REFUND"): money back, not an expense. No total is filled in. */
        REFUND,
        /** Barely anything legible (no total, under 3 amounts): blurred or too far -- retake it. */
        UNREADABLE,
    }

    data class Fields(
        val vendor: String?,
        /** yyyy-MM-dd */
        val date: String?,
        val totalCents: Int?,
        val taxCents: Int?,
        val subtotalCents: Int?,
        val hstNumber: String?,
        val flags: Set<Flag>,
    )

    // Money: 12.34, 1,234.56, $12.34, 12,34 (a comma for the decimals); not 13.00% (a rate).
    private val MONEY = Regex("""(?<![\d.,])-?\$?\s?(\d{1,3}(?:,\d{3})+|\d+)[.,](\d{2})(?![\d%])""")
    private val SUBTOTAL = Regex("""SUB\s*-?\s*TOTAL|小计|小計""")
    private val TOTAL = Regex("""TOTAL|AMOUNT\s+DUE|BALANCE\s+DUE|合计|合計|总计|總計|总额|總額|应付|應付|实付|實付""")
    // "Total" lines that aren't the amount paid.
    private val NOT_TOTAL = Regex("""SAVING|ITEMS?\b|QTY|QUANTITY|DISCOUNT|POINTS|NUMBER OF|TIPS?\b|TENDER""")
    private val TAX = Regex("""\b(HST|GST|QST|PST|TAX)\b|税|稅""")
    private val TOTAL_TAX = Regex("""TOTAL\s+(TAX|HST|GST)|(TAX|HST|GST)\s+TOTAL|税额|稅額""")
    // "GROCERY NON TAX" (Geelano, 2026-10-20): an item's tax status, not a tax line.
    private val NOT_TAX = Regex("""BEFORE\s+TAX|PRE-?TAX|EXEMPT|NON[-\s]?TAX|TAXABLE|NO\s+TAX|免税|免稅""")
    private val NOT_VENDOR = Regex("""WELCOME|RECEIPT|INVOICE|TEL\b|PHONE|WWW\.|HTTP|\.COM|CUSTOMER|COPY|欢迎|歡迎|收据|收據|发票|發票""")
    // A return / refund slip. "REFUND" alone isn't enough: "MAX REFUND VALUE 25.00" is a return
    // policy on an ordinary sale (Home Hardware, 2026-10-20) -- it takes the money going back.
    private val REFUND_SLIP = Regex("""RECEIPTED\s+RETURN|\bRETURN\s+TRANSACTION|TYPE\s*:?\s*REFUND|退款单|退货单|退貨單""")
    private val REFUND_WORD = Regex("""\bREFUND\b|退款""")
    private val MONEY_BACK = Regex("""-\s?\$?\d+[.,]\d{2}|\(\s?\$?\d+[.,]\d{2}\s?\)""")
    // Dates that aren't this purchase's: the original sale on a return, expiries, return-by, best-before.
    private val OTHER_DATE = Regex("""ORIG|EXPIR|POLICY|RETURN\s*BY|RETURNS?\s+UNTIL|VALID|BEST\s+BEFORE|BIRTH|DOB|原|有效|到期""")
    private val TIME = Regex("""\b\d{1,2}:\d{2}\b""")
    private val HST_NUMBER = Regex("""\b(\d{9})\s*-?\s*RT\s*-?\s*(\d{4})\b""")

    /** Puts lines at the same height (their vertical centers inside each other's box) into one row, top to bottom. */
    fun rows(lines: List<OcrLine>): List<String> {
        val sorted = lines.filter { it.text.isNotBlank() }.sortedBy { it.centerY }
        val rows = mutableListOf<MutableList<OcrLine>>()
        for (l in sorted) {
            val row = rows.lastOrNull()
            val anchor = row?.first()
            if (anchor != null && kotlin.math.abs(l.centerY - anchor.centerY) <= minOf(anchor.height, l.height) / 2) row.add(l)
            else rows.add(mutableListOf(l))
        }
        return rows.map { r -> r.sortedBy { it.left }.joinToString("  ") { it.text.trim() } }
    }

    /** The money amounts in a row, in cents, left to right. */
    fun amounts(row: String): List<Int> = MONEY.findAll(row).mapNotNull { m ->
        val whole = m.groupValues[1].replace(",", "").toIntOrNull() ?: return@mapNotNull null
        val cents = whole * 100 + m.groupValues[2].toInt()
        if (m.value.trimStart().startsWith("-")) null else cents
    }.toList()

    /** [today]: yyyy-MM-dd, for picking a plausible date (not in the future, within a year). */
    fun parse(lines: List<OcrLine>, today: String): Fields = parseRows(rows(lines), today)

    /**
     * For finding the words only (amounts are always read from the row as printed): OCR
     * reads O as 0 inside words -- "T0TAL" (Costco, 2026-10-20) -- so a 0 touching a letter
     * is an O here.
     */
    private fun words(row: String): String = row.uppercase(Locale.ROOT).replace(Regex("""(?<=[A-Z])0|0(?=[A-Z])"""), "O")

    fun parseRows(rows: List<String>, today: String): Fields {
        val up = rows.map { words(it) }
        val printed = rows.map { it.uppercase(Locale.ROOT) }

        // Total: the last "total" row that isn't the subtotal or a count; its right-most amount,
        // or the next row's when the amount sits under it.
        var total: Int? = null
        for (i in up.indices.reversed()) {
            val r = up[i]
            if (!TOTAL.containsMatchIn(r) || SUBTOTAL.containsMatchIn(r) || NOT_TOTAL.containsMatchIn(r)) continue
            // "TOTAL TAX", "HST TOTAL": a tax row. "TOTAL (INCL. HST)" is the total.
            if (TOTAL_TAX.containsMatchIn(r)) continue
            total = amounts(printed[i]).lastOrNull() ?: up.getOrNull(i + 1)?.takeIf { !TOTAL.containsMatchIn(it) }?.let { amounts(printed[i + 1]).lastOrNull() }
            if (total != null) break
        }

        val subtotal = up.indexOfLast { SUBTOTAL.containsMatchIn(it) }.takeIf { it >= 0 }?.let { i ->
            amounts(printed[i]).lastOrNull() ?: printed.getOrNull(i + 1)?.let { amounts(it).lastOrNull() }
        }

        // Tax: one "total tax" row if there is one, else every GST/HST/PST/QST row added up.
        val taxRows = up.indices.filter { i -> val r = up[i]
            TAX.containsMatchIn(r) && !NOT_TAX.containsMatchIn(r) && !SUBTOTAL.containsMatchIn(r) && (!TOTAL.containsMatchIn(r) || TOTAL_TAX.containsMatchIn(r)) }
        // A tax word alone on its row ("HST:"): its amount sits on the next row.
        fun taxAmount(i: Int) = amounts(printed[i]).lastOrNull()
            ?: printed.getOrNull(i + 1)?.takeIf { !TOTAL.containsMatchIn(up[i + 1]) }?.let { amounts(it).lastOrNull() }
        val totalTaxRow = taxRows.lastOrNull { TOTAL_TAX.containsMatchIn(up[it]) }
        var tax = if (totalTaxRow != null) taxAmount(totalTaxRow)
        else taxRows.mapNotNull { taxAmount(it) }.takeIf { it.isNotEmpty() }?.sum()
        // A "tax" as big as the total was read off the wrong row.
        if (tax != null && total != null && tax >= total) tax = null
        // No tax line read, but a subtotal and a total that differ by a tax-sized amount: that's the tax.
        if (tax == null && subtotal != null && total != null && total > subtotal && (total - subtotal) * 5 <= total) tax = total - subtotal

        val hst = printed.firstNotNullOfOrNull { HST_NUMBER.find(it) }?.let { "${it.groupValues[1]} RT ${it.groupValues[2]}" }
        // This purchase's date: not a return's original sale or an expiry; one printed with a
        // time (the transaction stamp) first.
        val dated = rows.indices.filter { !OTHER_DATE.containsMatchIn(up[it]) }.mapNotNull { i -> findDate(rows[i], today)?.let { i to it } }
        val date = (dated.firstOrNull { TIME.containsMatchIn(rows[it.first]) } ?: dated.firstOrNull())?.second
        // A return / refund: money going back (a negative or bracketed total, "REFUND (54.24)",
        // "Trans Type REFUND"). No total is offered for it.
        val refund = up.indices.any { i ->
            REFUND_SLIP.containsMatchIn(up[i]) ||
                (REFUND_WORD.containsMatchIn(up[i]) && MONEY_BACK.containsMatchIn(printed[i])) ||
                (TOTAL.containsMatchIn(up[i]) && !SUBTOTAL.containsMatchIn(up[i]) && MONEY_BACK.containsMatchIn(printed[i]))
        }
        val vendor = rows.take(8).firstOrNull { r ->
            val letters = r.count { it.isLetter() }
            letters >= 3 && letters > r.count { it.isDigit() } && !NOT_VENDOR.containsMatchIn(words(r))
        }?.trim()?.take(120)

        if (refund) return withFlags(Fields(vendor, date, null, null, null, hst, emptySet())).let { it.copy(flags = it.flags + Flag.REFUND) }
        val read = withFlags(Fields(vendor, date, total, tax, subtotal, hst, emptySet()))
        // Hardly any amounts read and no total: the photo, not the rules (blurred, too far --
        // a full-page bill shot from a distance, 2026-10-20). Retake rather than type it all.
        val amountsRead = printed.sumOf { amounts(it).size }
        return if (total == null && amountsRead < 3) read.copy(flags = read.flags + Flag.UNREADABLE) else read
    }

    /**
     * Fills what the rules didn't find with the model's reading ([ai]: its amounts were already
     * checked to be printed on the receipt) and checks the result again. Never overwrites a
     * field the rules found.
     */
    fun merge(base: Fields, ai: Fields): Fields {
        val m = base.copy(
            vendor = base.vendor ?: ai.vendor, date = base.date ?: ai.date,
            totalCents = base.totalCents ?: ai.totalCents, taxCents = base.taxCents ?: ai.taxCents,
            hstNumber = base.hstNumber ?: ai.hstNumber
        )
        if (Flag.REFUND in base.flags) return base
        val filled = m != base
        return withFlags(m).let { if (filled) it.copy(flags = it.flags + Flag.AI_FILLED) else it }
    }

    private fun withFlags(f: Fields): Fields {
        val total = f.totalCents; val tax = f.taxCents; val subtotal = f.subtotalCents
        val flags = mutableSetOf<Flag>()
        if (total == null) flags += Flag.TOTAL_MISSING
        if (tax == null) flags += Flag.TAX_MISSING
        if (total != null && subtotal != null && tax != null && kotlin.math.abs(subtotal + tax - total) > 2) flags += Flag.TOTAL_MISMATCH
        if (total != null && tax != null && tax > 0) {
            val before = total - tax
            if (before <= 0 || tax * 100 < before * 4 || tax * 100 > before * 16) flags += Flag.TAX_ODD
        }
        if (f.date == null) flags += Flag.DATE_MISSING
        if (total != null && total >= 10_000 && f.hstNumber == null) flags += Flag.HST_NUMBER_MISSING
        if (total != null && total >= 50_000) flags += Flag.LARGE_PURCHASE
        return f.copy(flags = flags)
    }

    private val ISO = Regex("""\b(20\d{2})[-/.](\d{1,2})[-/.](\d{1,2})\b""")
    private val SLASH = Regex("""\b(\d{1,2})[-/.](\d{1,2})[-/.](\d{2}|\d{4})\b""")
    private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
    private val WORDY = Regex("""\b(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)[A-Z]*\.?\s+(\d{1,2}),?\s+(20\d{2})\b""")
    private val CJK = Regex("""(20\d{2})年(\d{1,2})月(\d{1,2})日""")
    // "Sep 14/2026" and "14 Sep 2026" (Geelano, 2026-10-20).
    private val WORDY_SLASH = Regex("""\b(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)[A-Z]*\.?\s*(\d{1,2})\s*/\s*(20\d{2})\b""")
    private val DAY_WORDY = Regex("""\b(\d{1,2})\s+(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)[A-Z]*\.?,?\s+(20\d{2})\b""")

    /**
     * A date in the row, as yyyy-MM-dd: 2026-10-16, 2026/10/16, 10/16/2026, 16/10/2026 or
     * 26/10/16 (yy/mm/dd, Shoppers) -- whichever reading is a real date no later than today
     * and within the last year, the closest to today when several are -- 10/16/26,
     * Oct 16, 2026, Sep 14/2026, 14 Sep 2026, 2026年10月16日.
     */
    fun findDate(row: String, today: String): String? {
        fun ok(y: Int, m: Int, d: Int): String? {
            if (m !in 1..12 || d !in 1..31) return null
            val c = Calendar.getInstance().apply { isLenient = false; clear(); set(y, m - 1, d) }
            if (runCatching { c.timeInMillis }.isFailure) return null
            val s = "%04d-%02d-%02d".format(y, m, d)
            val t = today
            val yearAgo = "%04d".format(t.take(4).toInt() - 1) + t.drop(4)
            return s.takeIf { it <= t && it >= yearAgo }
        }
        CJK.find(row)?.let { m -> ok(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())?.let { return it } }
        ISO.find(row)?.let { m -> ok(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())?.let { return it } }
        val upper = row.uppercase(Locale.ROOT)
        for (re in listOf(WORDY, WORDY_SLASH)) re.find(upper)?.let { m ->
            ok(m.groupValues[3].toInt(), MONTHS.indexOf(m.groupValues[1]) + 1, m.groupValues[2].toInt())?.let { return it }
        }
        DAY_WORDY.find(upper)?.let { m ->
            ok(m.groupValues[3].toInt(), MONTHS.indexOf(m.groupValues[2]) + 1, m.groupValues[1].toInt())?.let { return it }
        }
        SLASH.find(row)?.let { m ->
            val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt(); val c = m.groupValues[3]
            val y = c.toInt().let { if (it < 100) 2000 + it else it }
            val mdy = ok(y, a, b); val dmy = ok(y, b, a)
            // yy/mm/dd: only with two-digit parts all round ("26/07/02").
            val ymd = if (c.length == 2) ok(2000 + a, b, c.toInt()) else null
            return listOfNotNull(mdy, dmy, ymd).maxOrNull()
        }
        return null
    }
}
