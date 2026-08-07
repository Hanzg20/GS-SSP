package com.pax.dal;

public interface ISys {
    String getTermSerial();
    String getFirmwareVersion();
    void reboot();
    void setScreenBrightness(int value);
}
