package com.wizarpos.honeywell.scanner.aidl;

import com.cloudpos.scanserver.aidl.ScanParameter;

// Reconstructed from real-device reverse engineering (dexdump of the
// installed com.wizarpos.honeywell.scanner APK, version 1.3.26) -- this
// interface is not documented anywhere in the CloudPOS SDK bundle we have.
// Method order below MUST match the TRANSACTION_* constants pulled from the
// real IHoneywellScanner$Stub class (AIDL assigns transaction codes by
// declaration order, and dexdump's method listing is alphabetical, NOT
// declaration order -- confirmed via the actual TRANSACTION_* field values):
//   TRANSACTION_openBySecurity       = 1
//   TRANSACTION_startScanByParameter = 2
//   TRANSACTION_stopScan             = 3
//   TRANSACTION_close                = 4
//   TRANSACTION_setParameter         = 5
//
// The callback parameter is declared as a raw IBinder rather than
// com.cloudpos.scanserver.aidl.IScanCallBack: that interface's real
// Stub/Proxy/Default classes already ship inside
// libs/wizarpos/cloudpos_sdk.aar, so redeclaring it as our own .aidl here
// would generate a second, conflicting IScanCallBack class on the classpath.
// IBinder is wire-compatible -- AIDL's own codegen for an interface
// parameter just extracts .asBinder() before writing it, so passing
// IScanCallBack.Stub.asBinder() here produces an identical wire format.
interface IHoneywellScanner {
    boolean openBySecurity(IBinder token);
    boolean startScanByParameter(in ScanParameter param, IBinder callback);
    boolean stopScan();
    boolean close();
    boolean setParameter(String key, String value);
}
