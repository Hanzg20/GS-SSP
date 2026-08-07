package com.pax.poslink;

public class PosLink {
    public CommSetting commSetting;
    public PaymentRequest paymentRequest;
    public PaymentResponse paymentResponse;

    public void setCommSetting(CommSetting setting) { this.commSetting = setting; }
    public void setPaymentRequest(PaymentRequest request) { this.paymentRequest = request; }
    public PaymentResponse getPaymentResponse() { return this.paymentResponse; }

    public ProcessTransResult ProcessTrans() {
        return new ProcessTransResult();
    }

    // Real POSLink API guide: "used to cancel transaction while POSLink is
    // processing transaction... only effective before the transaction is
    // [sent to the host]". No-op here since this stub's ProcessTrans()
    // never blocks waiting for anything to cancel.
    public void CancelTrans() {
    }
}
