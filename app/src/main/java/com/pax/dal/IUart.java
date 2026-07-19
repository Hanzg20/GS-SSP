package com.pax.dal;

public interface IUart {
    void open();
    void init(int baudRate, int dataBits, int parity, int stopBits, int flowControl);
    void send(byte[] data, int length);
    void close();
}
