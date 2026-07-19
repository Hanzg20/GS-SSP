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
}
