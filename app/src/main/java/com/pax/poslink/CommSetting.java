package com.pax.poslink;

public class CommSetting {
    public String commType;
    public String destIP;
    public String destPort;
    public String timeout;

    public void setCommType(String type) { this.commType = type; }
    public void setDestIP(String ip) { this.destIP = ip; }
    public void setDestPort(String port) { this.destPort = port; }
    public void setTimeOut(String timeout) { this.timeout = timeout; }
}
