package com.pax.dal;

import com.pax.dal.entities.EUartNumber;

public interface IDAL {
    IUart getUart(EUartNumber uartNumber);
    IScanner getScanner();
    IPicc getPicc();
}
