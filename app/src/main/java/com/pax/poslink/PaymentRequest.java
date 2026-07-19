package com.pax.poslink;

public class PaymentRequest {
    public int transType;
    public int tenderType;
    public String amount;
    public String ecrRefNum;
    public String origRefNum;

    public void setTransType(int type) { this.transType = type; }
    public void setTenderType(int type) { this.tenderType = type; }
    public void setAmount(String amount) { this.amount = amount; }
    public void setECRRefNum(String ref) { this.ecrRefNum = ref; }
    public void setOrigRefNum(String ref) { this.origRefNum = ref; }
}
