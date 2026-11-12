package com.follow.clash.service.module;

/**
 * What the Core module process pushes back to the UI process: event batches the
 * Core already framed, and the end of a run that this side did not ask for.
 */
oneway interface IModuleCallback {
    void onEvent(in byte[] data);

    void onStopped();
}
