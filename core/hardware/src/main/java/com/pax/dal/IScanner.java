package com.pax.dal;

public interface IScanner {
    void open();
    void close();
    void startScan(int timeout, ScanListener listener);
    void stopScan();
    void setLed(boolean enabled);

    interface ScanListener {
        void onSuccess(String barcode);
        void onFail();
    }
}
