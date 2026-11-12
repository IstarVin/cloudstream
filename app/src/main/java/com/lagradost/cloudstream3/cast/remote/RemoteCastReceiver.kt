package com.lagradost.cloudstream3.cast.remote

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.UUID

/** Implemented by the screen on the TV that actually plays the video. Always called on the main thread. */
interface RemoteCastReceiverHost {
    fun load(message: LoadMessage)
    fun play()
    fun pause()
    fun seek(positionMs: Long)
    fun setSpeed(speed: Float)
    fun setSubtitles(message: SetSubtitlesMessage)
    fun setSubtitleStyle(message: SetSubtitleStyleMessage)
    fun setSubtitleOffset(offsetMs: Long)
    fun setAudioTrack(message: SetAudioTrackMessage)
    fun setMaxVideoSize(message: SetMaxVideoSizeMessage)

    /** The phone ended the cast, close the player screen */
    fun stop()
}

/**
 * TV side of the cast feature. Listens for a phone, handles pairing and forwards
 * commands to the currently attached [RemoteCastReceiverHost].
 *
 * Only active while CloudStream is open on the TV.
 */
object RemoteCastReceiver {
    private const val TAG = "RemoteCastReceiver"
    private const val PREFS_NAME = "remote_cast"
    private const val PAIRED_PREFS_NAME = "remote_cast_paired"
    private const val KEY_DEVICE_ID = "device_id"
    private const val MAX_PIN_TRIES = 3

    /** After this many failed pairings from one address it is ignored for [LOCKOUT_MS] */
    private const val MAX_FAILURES = 5
    private const val LOCKOUT_MS = 10 * 60 * 1000L

    private val main = Handler(Looper.getMainLooper())
    private val random = SecureRandom()

    private var appContext: Context? = null
    private var serverSocket: ServerSocket? = null
    private var running = false

    /** Incremented on every start/stop, so a server that was stopped while starting shuts itself down */
    private var generation = 0

    @Volatile
    private var session: Session? = null

    private var host: RemoteCastReceiverHost? = null
    private var pendingLoad: LoadMessage? = null
    private var pinDialog: AlertDialog? = null

    private class Session(val channel: RemoteChannel, val handshake: ReceiverHandshake) {
        @Volatile
        var authenticated = false
    }

    private class PrefsPairingStorage(private val ctx: Context) : PairingStorage {
        private val prefs get() = ctx.getSharedPreferences(PAIRED_PREFS_NAME, Context.MODE_PRIVATE)
        override fun getToken(controllerId: String): String? = prefs.getString(controllerId, null)
        override fun saveToken(controllerId: String, token: String) {
            prefs.edit().putString(controllerId, token).apply()
        }
    }

    fun isEnabled(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean(context.getString(R.string.remote_cast_receiver_key), true)
    }

    /** Starts listening if this is a TV and the setting is enabled. Safe to call multiple times. */
    fun startIfEnabled(context: Context) {
        if (!isLayout(TV or EMULATOR)) return
        if (!isEnabled(context)) {
            stop()
            return
        }
        start(context)
    }

    @Synchronized
    fun start(context: Context) {
        if (running) return
        running = true
        val ctx = context.applicationContext
        appContext = ctx
        val gen = ++generation
        Thread({ runServer(ctx, gen) }, "RemoteCastReceiver-accept").apply {
            isDaemon = true
            start()
        }
    }

    private fun runServer(ctx: Context, gen: Int) {
        val server = try {
            bind(REMOTE_CAST_DEFAULT_PORT)
        } catch (_: BindException) {
            // Port is taken, let the OS pick one. It is still found over NSD.
            bind(0)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to open server socket", t)
            synchronized(this) { if (generation == gen) running = false }
            return
        }

        synchronized(this) {
            if (generation != gen) {
                // Stopped while starting
                server.close()
                return
            }
            serverSocket = server
        }

        RemoteCastDiscovery.register(
            ctx,
            serviceName = "CloudStream-${Build.MODEL}",
            deviceId = getDeviceId(ctx),
            port = server.localPort,
        )
        Log.d(TAG, "Listening on port ${server.localPort}")
        acceptLoop(server)
    }

