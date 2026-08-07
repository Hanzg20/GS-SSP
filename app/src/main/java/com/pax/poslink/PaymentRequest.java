package com.pax.poslink;

public class PaymentRequest {
    private int transType;
    private int tenderType;
    private String amount;
    private String ecrRefNum;
    private String origRefNum;

    public void setTransType(int type) { this.transType = type; }
    public void setTenderType(int type) { this.tenderType = type; }
    public void setAmount(String amount) { this.amount = amount; }
    public void setECRRefNum(String ref) { this.ecrRefNum = ref; }
    public void setOrigRefNum(String ref) { this.origRefNum = ref; }

    public int getTransType() { return transType; }
    public int getTenderType() { return tenderType; }
    public String getAmount() { return amount; }
    public String getECRRefNum() { return ecrRefNum; }
    public String getOrigRefNum() { return origRefNum; }
}
