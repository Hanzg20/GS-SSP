// OPC (com.wizarpos.opc) payment service, action "com.wizarpos.payment.aidl.pay".
// WizarPOS gave no .aidl file; read from OPC 2.17.3's IPaymentPay$Stub.onTransact
// on 255 (2026-10-25). Transaction codes follow declaration order, so the order
// below must stay: 1 transact, 2 addProcedureCallback, 3 cancelRequest, 4 setParam
// (OPC also has 5 addSelectPaymentDataCallback, 6 addPinpadCallback -- unused).
// transact() takes and returns the same JSON as the socket protocol (GlobalAidlRequest).
package com.wizarpos.payment.aidl;

import com.wizarpos.payment.aidl.IPaymentPayCallback;

interface IPaymentPay {
    String transact(String request);
    void addProcedureCallback(IPaymentPayCallback callback);
    boolean cancelRequest(String request);
    String setParam(String param);
}
