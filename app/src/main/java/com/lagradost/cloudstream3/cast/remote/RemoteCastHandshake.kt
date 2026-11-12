package com.lagradost.cloudstream3.cast.remote

/** Remembers which phones have been paired with this TV. */
interface PairingStorage {
    fun getToken(controllerId: String): String?
    fun saveToken(controllerId: String, token: String)
}

/**
 * TV side of the handshake, one instance per connection. Kept free of Android classes so it can be unit tested.
 *
 * 1. The phone sends a [HelloMessage] with the token from an earlier pairing (if it has one).
 * 2. A valid token authenticates directly. Otherwise a PIN is made, shown on the TV, and the phone is told to ask for it.
 * 3. The phone sends a [HelloMessage] with the PIN. If it is right a token is made, stored and sent back.
 */
class ReceiverHandshake(
    private val ownId: String,
    private val ownName: String,
    private val storage: PairingStorage,
    private val newPin: () -> String,
    private val newToken: () -> String,
    private val maxPinTries: Int = 3,
) {
    sealed interface Result {
        val ack: HelloAckMessage

        /** The phone may now send commands */
        data class Authenticated(override val ack: HelloAckMessage, val wasPairing: Boolean) : Result

        /** The phone has to send the PIN. [showPin] is set when it should be (newly) displayed on the TV */
        data class NeedsPin(override val ack: HelloAckMessage, val showPin: String?) : Result

        /** Send the ack, then close the connection */
        data class Rejected(override val ack: HelloAckMessage) : Result
    }

    /** The PIN currently shown on the TV for this connection, if any */
    var pin: String? = null
        private set

    private var pinTries = 0

    private fun ack(
        accepted: Boolean,
        needsPin: Boolean = false,
        token: String? = null,
        error: String? = null,
    ) = HelloAckMessage(
        version = REMOTE_CAST_PROTOCOL_VERSION,
        deviceId = ownId,
        deviceName = ownName,
        accepted = accepted,
        needsPin = needsPin,
        token = token,
        error = error,
    )

    fun onHello(hello: HelloMessage): Result {
        if (hello.version != REMOTE_CAST_PROTOCOL_VERSION) {
            return Result.Rejected(ack(false, error = "Unsupported protocol version ${hello.version}"))
        }

        val stored = storage.getToken(hello.deviceId)
        if (stored != null && hello.token != null && stored == hello.token) {
            return Result.Authenticated(ack(true), wasPairing = false)
        }

        val currentPin = pin
        if (currentPin != null && hello.pin != null) {
            if (hello.pin == currentPin) {
                val token = newToken()
                storage.saveToken(hello.deviceId, token)
                pin = null
                return Result.Authenticated(ack(true, token = token), wasPairing = true)
            }
            pinTries++
            return if (pinTries >= maxPinTries) {
                pin = null
                Result.Rejected(ack(false, error = "Too many wrong PINs"))
            } else {
                Result.NeedsPin(ack(false, needsPin = true, error = "Wrong PIN"), showPin = null)
            }
        }

        // First contact (or the token was not accepted), the user has to type the PIN from the TV into the phone
        val shown = pin ?: newPin().also { pin = it }
        return Result.NeedsPin(ack(false, needsPin = true), showPin = shown)
    }
}
