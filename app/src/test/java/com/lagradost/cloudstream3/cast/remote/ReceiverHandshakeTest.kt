package com.lagradost.cloudstream3.cast.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverHandshakeTest {
    private class MemoryStorage : PairingStorage {
        val tokens = mutableMapOf<String, String>()
        override fun getToken(controllerId: String) = tokens[controllerId]
        override fun saveToken(controllerId: String, token: String) {
            tokens[controllerId] = token
        }
    }

    private val storage = MemoryStorage()
    private var pins = 0
    private var tokens = 0

    private fun handshake(maxTries: Int = 3) = ReceiverHandshake(
        ownId = "tv-id",
        ownName = "Living room",
        storage = storage,
        newPin = { "123${pins++}" },
        newToken = { "token-${tokens++}" },
        maxPinTries = maxTries,
    )

    private fun hello(token: String? = null, pin: String? = null, version: Int = REMOTE_CAST_PROTOCOL_VERSION) =
        HelloMessage(version, "phone-id", "Pixel", token = token, pin = pin)

    @Test
    fun unknownPhoneIsAskedForAPin() {
        val result = handshake().onHello(hello()) as ReceiverHandshake.Result.NeedsPin
        assertEquals("1230", result.showPin)
        assertFalse(result.ack.accepted)
        assertTrue(result.ack.needsPin)
        assertNull(result.ack.error)
        assertEquals("tv-id", result.ack.deviceId)
        assertEquals("Living room", result.ack.deviceName)
    }

    @Test
    fun correctPinPairsAndStoresToken() {
        val h = handshake()
        h.onHello(hello())
        val result = h.onHello(hello(pin = "1230")) as ReceiverHandshake.Result.Authenticated
        assertTrue(result.wasPairing)
        assertTrue(result.ack.accepted)
        assertEquals("token-0", result.ack.token)
        assertEquals("token-0", storage.tokens["phone-id"])
        assertNull(h.pin)
    }

    @Test
    fun storedTokenAuthenticatesWithoutPin() {
        storage.tokens["phone-id"] = "secret"
        val result = handshake().onHello(hello(token = "secret")) as ReceiverHandshake.Result.Authenticated
        assertFalse(result.wasPairing)
        assertTrue(result.ack.accepted)
        // The token is not sent again
        assertNull(result.ack.token)
    }

    @Test
    fun wrongTokenFallsBackToPin() {
        storage.tokens["phone-id"] = "secret"
        val result = handshake().onHello(hello(token = "stale")) as ReceiverHandshake.Result.NeedsPin
        assertNotNull(result.showPin)
        assertNull(result.ack.error)
    }

    @Test
    fun tokenOfAnotherPhoneIsNotAccepted() {
        storage.tokens["other-phone"] = "secret"
        assertTrue(handshake().onHello(hello(token = "secret")) is ReceiverHandshake.Result.NeedsPin)
    }

    @Test
    fun wrongPinCanBeRetriedThenIsRejected() {
        val h = handshake(maxTries = 3)
        h.onHello(hello())

        val first = h.onHello(hello(pin = "0000")) as ReceiverHandshake.Result.NeedsPin
        assertEquals("Wrong PIN", first.ack.error)
        // Same PIN stays on screen, it must not change on every attempt
        assertNull(first.showPin)
        assertTrue(h.onHello(hello(pin = "1111")) is ReceiverHandshake.Result.NeedsPin)

        assertTrue(h.onHello(hello(pin = "2222")) is ReceiverHandshake.Result.Rejected)
        assertTrue(storage.tokens.isEmpty())
        // After being locked out the old PIN no longer works
        assertNull(h.pin)
    }

    @Test
    fun pinSentBeforeOneWasShownDoesNotAuthenticate() {
        val h = handshake()
        val result = h.onHello(hello(pin = "1230"))
        assertTrue(result is ReceiverHandshake.Result.NeedsPin)
        assertTrue(storage.tokens.isEmpty())
    }

    @Test
    fun pinIsReusedWhenPhoneAsksAgain() {
        val h = handshake()
        h.onHello(hello())
        val again = h.onHello(hello()) as ReceiverHandshake.Result.NeedsPin
        assertEquals("1230", again.showPin)
        assertEquals(1, pins)
    }

    @Test
    fun wrongProtocolVersionIsRejected() {
        val result = handshake().onHello(hello(version = REMOTE_CAST_PROTOCOL_VERSION + 1))
        assertTrue(result is ReceiverHandshake.Result.Rejected)
        assertFalse(result.ack.accepted)
    }
}
