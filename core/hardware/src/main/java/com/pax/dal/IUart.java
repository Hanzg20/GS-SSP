package com.pax.dal;

public interface IUart {
    void open();
    void init(int baudRate, int dataBits, int parity, int stopBits, int flowControl);
    void send(byte[] data, int length);

    /**
     * Blocking read with timeout. Returns the number of bytes read into
     * {@code buffer}, or 0 if no data arrived before {@code timeoutMs} elapsed.
     */
    int receive(byte[] buffer, int timeoutMs);

    void close();
}
