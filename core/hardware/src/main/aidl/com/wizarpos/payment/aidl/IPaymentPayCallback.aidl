// OPC (com.wizarpos.opc) progress callback, read from OPC 2.17.3's
// IPaymentPayCallback$Stub (2026-10-25): one method, code 1.
package com.wizarpos.payment.aidl;

interface IPaymentPayCallback {
    void process(int code, String data);
}
