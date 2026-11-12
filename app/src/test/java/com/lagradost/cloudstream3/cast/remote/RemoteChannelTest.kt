package com.lagradost.cloudstream3.cast.remote

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Runs the real channel over real loopback sockets. */
class RemoteChannelTest {
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val toClose = mutableListOf<RemoteChannel>()

    private class Peer(
        val channel: RemoteChannel,
        val frames: LinkedBlockingQueue<RemoteFrame>,
        val closed: CountDownLatch,
        val closeError: AtomicReference<Throwable?>,
    ) {
        fun next(): RemoteFrame? = frames.poll(5, TimeUnit.SECONDS)
    }

    private fun peer(socket: Socket, onFrame: (RemoteChannel, RemoteFrame) -> Unit = { _, _ -> }): Peer {
        val frames = LinkedBlockingQueue<RemoteFrame>()
        val closed = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        lateinit var channel: RemoteChannel
        channel = RemoteChannel(
            socket,
            onFrame = { frame ->
                frames.add(frame)
                onFrame(channel, frame)
            },
            onClosed = {
                error.set(it)
                closed.countDown()
            },
        )
        channel.start()
        return Peer(channel, frames, closed, error).also { toClose += channel }
    }

    private fun pair(): Pair<Peer, Peer> {
        val client = Socket(InetAddress.getLoopbackAddress(), server.localPort)
        val serverSide = server.accept()
        return peer(client) to peer(serverSide)
    }

    @After
    fun tearDown() {
        toClose.forEach { it.close() }
        server.close()
    }

    @Test
    fun messagesFlowBothWays() {
        val (phone, tv) = pair()

        phone.channel.send(RemoteOpcode.Seek, SeekMessage(4_000))
        val received = tv.next()!!
        assertEquals(RemoteOpcode.Seek, received.opcode)
        assertEquals(4_000L, RemoteCastJson.decode<SeekMessage>(received.body).positionMs)

        tv.channel.send(RemoteOpcode.State, StateMessage(1, 4_000, 60_000, REMOTE_STATUS_PLAYING))
        val state = RemoteCastJson.decode<StateMessage>(phone.next()!!.body)
        assertEquals(REMOTE_STATUS_PLAYING, state.status)
        assertEquals(60_000L, state.durationMs)
    }

    @Test
    fun messagesKeepTheirOrderEvenWhenManyAreSentFromOneThread() {
        val (phone, tv) = pair()
        repeat(200) { phone.channel.send(RemoteOpcode.Seek, SeekMessage(it.toLong())) }
        repeat(200) {
            val frame = tv.next()!!
            assertEquals(it.toLong(), RemoteCastJson.decode<SeekMessage>(frame.body).positionMs)
        }
    }

