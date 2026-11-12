package com.follow.clash.service.module

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.RemoteCallbackList
import androidx.core.app.NotificationCompat
import com.follow.clash.common.GlobalState
import com.follow.clash.common.R as CommonR
import com.follow.clash.common.ensureNotificationChannel
import com.follow.clash.core.Core
import com.follow.clash.service.NetworkObserver
import com.follow.clash.service.R
import com.follow.clash.service.ServiceConfig
import com.follow.clash.service.models.NotificationParams
import com.follow.clash.service.models.VpnOptions
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The Core module: the Go Core, the tunnel and the notification, in a process
 * of their own. The UI binds here to run, stop and call Core methods, so the
 * module keeps its run while the UI task is gone.
 */
class CoreModuleService : Service() {
    private val callbacks = RemoteCallbackList<IModuleCallback>()
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        ModuleHost.onStopped = ::dispatchStopped
        scope.launch {
            runCatching {
                Core.load()
                Core.updateEventListener(::dispatchEvent)
                NetworkObserver.start()
            }.onFailure { error ->
                GlobalState.log("Unable to open the Core module: $error")
            }
        }
    }

    override fun onDestroy() {
        ModuleHost.onStopped = null
        runCatching { Core.updateEventListener(null) }
        GlobalState.launch {
            runCatching { withTimeout(STOP_TIMEOUT_MILLIS) { ModuleHost.stop() } }
                .onFailure { error -> GlobalState.log("Unable to close the Core module: $error") }
        }
        dropForeground()
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        // startForegroundService has to reach startForeground in a few seconds;
        // the run itself replaces this notice with the tunnel's own.
        ensureForeground()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    private val binder = object : IModuleService.Stub() {
        override fun registerCallback(callback: IModuleCallback) {
            callbacks.register(callback)
        }

        override fun unregisterCallback(callback: IModuleCallback) {
            callbacks.unregister(callback)
        }

        override fun invoke(data: ByteArray): ByteArray = runCatching {
            val request = ModuleFrames.decode(data).toString(Charsets.UTF_8)
            val response = runBlocking {
                withTimeout(INVOKE_TIMEOUT_MILLIS) { ModuleHost.invoke(request) }
            }
            ModuleFrames.encode(response)
        }.getOrElse { error ->
            ModuleFrames.encode(errorReply(error))
        }

        override fun quickSetup(initParams: String, setupParams: String): String = runCatching {
            runBlocking {
                withTimeout(SETUP_TIMEOUT_MILLIS) { ModuleHost.quickSetup(initParams, setupParams) }
            }
        }.getOrElse { error ->
            error.message ?: error.toString()
        }

        override fun startRun(optionsJson: String, notificationJson: String): Boolean {
            val options = runCatching {
                gson.fromJson(optionsJson, VpnOptions::class.java)
            }.getOrNull() ?: return false
            runCatching {
                gson.fromJson(notificationJson, NotificationParams::class.java)
            }.getOrNull()?.let(ServiceConfig::updateNotificationParams)
            ensureForeground()
            val started = runCatching {
                runBlocking {
                    withTimeout(START_TIMEOUT_MILLIS) { ModuleHost.start(options) }
                }
            }.getOrElse { error ->
                GlobalState.log("Unable to start the module run: $error")
                false
            }
            dropForeground()
            if (!started) {
                stopSelf()
            }
            return started
        }

        override fun stopRun(): Boolean {
            val stopped = runCatching {
                runBlocking { withTimeout(STOP_TIMEOUT_MILLIS) { ModuleHost.stop() } }
                true
            }.getOrElse { error ->
                GlobalState.log("Unable to stop the module run: $error")
                false
            }
            dropForeground()
            stopSelf()
            return stopped
        }

        override fun status(): String = statusJson()

        override fun setNotification(json: String) {
            runCatching {
                gson.fromJson(json, NotificationParams::class.java)
            }.getOrNull()?.let(ServiceConfig::updateNotificationParams)
        }

        override fun shutdown() {
            runCatching {
                runBlocking { withTimeout(STOP_TIMEOUT_MILLIS) { ModuleHost.stop() } }
            }
            dropForeground()
            stopSelf()
        }
    }

    private fun statusJson(): String {
        val runtime = ModuleHost.runtime
        return gson.toJson(
            mapOf(
                "running" to (runtime != null),
                "vpn" to (runtime?.vpn ?: false),
                "startedAt" to (runtime?.startedAtMillis ?: 0L),
            ),
        )
    }

    private fun errorReply(error: Throwable): ByteArray = gson.toJson(
        mapOf(
            "error" to mapOf(
                "code" to "module_error",
                "message" to (error.message ?: error.toString()),
            ),
        ),
    ).toByteArray()

    private fun dispatchEvent(data: ByteArray) {
        val frame = runCatching { ModuleFrames.encode(data) }.getOrNull() ?: return
        val count = callbacks.beginBroadcast()
        try {
            for (index in 0 until count) {
                runCatching { callbacks.getBroadcastItem(index).onEvent(frame) }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    private fun dispatchStopped() {
        val count = callbacks.beginBroadcast()
        try {
            for (index in 0 until count) {
                runCatching { callbacks.getBroadcastItem(index).onStopped() }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    @Synchronized
    private fun ensureForeground() {
        if (foreground) return
        try {
            ensureNotificationChannel()
            val notification = NotificationCompat.Builder(this, GlobalState.NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_service)
                .setContentTitle(getString(CommonR.string.app_name))
                .setContentText(getString(R.string.module_notification_text))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foreground = true
        } catch (error: Exception) {
            GlobalState.log("Unable to foreground the Core module: $error")
        }
    }

    @Synchronized
    private fun dropForeground() {
        if (!foreground) return
        foreground = false
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    private companion object {
        // Beside the tunnel notification, never over it.
        const val NOTIFICATION_ID = 2
        const val INVOKE_TIMEOUT_MILLIS = 180_000L
        const val SETUP_TIMEOUT_MILLIS = 60_000L
        const val START_TIMEOUT_MILLIS = 30_000L
        const val STOP_TIMEOUT_MILLIS = 15_000L
    }
}
