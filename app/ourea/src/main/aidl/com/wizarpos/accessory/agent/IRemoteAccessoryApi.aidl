package com.wizarpos.accessory.agent;

/**
 * WizarPOS Accessory Agent Service AIDL.
 * Facilitates Intent proxying from Master (e.g. D3) to Slave (e.g. Q3).
 */
interface IRemoteAccessoryApi {
    /**
     * Sends an Intent defined as JSON to the connected accessory.
     * JSON Keys: action, packageName, className, flags, putExtra (map).
     */
    void remoteIntent(String intentJson);
}
