package com.pax.dal;

public interface IDeviceControl {
    /**
     * Start the hardware watchdog.
     */
    void watchdogOpen();

    /**
     * Feed the hardware watchdog to reset the timeout.
     */
    void watchdogFeed();

    /**
     * Stop the hardware watchdog.
     */
    void watchdogClose();
}
