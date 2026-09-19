package com.cloudpos.scanserver.aidl;

// Declaration only -- the real Parcelable implementation ships inside
// libs/wizarpos/cloudpos_sdk.aar (com.cloudpos.scanserver.aidl.ScanParameter,
// confirmed present via `unzip -l classes.jar`). This just tells the AIDL
// compiler the type exists and is Parcelable so IHoneywellScanner.aidl can
// reference it.
parcelable ScanParameter;
