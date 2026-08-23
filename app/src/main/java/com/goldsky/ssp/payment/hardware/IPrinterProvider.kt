package com.goldsky.ssp.payment.hardware

/**
 * Common interface for thermal printer operations.
 */
interface IPrinterProvider {
    /**
     * Resets and initializes the printer.
     */
    fun init(): Boolean

    /**
     * Adds a line of text to the print buffer.
     */
    fun addText(text: String): Boolean

    /**
     * Commits the buffer to physical print and feeds paper.
     */
    fun startPrint(): Boolean

    /**
     * Feeds the paper by specific lines.
     */
    fun feedPaper(lines: Int): Boolean

    /**
     * Checks if the printer has paper.
     */
    fun hasPaper(): Boolean
}
