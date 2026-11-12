package com.follow.clash.service.module

import com.follow.clash.common.GlobalState
import java.io.File

/**
 * A Binder transaction stops at about 1 MiB and the Core answers with payloads
 * that pass it, so the two module sides hand anything above [INLINE_LIMIT] over
 * through a file both processes reach: they run as the same UID.
 */
object ModuleFrames {
    private const val INLINE = 0.toByte()
    private const val FILE = 1.toByte()
    private const val INLINE_LIMIT = 256 * 1024

    private val directory: File
        get() = File(GlobalState.application.filesDir, "module-ipc").apply { mkdirs() }

    fun encode(data: ByteArray): ByteArray {
        if (data.size <= INLINE_LIMIT) {
            return byteArrayOf(INLINE) + data
        }
        val file = File.createTempFile("frame-", ".bin", directory)
        file.writeBytes(data)
        return byteArrayOf(FILE) + file.absolutePath.toByteArray()
    }

    fun decode(frame: ByteArray): ByteArray {
        if (frame.isEmpty()) {
            return ByteArray(0)
        }
        return when (frame[0]) {
            INLINE -> frame.copyOfRange(1, frame.size)
            FILE -> {
                val file = File(String(frame, 1, frame.size - 1))
                try {
                    file.readBytes()
                } finally {
                    file.delete()
                }
            }

            else -> throw IllegalArgumentException("Unknown module frame")
        }
    }
}
