package com.pax.dal;

public interface IPrinter {
    void init();
    void addText(String text);

    /**
     * Executes the queued print job (feeds paper, cuts if supported).
     */
    void step();

    /**
     * Returns 0 for normal/ready; non-zero indicates a fault (e.g. out of paper).
     */
    int getStatus();
}
