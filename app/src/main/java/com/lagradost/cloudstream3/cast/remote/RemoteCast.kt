package com.lagradost.cloudstream3.cast.remote

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lagradost.cloudstream3.ui.player.IPlayer
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Who this phone is and which TVs it has paired with. */
interface ControllerStorage {
    val deviceId: String
    val deviceName: String

    /** Token for a device that was paired before, null if not */
    fun getToken(device: RemoteCastDevice): String?
    fun saveToken(device: RemoteCastDevice, tvId: String, token: String)
    fun forgetToken(device: RemoteCastDevice)
}

class PrefsControllerStorage(private val ctx: Context) : ControllerStorage {
    private val prefs get() = ctx.getSharedPreferences("remote_cast", Context.MODE_PRIVATE)
    private val tokens get() = ctx.getSharedPreferences("remote_cast_tokens", Context.MODE_PRIVATE)

    override val deviceId: String
        get() = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }

    override val deviceName: String get() = "${Build.MANUFACTURER} ${Build.MODEL}"

    /** Stored as "tvDeviceId|token", keyed by address so a token is never sent to some other host */
    override fun getToken(device: RemoteCastDevice): String? {
        val stored = tokens.getString(device.host, null) ?: return null
        val (tvId, token) = stored.split("|", limit = 2).takeIf { it.size == 2 } ?: return null
        if (device.deviceId != null && device.deviceId != tvId) return null
        return token
    }

    override fun saveToken(device: RemoteCastDevice, tvId: String, token: String) {
        tokens.edit().putString(device.host, "$tvId|$token").apply()
    }

    override fun forgetToken(device: RemoteCastDevice) {
        tokens.edit().remove(device.host).apply()
    }
}

enum class RemoteCastStatus { Disconnected, Connecting, Connected, Reconnecting }

interface RemoteCastListener {
    /** Main thread */
    fun onStatusChanged(status: RemoteCastStatus) {}

    /** Main thread, only authenticated TV -> phone messages */
    fun onMessage(opcode: RemoteOpcode, body: ByteArray) {}
}

/** What [RemoteIPlayer] needs from the connection, so it can be tested without a real TV. */
interface RemoteLink {
    fun send(opcode: RemoteOpcode, body: ByteArray = ByteArray(0))
    fun addListener(listener: RemoteCastListener)
    fun removeListener(listener: RemoteCastListener)
}

inline fun <reified T> RemoteLink.send(opcode: RemoteOpcode, message: T) {
    send(opcode, RemoteCastJson.encode(message))
}

/**
 * Phone side of the cast feature. Owns the connection to one CloudStream TV.
 *
 * The phone keeps doing everything (resolving links, subtitles, picking sources), and this class is
 * only the pipe that carries the commands to the TV and its state back.
 * See [RemoteIPlayer] for what makes the existing player UI use this.
 */
object RemoteCast : RemoteLink {
    private const val TAG = "RemoteCast"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val RECONNECT_ATTEMPTS = 8


    /** Called on the main thread when the TV needs a PIN, [submit] with null cancels */
    fun interface PinProvider {
        fun requestPin(deviceName: String, wasWrong: Boolean, submit: (String?) -> Unit)
    }

    sealed class ConnectResult {
        data class Success(val deviceName: String) : ConnectResult()
        data class Failure(val message: String?) : ConnectResult()
    }

    private val main = Handler(Looper.getMainLooper())

    /** Where callbacks run, the main thread. Replaced in tests, as there is none there. */
    internal var postToMain: (() -> Unit) -> Unit = { block -> postToMain { block() } }
    private val listeners = CopyOnWriteArrayList<RemoteCastListener>()

    @Volatile
    var status: RemoteCastStatus = RemoteCastStatus.Disconnected
        private set

    @Volatile
    var device: RemoteCastDevice? = null
        private set

    @Volatile
    var deviceName: String? = null
        private set

    @Volatile
    private var channel: RemoteChannel? = null

    /** Incremented on every connect/disconnect so stale callbacks and reconnects can be ignored */
    @Volatile
    private var generation = 0

    private var storage: ControllerStorage? = null

    /** The cast is in use, and the player should be remote controlling the TV */
    val isActive: Boolean
        get() = status == RemoteCastStatus.Connected || status == RemoteCastStatus.Reconnecting

    val isConnected: Boolean
        get() = status == RemoteCastStatus.Connected

    override fun addListener(listener: RemoteCastListener) {
        listeners.addIfAbsent(listener)
    }

    override fun removeListener(listener: RemoteCastListener) {
        listeners.remove(listener)
    }

    /**
     * Used by the player fragment to pick the player. If we are connected to a TV
     * the player is one that controls the TV, otherwise null and the normal player is used.
     */
    fun createPlayerOrNull(): IPlayer? = if (isActive) RemoteIPlayer() else null

    private fun setStatus(newStatus: RemoteCastStatus) {
        if (status == newStatus) return
        status = newStatus
        postToMain { listeners.forEach { it.onStatusChanged(newStatus) } }
    }

    // ----- Connecting -----

    /**
     * Connects to [target], pairing with a PIN if this phone has not been paired with it before.
     * [callback] is always invoked on the main thread.
     */
    fun connect(
        context: Context,
        target: RemoteCastDevice,
        pinProvider: PinProvider,
        callback: (ConnectResult) -> Unit,
    ) {
        connect(PrefsControllerStorage(context.applicationContext), target, pinProvider, callback)
    }

