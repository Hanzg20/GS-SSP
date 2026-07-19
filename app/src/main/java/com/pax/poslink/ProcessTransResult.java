package com.pax.poslink;

public class ProcessTransResult {
    public ProcessTransExitCode code;
    public String msg;

    public enum ProcessTransExitCode {
        OK,
        Error
    }
}
