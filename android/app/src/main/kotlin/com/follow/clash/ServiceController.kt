package com.follow.clash

import com.follow.clash.common.GlobalState
import com.follow.clash.module.ModuleClient
import com.follow.clash.module.ModuleStatus
import com.follow.clash.service.models.NotificationParams
import com.follow.clash.service.models.VpnOptions
import kotlinx.coroutines.launch

/**
 * The UI's connection to the Core module. Binding and process-time bookkeeping
 * live here; which run should exist stays in [ServiceState].
 */
object ServiceController {
    init {
        ModuleClient.onRunLost = ::handleModuleLost
    }

    internal val runtime: ServiceRuntime?
        get() = ModuleClient.status
            ?.takeIf { it.running }
            ?.let { ServiceRuntime(vpn = it.vpn, startedAtMillis = it.startedAtMillis) }

    fun invokeMethod(data: String, callback: (ByteArray) -> Unit): Result<Unit> = runCatching {
        ModuleClient.invoke(data, callback)
    }

    fun setEventListener(callback: ((ByteArray) -> Unit)?): Result<Unit> = runCatching {
        ModuleClient.eventListener = callback
    }

    fun setNotification(params: NotificationParams) {
        ModuleClient.setNotification(params)
    }

    suspend fun quickSetup(initParams: String, setupParams: String): Result<String> =
        ModuleClient.quickSetup(initParams, setupParams)

    suspend fun attach(): ModuleStatus? = ModuleClient.refreshStatus()

    suspend fun start(options: VpnOptions): Boolean = ModuleClient.start(options)

    suspend fun stop() {
        ModuleClient.stop()
    }

    private fun handleModuleLost() {
        val token = ServiceState.captureRequestToken()
        GlobalState.launch {
            ServiceState.handleServiceLost(token)
        }
    }
}
