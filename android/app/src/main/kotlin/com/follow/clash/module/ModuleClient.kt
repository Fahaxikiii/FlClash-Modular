package com.follow.clash.module

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.follow.clash.common.GlobalState
import com.follow.clash.common.intent
import com.follow.clash.service.models.NotificationParams
import com.follow.clash.service.models.VpnOptions
import com.follow.clash.service.module.CoreModuleService
import com.follow.clash.service.module.IModuleCallback
import com.follow.clash.service.module.IModuleService
import com.follow.clash.service.module.ModuleFrames
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class ModuleStatus(
    val running: Boolean,
    val vpn: Boolean,
    val startedAtMillis: Long,
)

/**
 * The UI's side of the Core module: it starts the module process, binds to it
 * and forwards Core method calls, while the module owns the run and survives
 * this process.
 */
object ModuleClient {
    private const val BIND_TIMEOUT_MILLIS = 5_000L

    private val gson = Gson()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "core-module-client")
    }
    private val bindLock = Any()

    @Volatile
    var status: ModuleStatus? = null
        private set

    @Volatile
    var eventListener: ((ByteArray) -> Unit)? = null

    @Volatile
    var onRunLost: (() -> Unit)? = null

    private var connection: Connection? = null
    @Volatile
    private var serviceStarted = false

    @Volatile
    private var lastNotification = gson.toJson(NotificationParams())

    private val callback = object : IModuleCallback.Stub() {
        override fun onEvent(data: ByteArray) {
            val payload = runCatching { ModuleFrames.decode(data) }.getOrNull() ?: return
            eventListener?.invoke(payload)
        }

        override fun onStopped() {
            status = null
            onRunLost?.invoke()
        }
    }

    fun setNotification(params: NotificationParams) {
        lastNotification = gson.toJson(params)
        executor.execute {
            runCatching { connection?.service?.setNotification(lastNotification) }
        }
    }

    fun invoke(data: String, callback: (ByteArray) -> Unit) {
        executor.execute {
            runCatching {
                val service = ensureBound() ?: error("Core module is not connected")
                val frame = service.invoke(ModuleFrames.encode(data.toByteArray(Charsets.UTF_8)))
                ModuleFrames.decode(frame)
            }.onSuccess(callback)
                .onFailure { error -> callback(errorReply(error)) }
        }
    }

    suspend fun quickSetup(initParams: String, setupParams: String): Result<String> =
        suspendCancellableCoroutine<Result<String>> { continuation ->
            executor.execute {
                val result = runCatching {
                    val service = ensureBound() ?: error("Core module is not connected")
                    service.quickSetup(initParams, setupParams)
                }
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }
        }

    suspend fun start(options: VpnOptions): Boolean = suspendCancellableCoroutine<Boolean> { continuation ->
        executor.execute {
            val started = runCatching {
                startServiceIfNeeded()
                val service = ensureBound() ?: error("Core module is not connected")
                service.startRun(gson.toJson(options), lastNotification).also {
                    refreshStatus(service)
                }
            }.getOrElse { error ->
                GlobalState.log("Unable to start the Core module: $error")
                false
            }
            if (!started) {
                serviceStarted = false
                status = null
            }
            if (continuation.isActive) {
                continuation.resume(started)
            }
        }
    }

    suspend fun stop(): Boolean = suspendCancellableCoroutine<Boolean> { continuation ->
        executor.execute {
            val stopped = runCatching {
                val service = connection?.service ?: return@runCatching true
                service.stopRun()
            }.getOrElse { error ->
                GlobalState.log("Unable to stop the Core module: $error")
                true
            }
            serviceStarted = false
            status = null
            if (continuation.isActive) {
                continuation.resume(stopped)
            }
        }
    }

    suspend fun refreshStatus(): ModuleStatus? = suspendCancellableCoroutine<ModuleStatus?> { continuation ->
        executor.execute {
            val refreshed = runCatching {
                ensureBound()?.let(::refreshStatus)
            }.getOrNull()
            if (continuation.isActive) {
                continuation.resume(refreshed ?: status)
            }
        }
    }

    private fun startServiceIfNeeded() {
        if (serviceStarted) return
        ContextCompat.startForegroundService(
            GlobalState.application,
            CoreModuleService::class.intent,
        )
        serviceStarted = true
    }

    private fun refreshStatus(service: IModuleService): ModuleStatus? = runCatching {
        val json = gson.fromJson(service.status(), JsonObject::class.java)
        ModuleStatus(
            running = json.get("running")?.asBoolean ?: false,
            vpn = json.get("vpn")?.asBoolean ?: false,
            startedAtMillis = json.get("startedAt")?.asLong ?: 0L,
        ).also { status = it }
    }.getOrNull()

    private fun ensureBound(): IModuleService? {
        val current = connection
        if (current != null && !current.dead) {
            current.service?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        }
        synchronized(bindLock) {
            val existing = connection
            if (existing != null && !existing.dead) {
                existing.service?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
            }
            val next = Connection()
            connection = next
            next.bind()
            if (!next.latch.await(BIND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                GlobalState.log("Unable to bind the Core module")
                next.dead = true
                connection = null
                return null
            }
            return next.service
        }
    }

    private fun errorReply(error: Throwable): ByteArray = gson.toJson(
        mapOf(
            "error" to mapOf(
                "code" to "module_unavailable",
                "message" to (error.message ?: error.toString()),
            ),
        ),
    ).toByteArray()

    private class Connection : ServiceConnection {
        val latch = CountDownLatch(1)

        @Volatile
        var service: IModuleService? = null

        @Volatile
        var dead = false

        fun bind() {
            val bound = runCatching {
                GlobalState.application.bindService(
                    CoreModuleService::class.intent,
                    this,
                    Context.BIND_AUTO_CREATE,
                )
            }.getOrDefault(false)
            if (!bound) {
                dead = true
                latch.countDown()
            }
        }

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val next = binder?.let { IModuleService.Stub.asInterface(it) }
            if (next == null) {
                ModuleClient.lost(this)
                latch.countDown()
                return
            }
            service = next
            latch.countDown()
            ModuleClient.executor.execute {
                runCatching {
                    next.registerCallback(ModuleClient.callback)
                    next.setNotification(ModuleClient.lastNotification)
                    ModuleClient.refreshStatus(next)
                }.onFailure { error ->
                    GlobalState.log("Unable to attach to the Core module: $error")
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            ModuleClient.lost(this)
        }

        override fun onBindingDied(name: ComponentName?) {
            ModuleClient.lost(this)
        }

        override fun onNullBinding(name: ComponentName?) {
            ModuleClient.lost(this)
        }
    }

    private fun lost(connection: Connection) {
        if (this.connection !== connection) {
            return
        }
        connection.dead = true
        connection.service = null
        connection.latch.countDown()
        this.connection = null
        serviceStarted = false
        status = null
        onRunLost?.invoke()
    }
}
