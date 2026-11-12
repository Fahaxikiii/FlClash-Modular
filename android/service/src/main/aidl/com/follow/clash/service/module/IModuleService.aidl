package com.follow.clash.service.module;

import com.follow.clash.service.module.IModuleCallback;

/**
 * The boundary between the UI process and the Core module process. The module
 * owns the Go Core, the tunnel and the notification; the UI only asks it to run,
 * stop and answer Core method calls.
 */
interface IModuleService {
    void registerCallback(IModuleCallback callback);

    void unregisterCallback(IModuleCallback callback);

    /**
     * One Core method call, already JSON encoded by the UI. The reply is the
     * Core's response envelope, framed by ModuleFrames when it is too large for
     * a Binder transaction.
     */
    byte[] invoke(in byte[] data);

    /**
     * Initialises the Core and applies a profile in one hop. Returns the Core's
     * message, empty on success.
     */
    String quickSetup(String initParams, String setupParams);

    /**
     * Starts a run with the given VpnOptions. Returns false when the tunnel or
     * the Core refused it.
     */
    boolean startRun(String optionsJson, String notificationJson);

    boolean stopRun();

    /**
     * JSON: {"running":bool,"vpn":bool,"startedAt":long}
     */
    String status();

    void setNotification(String json);

    /**
     * Stops the run and lets the system destroy the module when the UI unbinds.
     */
    void shutdown();
}
