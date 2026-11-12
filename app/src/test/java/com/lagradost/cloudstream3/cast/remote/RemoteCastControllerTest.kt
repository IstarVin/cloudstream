package com.lagradost.cloudstream3.cast.remote

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The real [RemoteCast] controller talking to a fake TV over real sockets.
 * The fake TV uses the real [ReceiverHandshake] and [RemoteChannel].
 */
class RemoteCastControllerTest {
    private class FakeTv {
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        val tokens = mutableMapOf<String, String>()
        val received = LinkedBlockingQueue<RemoteFrame>()
        val channels = CopyOnWriteArrayList<RemoteChannel>()
        var shownPin: String? = null
        var connections = 0
        var pinCounter = 0
        @Volatile
        var accepting = true

        private val storage = object : PairingStorage {
            override fun getToken(controllerId: String) = tokens[controllerId]
            override fun saveToken(controllerId: String, token: String) {
                tokens[controllerId] = token
            }
        }

        init {
            Thread {
                while (accepting) {
                    val socket = try {
                        server.accept()
                    } catch (_: Throwable) {
                        return@Thread
                    }
                    if (!accepting) {
                        socket.close()
                        continue
                    }
                    connections++
                    val handshake = ReceiverHandshake(
                        "tv-id", "Living room TV", storage,
                        newPin = { "55${pinCounter++}5" },
                        newToken = { "token-${tokens.size}" },
                    )
                    var authenticated = false
                    lateinit var channel: RemoteChannel
                    channel = RemoteChannel(socket, onFrame = { frame ->
                        if (frame.opcode == RemoteOpcode.Hello) {
                            val r = handshake.onHello(RemoteCastJson.decode(frame.body))
                            when (r) {
                                is ReceiverHandshake.Result.Authenticated -> authenticated = true
                                is ReceiverHandshake.Result.NeedsPin -> r.showPin?.let { shownPin = it }
                                is ReceiverHandshake.Result.Rejected -> Unit
                            }
                            channel.send(RemoteOpcode.HelloAck, r.ack)
                        } else if (authenticated) {
                            received.add(frame)
                        }
                    }, onClosed = {})
                    channels += channel
                    channel.start()
                }
            }.apply { isDaemon = true; start() }
        }

        fun device() = RemoteCastDevice("TV", "127.0.0.1", server.localPort, "tv-id")

        fun send(opcode: RemoteOpcode, body: ByteArray = ByteArray(0)) {
            channels.last().send(opcode, body)
        }

        fun dropConnection() {
            channels.last().close()
        }

        fun close() {
            accepting = false
            channels.forEach { it.close() }
            server.close()
        }

        fun next(): RemoteFrame? = received.poll(5, TimeUnit.SECONDS)
    }

    private class MemoryStorage : ControllerStorage {
        override val deviceId = "phone-id"
        override val deviceName = "Pixel"
        val tokens = mutableMapOf<String, String>()
        override fun getToken(device: RemoteCastDevice) = tokens[device.host]
        override fun saveToken(device: RemoteCastDevice, tvId: String, token: String) {
            tokens[device.host] = token
        }

        override fun forgetToken(device: RemoteCastDevice) {
            tokens.remove(device.host)
        }
    }

    private class Recorder : RemoteCastListener {
        val statuses = CopyOnWriteArrayList<RemoteCastStatus>()
        val messages = LinkedBlockingQueue<Pair<RemoteOpcode, ByteArray>>()
        override fun onStatusChanged(status: RemoteCastStatus) {
            statuses += status
        }

        override fun onMessage(opcode: RemoteOpcode, body: ByteArray) {
            messages.add(opcode to body)
        }
    }

    private lateinit var tv: FakeTv
    private val storage = MemoryStorage()
    private val recorder = Recorder()

    @Before
    fun setUp() {
        RemoteCast.postToMain = { it() }
        RemoteCast.disconnect(sendStop = false)
        tv = FakeTv()
        RemoteCast.addListener(recorder)
    }

    @After
    fun tearDown() {
        RemoteCast.removeListener(recorder)
        RemoteCast.disconnect(sendStop = false)
        tv.close()
    }

    private fun connect(
        pins: List<String?> = emptyList(),
        asked: MutableList<Pair<String, Boolean>> = mutableListOf(),
    ): RemoteCast.ConnectResult {
        val result = AtomicReference<RemoteCast.ConnectResult>()
        val done = CountDownLatch(1)
        val remaining = pins.toMutableList()
        RemoteCast.connect(storage, tv.device(), { name, wasWrong, submit ->
            asked += name to wasWrong
            submit(if (remaining.isEmpty()) null else remaining.removeAt(0))
        }) {
            result.set(it)
            done.countDown()
        }
        assertTrue("connect never finished", done.await(10, TimeUnit.SECONDS))
        return result.get()
    }

    @Test
    fun firstConnectionAsksForPinThenStoresToken() {
        val asked = mutableListOf<Pair<String, Boolean>>()
        // The PIN is only known once the TV shows it, so ask the TV (the fake shows 5505)
        val result = connect(pins = listOf("5505"), asked = asked)

        assertTrue(result is RemoteCast.ConnectResult.Success)
        assertEquals("Living room TV", (result as RemoteCast.ConnectResult.Success).deviceName)
        assertEquals(listOf("Living room TV" to false), asked)
        assertEquals(RemoteCastStatus.Connected, RemoteCast.status)
        assertTrue(RemoteCast.isActive)
        assertNotNull(storage.tokens["127.0.0.1"])
        assertEquals("Living room TV", RemoteCast.deviceName)
    }

