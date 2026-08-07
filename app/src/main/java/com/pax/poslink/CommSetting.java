package com.pax.poslink;

public class CommSetting {
    private String commType;
    private String destIP;
    private String destPort;
    private String timeout;

    public void setCommType(String type) { this.commType = type; }
    public void setDestIP(String ip) { this.destIP = ip; }
    public void setDestPort(String port) { this.destPort = port; }
    public void setTimeOut(String timeout) { this.timeout = timeout; }

    public String getCommType() { return commType; }
    public String getDestIP() { return destIP; }
    public String getDestPort() { return destPort; }
    public String getTimeOut() { return timeout; }
}
