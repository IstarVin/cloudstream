package com.lagradost.cloudstream3.cast.remote

import java.io.IOException
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One framed connection between phone and TV, used by both sides.
 *
 * - Reading happens on a dedicated daemon thread, [onFrame] is invoked on that thread.
 * - Writing happens on a single thread executor so it is safe to call [send] from the main thread.
 * - A ping is sent regularly and the channel is closed if nothing has been received for a while,
 *   as half-open TCP connections are very common on wifi.
 */
class RemoteChannel(
    private val socket: Socket,
    private val onFrame: (RemoteFrame) -> Unit,
    private val onClosed: (Throwable?) -> Unit,
) {
    private val closed = AtomicBoolean(false)
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "RemoteChannel-writer").apply { isDaemon = true }
    }

    @Volatile
    private var lastReceivedMs = System.currentTimeMillis()

    val isClosed: Boolean get() = closed.get()

    val remoteAddress: String? get() = socket.inetAddress?.hostAddress

    fun start() {
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
        } catch (_: Throwable) {
        }
        Thread({ readLoop() }, "RemoteChannel-reader").apply {
            isDaemon = true
            start()
        }
        Thread({ keepAliveLoop() }, "RemoteChannel-keepalive").apply {
            isDaemon = true
            start()
        }
    }

    private fun readLoop() {
        var error: Throwable? = null
        try {
            val input = socket.getInputStream().buffered()
            while (!closed.get()) {
                val frame = RemoteFraming.read(input) ?: continue
                lastReceivedMs = System.currentTimeMillis()
                when (frame.opcode) {
                    RemoteOpcode.Ping -> send(RemoteOpcode.Pong)
                    RemoteOpcode.Pong -> Unit
                    else -> try {
                        onFrame(frame)
                    } catch (t: Throwable) {
                        // A bad message must never kill the connection
                        t.printStackTrace()
                    }
                }
            }
        } catch (t: Throwable) {
            error = t
        } finally {
            close(error)
        }
    }

    private fun keepAliveLoop() {
        while (!closed.get()) {
            try {
                Thread.sleep(PING_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            }
            if (closed.get()) return
            if (System.currentTimeMillis() - lastReceivedMs > TIMEOUT_MS) {
                close(IOException("Connection timed out"))
                return
            }
            send(RemoteOpcode.Ping)
        }
    }

    fun send(opcode: RemoteOpcode, body: ByteArray = ByteArray(0)) {
        if (closed.get()) return
        try {
            writer.execute {
                try {
                    if (!closed.get()) {
                        RemoteFraming.write(socket.getOutputStream(), opcode, body)
                    }
                } catch (t: Throwable) {
                    close(t)
                }
            }
        } catch (_: RejectedExecutionException) {
            // Already shut down
        }
    }

    inline fun <reified T> send(opcode: RemoteOpcode, message: T) {
        send(opcode, RemoteCastJson.encode(message))
    }

    /** Closes after everything queued with [send] has been written */
    fun closeAfterFlush() {
        try {
            writer.execute { close() }
        } catch (_: RejectedExecutionException) {
            close()
        }
    }

    fun close(error: Throwable? = null) {
        if (!closed.compareAndSet(false, true)) return
        try {
            socket.close()
        } catch (_: Throwable) {
        }
        writer.shutdown()
        onClosed(error)
    }

    companion object {
        const val PING_INTERVAL_MS = 4_000L
        const val TIMEOUT_MS = 15_000L
    }
}