    @Test
    fun pingIsAnsweredAndNeverShownToTheApp() {
        val (phone, tv) = pair()
        phone.channel.send(RemoteOpcode.Ping)
        phone.channel.send(RemoteOpcode.Play)
        // Only the play arrives, the ping was handled by the channel itself
        assertEquals(RemoteOpcode.Play, tv.next()!!.opcode)
        assertNull(tv.frames.poll(200, TimeUnit.MILLISECONDS))
        assertNull(phone.frames.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun closingOneSideNotifiesTheOther() {
        val (phone, tv) = pair()
        phone.channel.close()
        assertTrue(phone.closed.await(5, TimeUnit.SECONDS))
        assertTrue(tv.closed.await(5, TimeUnit.SECONDS))
        assertTrue(tv.channel.isClosed)
    }

    @Test
    fun sendingOnAClosedChannelDoesNothing() {
        val (phone, tv) = pair()
        tv.channel.close()
        assertTrue(phone.closed.await(5, TimeUnit.SECONDS))
        // Must neither throw nor hang
        phone.channel.send(RemoteOpcode.Play)
        phone.channel.close()
    }

    @Test
    fun closeAfterFlushDeliversQueuedMessagesFirst() {
        val (phone, tv) = pair()
        phone.channel.send(RemoteOpcode.Stop)
        phone.channel.closeAfterFlush()
        assertEquals(RemoteOpcode.Stop, tv.next()!!.opcode)
        assertTrue(tv.closed.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun badMessageHandlerDoesNotKillTheConnection() {
        val client = Socket(InetAddress.getLoopbackAddress(), server.localPort)
        val serverSide = server.accept()
        val phone = peer(client)
        val received = LinkedBlockingQueue<RemoteOpcode>()
        val tv = RemoteChannel(
            serverSide,
            onFrame = { frame ->
                received.add(frame.opcode)
                if (frame.opcode == RemoteOpcode.Pause) error("boom")
            },
            onClosed = {},
        ).also { it.start(); toClose += it }

        phone.channel.send(RemoteOpcode.Pause)
        phone.channel.send(RemoteOpcode.Play)
        assertEquals(RemoteOpcode.Pause, received.poll(5, TimeUnit.SECONDS))
        assertEquals(RemoteOpcode.Play, received.poll(5, TimeUnit.SECONDS))
        assertFalse(tv.isClosed)
    }

    @Test
    fun garbageOnTheSocketClosesTheChannelWithAnError() {
        val client = Socket(InetAddress.getLoopbackAddress(), server.localPort)
        val serverSide = server.accept()
        val tv = peer(serverSide)
        // Claims a 2GB frame
        client.getOutputStream().apply {
            write(byteArrayOf(-1, -1, -1, 0x7f, 1))
            flush()
        }
        assertTrue(tv.closed.await(5, TimeUnit.SECONDS))
        assertNotNull(tv.closeError.get())
        client.close()
    }

    /**
     * The whole pairing conversation, TV logic is the real [ReceiverHandshake],
     * phone side follows the same steps as [RemoteCast].
     */
    @Test
    fun pairingWithPinThenReconnectingWithTokenOverRealSockets() {
        val tokens = mutableMapOf<String, String>()
        val storage = object : PairingStorage {
            override fun getToken(controllerId: String) = tokens[controllerId]
            override fun saveToken(controllerId: String, token: String) {
                tokens[controllerId] = token
            }
        }
        var shownPin: String? = null

        fun connectPhone(token: String?, pin: String?): Triple<Peer, Peer, ReceiverHandshake> {
            val client = Socket(InetAddress.getLoopbackAddress(), server.localPort)
            val serverSide = server.accept()
            val handshake = ReceiverHandshake("tv", "TV", storage, newPin = { "4321" }, newToken = { "tok-1" })
            val tv = peer(serverSide) { channel, frame ->
                if (frame.opcode == RemoteOpcode.Hello) {
                    val result = handshake.onHello(RemoteCastJson.decode(frame.body))
                    (result as? ReceiverHandshake.Result.NeedsPin)?.showPin?.let { shownPin = it }
                    channel.send(RemoteOpcode.HelloAck, result.ack)
                }
            }
            val phone = peer(client)
            phone.channel.send(RemoteOpcode.Hello, HelloMessage(1, "phone", "Pixel", token = token, pin = pin))
            return Triple(phone, tv, handshake)
        }

        // First time, no token: TV asks for PIN
        val (phone, _, _) = connectPhone(token = null, pin = null)
        val ask = RemoteCastJson.decode<HelloAckMessage>(phone.next()!!.body)
        assertTrue(ask.needsPin)
        assertFalse(ask.accepted)
        assertEquals("4321", shownPin)

        // Wrong PIN is refused but the connection stays open
        phone.channel.send(RemoteOpcode.Hello, HelloMessage(1, "phone", "Pixel", pin = "0000"))
        val wrong = RemoteCastJson.decode<HelloAckMessage>(phone.next()!!.body)
        assertEquals("Wrong PIN", wrong.error)
        assertTrue(wrong.needsPin)

        // Right PIN pairs and returns a token
        phone.channel.send(RemoteOpcode.Hello, HelloMessage(1, "phone", "Pixel", pin = "4321"))
        val paired = RemoteCastJson.decode<HelloAckMessage>(phone.next()!!.body)
        assertTrue(paired.accepted)
        assertEquals("tok-1", paired.token)

        // Later, a new connection with the token needs no PIN
        val (phone2, _, _) = connectPhone(token = paired.token, pin = null)
        val again = RemoteCastJson.decode<HelloAckMessage>(phone2.next()!!.body)
        assertTrue(again.accepted)
        assertNull(again.token)
    }
}
