package com.lagradost.cloudstream3.cast.remote

import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.subtitles.SaveCaptionStyle
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Wire protocol used by the "CloudStream TV" cast feature.
 *
 * The phone resolves links/subtitles and acts as a remote control, the TV only plays what it is told to.
 *
 * Framing is the same as FCast (4 byte little endian size, 1 byte opcode, json body), where size is
 * `body.size + 1`, but the opcodes and messages are our own, as FCast has no support for tracks and subtitles.
 */

/** Advertised over NSD by the TV, and discovered by the phone */
const val REMOTE_CAST_SERVICE_TYPE = "_cloudstream-cast._tcp"
const val REMOTE_CAST_DEFAULT_PORT = 46901
const val REMOTE_CAST_PROTOCOL_VERSION = 1

/** Hard limit to prevent a bad peer from making us allocate gigabytes */
const val REMOTE_CAST_MAX_FRAME_BYTES = 8 * 1024 * 1024

/** Status codes used in [StateMessage.status] */
const val REMOTE_STATUS_PAUSED = 0
const val REMOTE_STATUS_PLAYING = 1
const val REMOTE_STATUS_BUFFERING = 2
const val REMOTE_STATUS_ENDED = 3
const val REMOTE_STATUS_IDLE = 4

enum class RemoteOpcode(val value: Byte) {
    // Both directions
    Ping(1),
    Pong(2),

    // Handshake
    Hello(10),
    HelloAck(11),

    // Phone -> TV
    Load(20),
    Play(21),
    Pause(22),
    Seek(23),
    SetSpeed(24),
    Stop(25),
    SetSubtitles(26),
    SetSubtitleStyle(27),
    SetSubtitleOffset(28),
    SetAudioTrack(29),
    SetMaxVideoSize(30),

    // TV -> Phone
    State(40),
    Tracks(41),
    EmbeddedSubtitles(42),
    Error(43),
    Navigate(44),
    Stopped(45);

    companion object {
        private val byValue = entries.associateBy { it.value }
        fun fromValue(value: Byte): RemoteOpcode? = byValue[value]
    }
}

@Serializable
data class HelloMessage(
    val version: Int,
    val deviceId: String,
    val deviceName: String,
    /** Token received after a successful pairing */
    val token: String? = null,
    /** PIN displayed on the TV, only used during pairing */
    val pin: String? = null,
)

@Serializable
data class HelloAckMessage(
    val version: Int,
    val deviceId: String,
    val deviceName: String,
    val accepted: Boolean,
    /** The controller must send a [HelloMessage] with [HelloMessage.pin] set */
    val needsPin: Boolean = false,
    /** Only set when pairing just succeeded */
    val token: String? = null,
    val error: String? = null,
)

@Serializable
data class LoadMessage(
    /** Incremented by the phone for each load, echoed back in [StateMessage.loadId] */
    val loadId: Int,
    val link: ExtractorLink,
    val subtitles: List<SubtitleData> = emptyList(),
    val preferredSubtitle: SubtitleData? = null,
    val startPositionMs: Long = 0,
    val autoPlay: Boolean = true,
    val sameEpisode: Boolean = false,
    val speed: Float = 1.0f,
    val subtitleOffsetMs: Long = 0,
    val subtitleStyle: SaveCaptionStyle? = null,
    val title: String? = null,
)

@Serializable
data class SeekMessage(val positionMs: Long)

@Serializable
data class SetSpeedMessage(val speed: Float)

@Serializable
data class SetSubtitlesMessage(
    val subtitles: List<SubtitleData>,
    /** null means no subtitles */
    val preferred: SubtitleData?,
)

@Serializable
data class SetSubtitleStyleMessage(val style: SaveCaptionStyle)

@Serializable
data class SetSubtitleOffsetMessage(val offsetMs: Long)

@Serializable
data class SetAudioTrackMessage(
    val language: String?,
    val id: String?,
    val formatIndex: Int?,
)

@Serializable
data class SetMaxVideoSizeMessage(
    val width: Int,
    val height: Int,
    val id: String?,
)

@Serializable
data class StateMessage(
    val loadId: Int,
    val positionMs: Long,
    /** < 0 if unknown */
    val durationMs: Long,
    /** One of the REMOTE_STATUS_* constants */
    val status: Int,
    val speed: Float = 1.0f,
    val width: Int = 0,
    val height: Int = 0,
    val subtitleOffsetMs: Long = 0,
)

@Serializable
data class WireTrack(
    val id: String?,
    val label: String?,
    val language: String?,
    val mimeType: String?,
    val width: Int? = null,
    val height: Int? = null,
    val channelCount: Int? = null,
    val formatIndex: Int? = null,
)

@Serializable
data class TracksMessage(
    val loadId: Int,
    val currentVideo: WireTrack? = null,
    val currentAudio: WireTrack? = null,
    val currentText: List<WireTrack> = emptyList(),
    val video: List<WireTrack> = emptyList(),
    val audio: List<WireTrack> = emptyList(),
    val text: List<WireTrack> = emptyList(),
    /** [SubtitleData.getId] of the subtitle the TV is currently using */
    val selectedSubtitleId: String? = null,
)

@Serializable
data class EmbeddedSubtitlesMessage(
    val loadId: Int,
    val tracks: List<SubtitleData>,
)

@Serializable
data class ErrorMessage(
    val loadId: Int,
    val message: String,
)

/** The user pressed next/previous on the TV, the phone decides what that means */
@Serializable
data class NavigateMessage(val offset: Int)

object RemoteCastJson {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    inline fun <reified T> encode(value: T): ByteArray =
        json.encodeToString(kotlinx.serialization.serializer<T>(), value).toByteArray(Charsets.UTF_8)

    inline fun <reified T> decode(body: ByteArray): T =
        json.decodeFromString(kotlinx.serialization.serializer<T>(), String(body, Charsets.UTF_8))
}

data class RemoteFrame(val opcode: RemoteOpcode, val body: ByteArray)

object RemoteFraming {
    /** Little endian, starting from 1 (opcode included in the size). Same as FCast. */
    fun write(out: OutputStream, opcode: RemoteOpcode, body: ByteArray) {
        val size = body.size + 1
        require(size <= REMOTE_CAST_MAX_FRAME_BYTES) { "Frame too large: $size" }
        val header = ByteArray(5) { i ->
            when (i) {
                in 0..3 -> (size shr (8 * i) and 0xff).toByte()
                else -> opcode.value
            }
        }
        // One write so frames are not interleaved at the TCP level when called synchronized
        out.write(header + body)
        out.flush()
    }

    /**
     * Reads one frame, null if the opcode is unknown (the frame is skipped to keep the stream in sync).
     * @throws EOFException when the stream is closed
     */
    fun read(input: InputStream): RemoteFrame? {
        val data = DataInputStream(input)
        val sizeBytes = ByteArray(4)
        data.readFully(sizeBytes)
        var size = 0
        for (i in 0..3) {
            size = size or ((sizeBytes[i].toInt() and 0xff) shl (8 * i))
        }
        if (size < 1 || size > REMOTE_CAST_MAX_FRAME_BYTES) {
            throw java.io.IOException("Invalid frame size: $size")
        }
        val opcodeValue = data.readByte()
        val body = ByteArray(size - 1)
        data.readFully(body)
        val opcode = RemoteOpcode.fromValue(opcodeValue) ?: return null
        return RemoteFrame(opcode, body)
    }
}
