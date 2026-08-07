package com.pax.poslink;

public class PaymentResponse {
    private String resultCode;
    private String resultMsg;
    private String authCode;
    private String refNum;

    public String getResultCode() { return resultCode; }
    public String getResultMsg() { return resultMsg; }
    public String getAuthCode() { return authCode; }
    public String getRefNum() { return refNum; }

    public void setResultCode(String code) { this.resultCode = code; }
    public void setResultMsg(String msg) { this.resultMsg = msg; }
    public void setAuthCode(String code) { this.authCode = code; }
    public void setRefNum(String ref) { this.refNum = ref; }
}