    private fun bind(port: Int): ServerSocket {
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(port))
        return server
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        generation++
        appContext?.let { RemoteCastDiscovery.unregister(it) }
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
        session?.channel?.close()
        session = null
        main.post { dismissPin() }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: Throwable) {
                return
            }
            handleNewConnection(socket)
        }
    }

    // ----- Brute force protection, a PIN is only 4 digits -----

    private val failures = HashMap<String, MutableList<Long>>()

    private fun isBlocked(address: String): Boolean = synchronized(failures) {
        val list = failures[address] ?: return false
        val now = System.currentTimeMillis()
        list.removeAll { now - it > LOCKOUT_MS }
        list.size >= MAX_FAILURES
    }

    private fun recordFailure(address: String?) {
        if (address == null) return
        synchronized(failures) {
            failures.getOrPut(address) { mutableListOf() }.add(System.currentTimeMillis())
        }
    }

    private fun handleNewConnection(socket: Socket) {
        val ctx = appContext ?: return
        val address = socket.inetAddress?.hostAddress
        if (address != null && isBlocked(address)) {
            try {
                socket.close()
            } catch (_: Throwable) {
            }
            return
        }
        val handshake = ReceiverHandshake(
            ownId = getDeviceId(ctx),
            ownName = getDeviceName(),
            storage = PrefsPairingStorage(ctx),
            newPin = { String.format("%04d", random.nextInt(10_000)) },
            newToken = { UUID.randomUUID().toString() },
            maxPinTries = MAX_PIN_TRIES,
        )
        lateinit var newSession: Session
        val channel = RemoteChannel(
            socket,
            onFrame = { frame -> onFrame(newSession, frame) },
            onClosed = { onSessionClosed(newSession) },
        )
        newSession = Session(channel, handshake)
        channel.start()
    }

    private fun onSessionClosed(closed: Session) {
        if (session === closed) session = null
        main.post {
            // Only remove the PIN if it belonged to this connection
            if (!closed.authenticated && closed.handshake.pin != null) dismissPin()
        }
    }

    // ----- Handshake -----

    private fun onFrame(s: Session, frame: RemoteFrame) {
        if (frame.opcode == RemoteOpcode.Hello) {
            handleHello(s, RemoteCastJson.decode(frame.body))
            return
        }
        if (!s.authenticated) return
        dispatch(frame)
    }

    private fun handleHello(s: Session, hello: HelloMessage) {
        if (s.authenticated) return
        when (val result = s.handshake.onHello(hello)) {
            is ReceiverHandshake.Result.Authenticated -> {
                if (result.wasPairing) main.post { dismissPin() }
                authenticate(s)
                s.channel.send(RemoteOpcode.HelloAck, result.ack)
            }

            is ReceiverHandshake.Result.NeedsPin -> {
                // The user has to type the PIN shown here into the phone
                result.showPin?.let { pin -> main.post { showPin(pin, hello.deviceName) } }
                s.channel.send(RemoteOpcode.HelloAck, result.ack)
            }

            is ReceiverHandshake.Result.Rejected -> {
                recordFailure(s.channel.remoteAddress)
                main.post { dismissPin() }
                s.channel.send(RemoteOpcode.HelloAck, result.ack)
                s.channel.closeAfterFlush()
            }
        }
    }

    private fun authenticate(s: Session) {
        s.authenticated = true
        val previous = session
        session = s
        // Only one phone at a time
        if (previous != null && previous !== s) previous.channel.close()
    }

    private fun getDeviceId(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }
    }

    private fun getDeviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

    // ----- PIN dialog -----

    private fun showPin(pin: String, controllerName: String) {
        dismissPin()
        val activity = CommonActivity.activity ?: return
        if (activity.isFinishing) return
        pinDialog = try {
            AlertDialog.Builder(activity)
                .setTitle(R.string.remote_cast_pin_title)
                .setMessage(activity.getString(R.string.remote_cast_pin_message, controllerName, pin))
                .setCancelable(true)
                .show()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to show PIN", t)
            null
        }
    }

    private fun dismissPin() {
        try {
            pinDialog?.dismiss()
        } catch (_: Throwable) {
        }
        pinDialog = null
    }

    // ----- Commands -----

    private fun dispatch(frame: RemoteFrame) {
        val body = frame.body
        when (frame.opcode) {
            RemoteOpcode.Load -> {
                val message = RemoteCastJson.decode<LoadMessage>(body)
                main.post { onLoad(message) }
            }

            RemoteOpcode.Play -> onHost { it.play() }
            RemoteOpcode.Pause -> onHost { it.pause() }
            RemoteOpcode.Stop -> {
                main.post {
                    pendingLoad = null
                    host?.stop()
                }
            }

            RemoteOpcode.Seek -> {
                val m = RemoteCastJson.decode<SeekMessage>(body)
                onHost { it.seek(m.positionMs) }
            }

            RemoteOpcode.SetSpeed -> {
                val m = RemoteCastJson.decode<SetSpeedMessage>(body)
                onHost { it.setSpeed(m.speed) }
            }

            RemoteOpcode.SetSubtitles -> {
                val m = RemoteCastJson.decode<SetSubtitlesMessage>(body)
                onHost { it.setSubtitles(m) }
            }

            RemoteOpcode.SetSubtitleStyle -> {
                val m = RemoteCastJson.decode<SetSubtitleStyleMessage>(body)
                onHost { it.setSubtitleStyle(m) }
            }

            RemoteOpcode.SetSubtitleOffset -> {
                val m = RemoteCastJson.decode<SetSubtitleOffsetMessage>(body)
                onHost { it.setSubtitleOffset(m.offsetMs) }
            }

            RemoteOpcode.SetAudioTrack -> {
                val m = RemoteCastJson.decode<SetAudioTrackMessage>(body)
                onHost { it.setAudioTrack(m) }
            }

            RemoteOpcode.SetMaxVideoSize -> {
                val m = RemoteCastJson.decode<SetMaxVideoSizeMessage>(body)
                onHost { it.setMaxVideoSize(m) }
            }

            else -> Log.d(TAG, "Ignoring opcode ${frame.opcode}")
        }
    }

    private fun onHost(block: (RemoteCastReceiverHost) -> Unit) {
        main.post { host?.let(block) }
    }

    private fun onLoad(message: LoadMessage) {
        val current = host
        if (current != null) {
            current.load(message)
            return
        }
        pendingLoad = message
        launchPlayerScreen()
    }

    private fun launchPlayerScreen() {
        val ctx = CommonActivity.activity ?: appContext ?: return
        try {
            ctx.startActivity(
                Intent(ctx, RemoteCastReceiverActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to open player screen", t)
            pendingLoad = null
        }
    }

    // ----- Called by the player screen -----

    /** Attach the screen that plays the video, returns a load that arrived before it was ready. */
    fun attachHost(newHost: RemoteCastReceiverHost): LoadMessage? {
        host = newHost
        return pendingLoad.also { pendingLoad = null }
    }

    /**
     * @param notifyController true if the TV user closed the screen, so the phone knows
     */
    fun detachHost(oldHost: RemoteCastReceiverHost, notifyController: Boolean) {
        if (host !== oldHost) return
        host = null
        if (notifyController) {
            session?.takeIf { it.authenticated }?.channel?.send(RemoteOpcode.Stopped)
        }
    }

    private fun sendIfConnected(opcode: RemoteOpcode, body: ByteArray) {
        session?.takeIf { it.authenticated }?.channel?.send(opcode, body)
    }

    fun sendState(message: StateMessage) =
        sendIfConnected(RemoteOpcode.State, RemoteCastJson.encode(message))

    fun sendTracks(message: TracksMessage) =
        sendIfConnected(RemoteOpcode.Tracks, RemoteCastJson.encode(message))

    fun sendEmbeddedSubtitles(message: EmbeddedSubtitlesMessage) =
        sendIfConnected(RemoteOpcode.EmbeddedSubtitles, RemoteCastJson.encode(message))

    fun sendError(message: ErrorMessage) =
        sendIfConnected(RemoteOpcode.Error, RemoteCastJson.encode(message))

    fun sendNavigate(offset: Int) =
        sendIfConnected(RemoteOpcode.Navigate, RemoteCastJson.encode(NavigateMessage(offset)))
}