    @Test
    fun secondConnectionUsesTheStoredTokenWithoutPin() {
        connect(pins = listOf("5505"))
        RemoteCast.disconnect(sendStop = false)

        val asked = mutableListOf<Pair<String, Boolean>>()
        val result = connect(asked = asked)

        assertTrue(result is RemoteCast.ConnectResult.Success)
        assertTrue("No PIN should be needed again", asked.isEmpty())
        assertEquals(2, tv.connections)
    }

    @Test
    fun wrongPinIsRetriedAndMarkedAsWrong() {
        val asked = mutableListOf<Pair<String, Boolean>>()
        val result = connect(pins = listOf("0000", "5505"), asked = asked)

        assertTrue(result is RemoteCast.ConnectResult.Success)
        assertEquals(listOf(false, true), asked.map { it.second })
    }

    @Test
    fun cancellingThePinFailsQuietlyAndStaysDisconnected() {
        val result = connect(pins = listOf(null))

        assertEquals(RemoteCast.ConnectResult.Failure(null), result)
        assertEquals(RemoteCastStatus.Disconnected, RemoteCast.status)
        assertFalse(RemoteCast.isActive)
        assertNull(RemoteCast.device)
        assertTrue(storage.tokens.isEmpty())
    }

    @Test
    fun tooManyWrongPinsFails() {
        val result = connect(pins = listOf("1", "2", "3", "4"))
        assertTrue(result is RemoteCast.ConnectResult.Failure)
        assertEquals(RemoteCastStatus.Disconnected, RemoteCast.status)
    }

    @Test
    fun connectingToNothingFailsInsteadOfHanging() {
        val device = tv.device()
        tv.close()
        val done = CountDownLatch(1)
        val result = AtomicReference<RemoteCast.ConnectResult>()
        RemoteCast.connect(storage, device, { _, _, submit -> submit(null) }) {
            result.set(it)
            done.countDown()
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertTrue(result.get() is RemoteCast.ConnectResult.Failure)
        assertEquals(RemoteCastStatus.Disconnected, RemoteCast.status)
    }

    @Test
    fun messagesFlowBothWaysOnceConnected() {
        connect(pins = listOf("5505"))

        RemoteCast.send(RemoteOpcode.Seek, SeekMessage(9_000))
        val frame = tv.next()!!
        assertEquals(RemoteOpcode.Seek, frame.opcode)
        assertEquals(9_000L, RemoteCastJson.decode<SeekMessage>(frame.body).positionMs)

        tv.send(RemoteOpcode.State, RemoteCastJson.encode(StateMessage(1, 100, 1000, REMOTE_STATUS_PLAYING)))
        val (op, body) = recorder.messages.poll(5, TimeUnit.SECONDS)!!
        assertEquals(RemoteOpcode.State, op)
        assertEquals(100L, RemoteCastJson.decode<StateMessage>(body).positionMs)
    }

    @Test
    fun handshakeMessagesAreNotPassedToListeners() {
        connect(pins = listOf("5505"))
        assertNull(recorder.messages.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun disconnectTellsTheTvToStopFirst() {
        connect(pins = listOf("5505"))
        RemoteCast.disconnect()
        assertEquals(RemoteOpcode.Stop, tv.next()!!.opcode)
        assertEquals(RemoteCastStatus.Disconnected, RemoteCast.status)
        assertNull(RemoteCast.device)
    }

    @Test
    fun nothingIsSentWhenNotConnected() {
        RemoteCast.send(RemoteOpcode.Play)
        assertNull(tv.received.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun droppedConnectionIsReestablishedWithTheTokenAndNoPin() {
        connect(pins = listOf("5505"))
        recorder.statuses.clear()

        tv.dropConnection()

        // Reconnects on its own, first try is after a second
        val deadline = System.currentTimeMillis() + 15_000
        while (tv.connections < 2 && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals("expected a reconnect", 2, tv.connections)
        while (RemoteCast.status != RemoteCastStatus.Connected && System.currentTimeMillis() < deadline) Thread.sleep(50)

        assertEquals(RemoteCastStatus.Connected, RemoteCast.status)
        assertTrue(RemoteCastStatus.Reconnecting in recorder.statuses)

        // And it works again
        RemoteCast.send(RemoteOpcode.Play)
        assertEquals(RemoteOpcode.Play, tv.next()!!.opcode)
    }

    @Test
    fun deliberateDisconnectDoesNotReconnect() {
        connect(pins = listOf("5505"))
        RemoteCast.disconnect(sendStop = false)
        Thread.sleep(2_500)
        assertEquals(1, tv.connections)
        assertEquals(RemoteCastStatus.Disconnected, RemoteCast.status)
    }

    @Test
    fun givesUpAfterTheTvIsGone() {
        connect(pins = listOf("5505"))
        tv.close()
        val deadline = System.currentTimeMillis() + 60_000
        while (RemoteCast.status != RemoteCastStatus.Disconnected && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        assertEquals(RemoteCastStatus.Disconnected, RemoteCast.status)
        assertTrue(RemoteCastStatus.Reconnecting in recorder.statuses)
    }

    @Test
    fun connectingToAnotherTvReplacesTheFirst() {
        connect(pins = listOf("5505"))
        val second = FakeTv()
        try {
            val done = CountDownLatch(1)
            RemoteCast.connect(storage, second.device(), { _, _, submit -> submit("5505") }) { done.countDown() }
            assertTrue(done.await(10, TimeUnit.SECONDS))
            RemoteCast.send(RemoteOpcode.Pause)
            assertEquals(RemoteOpcode.Pause, second.next()!!.opcode)
            assertNull(tv.received.poll(300, TimeUnit.MILLISECONDS))
        } finally {
            second.close()
        }
    }
}