    internal fun connect(
        storage: ControllerStorage,
        target: RemoteCastDevice,
        pinProvider: PinProvider,
        callback: (ConnectResult) -> Unit,
    ) {
        this.storage = storage
        disconnect(sendStop = false)
        val gen = ++generation
        setStatus(RemoteCastStatus.Connecting)
        device = target

        Thread({
            val result = try {
                openAndHandshake(storage, target, gen, pinProvider)
            } catch (t: Throwable) {
                Log.e(TAG, "Connect failed", t)
                ConnectResult.Failure(t.message)
            }

            if (gen != generation) return@Thread
            if (result is ConnectResult.Failure) {
                channel?.close()
                channel = null
                device = null
                setStatus(RemoteCastStatus.Disconnected)
            } else {
                setStatus(RemoteCastStatus.Connected)
            }
            postToMain { callback(result) }
        }, "RemoteCast-connect").apply {
            isDaemon = true
            start()
        }
    }

    /** Blocks until paired and ready (or failed). Runs on a background thread. */
    private fun openAndHandshake(
        storage: ControllerStorage,
        target: RemoteCastDevice,
        gen: Int,
        pinProvider: PinProvider?,
    ): ConnectResult {
        val socket = Socket()
        socket.connect(InetSocketAddress(target.host, target.port), CONNECT_TIMEOUT_MS)

        val result = AtomicReference<ConnectResult?>(null)
        val done = CountDownLatch(1)

        fun finish(r: ConnectResult) {
            if (result.compareAndSet(null, r)) done.countDown()
        }

        lateinit var newChannel: RemoteChannel
        fun sendHello(pin: String?, token: String?) {
            newChannel.send(
                RemoteOpcode.Hello,
                HelloMessage(
                    version = REMOTE_CAST_PROTOCOL_VERSION,
                    deviceId = storage.deviceId,
                    deviceName = storage.deviceName,
                    token = token,
                    pin = pin,
                )
            )
        }

        newChannel = RemoteChannel(
            socket,
            onFrame = { frame ->
                if (frame.opcode == RemoteOpcode.HelloAck) {
                    val ack = RemoteCastJson.decode<HelloAckMessage>(frame.body)
                    when {
                        ack.accepted -> {
                            ack.token?.let { storage.saveToken(target, ack.deviceId, it) }
                            deviceName = ack.deviceName
                            finish(ConnectResult.Success(ack.deviceName))
                        }

                        ack.needsPin && pinProvider != null -> {
                            if (ack.error == null) storage.forgetToken(target)
                            postToMain {
                                pinProvider.requestPin(ack.deviceName, ack.error != null) { pin ->
                                    if (pin == null) {
                                        finish(ConnectResult.Failure(null))
                                    } else {
                                        sendHello(pin, null)
                                    }
                                }
                            }
                        }

                        else -> finish(ConnectResult.Failure(ack.error))
                    }
                } else if (result.get() is ConnectResult.Success) {
                    deliver(gen, frame)
                }
            },
            onClosed = { error ->
                finish(ConnectResult.Failure(error?.message))
                onChannelClosed(gen)
            },
        )
        channel = newChannel
        newChannel.start()
        sendHello(pin = null, token = storage.getToken(target))

        done.await(HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return result.get() ?: ConnectResult.Failure("Timed out")
    }

    private const val HANDSHAKE_TIMEOUT_MS = 120_000L // The user has to type a PIN

    private fun deliver(gen: Int, frame: RemoteFrame) {
        if (gen != generation) return
        postToMain {
            if (gen != generation) return@postToMain
            listeners.forEach { it.onMessage(frame.opcode, frame.body) }
        }
    }

    private fun onChannelClosed(gen: Int) {
        if (gen != generation) return
        if (status == RemoteCastStatus.Connected) {
            startReconnect(gen)
        }
    }

    private fun startReconnect(gen: Int) {
        val storage = storage ?: return
        val target = device ?: return
        setStatus(RemoteCastStatus.Reconnecting)
        Thread({
            var delayMs = 1_000L
            repeat(RECONNECT_ATTEMPTS) {
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (gen != generation) return@Thread
                // No PIN provider: if the TV forgot us we cannot silently recover
                val result = try {
                    openAndHandshake(storage, target, gen, pinProvider = null)
                } catch (t: Throwable) {
                    ConnectResult.Failure(t.message)
                }
                if (gen != generation) return@Thread
                if (result is ConnectResult.Success) {
                    setStatus(RemoteCastStatus.Connected)
                    return@Thread
                }
                delayMs = (delayMs * 3 / 2).coerceAtMost(8_000L)
            }
            if (gen == generation) {
                channel = null
                device = null
                setStatus(RemoteCastStatus.Disconnected)
            }
        }, "RemoteCast-reconnect").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * @param sendStop if true the TV is told to close its player first
     */
    fun disconnect(sendStop: Boolean = true) {
        generation++
        val old = channel
        channel = null
        device = null
        deviceName = null
        if (old != null) {
            if (sendStop) old.send(RemoteOpcode.Stop)
            // Lets the stop message go out before the socket closes
            old.closeAfterFlush()
        }
        setStatus(RemoteCastStatus.Disconnected)
    }

    // ----- Sending -----

    override fun send(opcode: RemoteOpcode, body: ByteArray) {
        channel?.send(opcode, body)
    }
}
