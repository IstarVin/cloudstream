package com.lagradost.cloudstream3.cast.remote

import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException

class RemoteCastProtocolTest {
    private fun frame(vararg messages: Pair<RemoteOpcode, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        messages.forEach { (op, body) -> RemoteFraming.write(out, op, body) }
        return out.toByteArray()
    }

    @Test
    fun framingUsesFcastLayout() {
        // size (little endian, includes the opcode), opcode, body
        val bytes = frame(RemoteOpcode.Pause to "hi".toByteArray())
        assertArrayEquals(
            byteArrayOf(3, 0, 0, 0, RemoteOpcode.Pause.value, 'h'.code.toByte(), 'i'.code.toByte()),
            bytes
        )
    }

    @Test
    fun framingRoundTripsMultipleFrames() {
        val input = ByteArrayInputStream(
            frame(
                RemoteOpcode.Play to ByteArray(0),
                RemoteOpcode.Seek to RemoteCastJson.encode(SeekMessage(12_345)),
            )
        )
        val first = RemoteFraming.read(input)!!
        assertEquals(RemoteOpcode.Play, first.opcode)
        assertEquals(0, first.body.size)

        val second = RemoteFraming.read(input)!!
        assertEquals(RemoteOpcode.Seek, second.opcode)
        assertEquals(12_345L, RemoteCastJson.decode<SeekMessage>(second.body).positionMs)

        assertThrows(EOFException::class.java) { RemoteFraming.read(input) }
    }

    @Test
    fun largeBodyRoundTrips() {
        val body = ByteArray(300_000) { (it % 251).toByte() }
        val read = RemoteFraming.read(ByteArrayInputStream(frame(RemoteOpcode.Load to body)))!!
        assertArrayEquals(body, read.body)
    }

    @Test
    fun unknownOpcodeIsSkippedAndStreamStaysInSync() {
        val bytes = ByteArrayOutputStream().apply {
            // Frame with an opcode that does not exist, followed by a normal one
            write(byteArrayOf(4, 0, 0, 0, 99, 1, 2, 3))
            RemoteFraming.write(this, RemoteOpcode.Stop, ByteArray(0))
        }.toByteArray()
        val input = ByteArrayInputStream(bytes)
        assertNull(RemoteFraming.read(input))
        assertEquals(RemoteOpcode.Stop, RemoteFraming.read(input)!!.opcode)
    }

    @Test
    fun invalidSizesAreRejected() {
        // Zero is not valid as the opcode is part of the size
        assertThrows(IOException::class.java) {
            RemoteFraming.read(ByteArrayInputStream(byteArrayOf(0, 0, 0, 0, 1)))
        }
        // Absurdly large, must not try to allocate it
        assertThrows(IOException::class.java) {
            RemoteFraming.read(ByteArrayInputStream(byteArrayOf(-1, -1, -1, 0x7f, 1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteFraming.write(ByteArrayOutputStream(), RemoteOpcode.Load, ByteArray(REMOTE_CAST_MAX_FRAME_BYTES))
        }
    }

    @Test
    fun opcodesAreUniqueAndLookupWorks() {
        assertEquals(RemoteOpcode.entries.size, RemoteOpcode.entries.map { it.value }.toSet().size)
        RemoteOpcode.entries.forEach { assertEquals(it, RemoteOpcode.fromValue(it.value)) }
        assertNull(RemoteOpcode.fromValue(127))
    }

    @Test
    fun loadMessageKeepsLinkHeadersAndSubtitles() = runBlocking {
        val link = newExtractorLink("source", "name 1080p", "https://example.com/video.m3u8", ExtractorLinkType.M3U8) {
            referer = "https://example.com/"
            quality = 1080
            headers = mapOf("User-Agent" to "test", "Cookie" to "a=b")
        }
        val subtitle = SubtitleData(
            originalName = "English",
            nameSuffix = "1",
            url = "https://example.com/en.vtt",
            origin = SubtitleOrigin.URL,
            mimeType = "text/vtt",
            headers = mapOf("X-Test" to "1"),
            languageCode = "en",
        )
        val message = LoadMessage(
            loadId = 7,
            link = link,
            subtitles = listOf(subtitle),
            preferredSubtitle = subtitle,
            startPositionMs = 90_000,
            autoPlay = false,
            sameEpisode = true,
            speed = 1.5f,
            subtitleOffsetMs = -250,
            title = "Episode 1",
        )

        val decoded = RemoteCastJson.decode<LoadMessage>(RemoteCastJson.encode(message))

        assertEquals(7, decoded.loadId)
        assertEquals(link.url, decoded.link.url)
        assertEquals(ExtractorLinkType.M3U8, decoded.link.type)
        assertEquals("https://example.com/", decoded.link.referer)
        assertEquals(1080, decoded.link.quality)
        assertEquals(mapOf("User-Agent" to "test", "Cookie" to "a=b"), decoded.link.headers)
        assertEquals(subtitle, decoded.subtitles.single())
        assertEquals(subtitle.getId(), decoded.preferredSubtitle?.getId())
        assertEquals(90_000L, decoded.startPositionMs)
        assertEquals(false, decoded.autoPlay)
        assertEquals(true, decoded.sameEpisode)
        assertEquals(1.5f, decoded.speed)
        assertEquals(-250L, decoded.subtitleOffsetMs)
    }

    @Test
    fun nullPreferredSubtitleMeansNoSubtitles() {
        val decoded = RemoteCastJson.decode<SetSubtitlesMessage>(
            RemoteCastJson.encode(SetSubtitlesMessage(emptyList(), null))
        )
        assertNull(decoded.preferred)
    }

    @Test
    fun embeddedSubtitleKeepsBackendId() {
        val embedded = SubtitleData(
            "English", "", "track-3", SubtitleOrigin.EMBEDDED_IN_VIDEO, "text/vtt", emptyMap(), "en"
        )
        val decoded = RemoteCastJson.decode<EmbeddedSubtitlesMessage>(
            RemoteCastJson.encode(EmbeddedSubtitlesMessage(3, listOf(embedded)))
        ).tracks.single()
        // The phone matches the TV's selected subtitle on this id
        assertEquals("track-3", decoded.getId())
    }

    @Test
    fun unknownJsonFieldsAreIgnoredForForwardCompatibility() {
        val json = """{"loadId":2,"positionMs":10,"durationMs":100,"status":1,"someFutureField":true}"""
        val state = RemoteCastJson.decode<StateMessage>(json.toByteArray())
        assertEquals(2, state.loadId)
        assertEquals(REMOTE_STATUS_PLAYING, state.status)
        assertEquals(1.0f, state.speed)
    }

    @Test
    fun tracksRoundTrip() {
        val audio = WireTrack("a1", "English", "en", "audio/mp4a", channelCount = 6, formatIndex = 2)
        val message = TracksMessage(
            loadId = 4,
            currentAudio = audio,
            audio = listOf(audio),
            video = listOf(WireTrack("v1", null, null, "video/avc", width = 1920, height = 1080)),
            selectedSubtitleId = "sub",
        )
        val decoded = RemoteCastJson.decode<TracksMessage>(RemoteCastJson.encode(message))
        val tracks = decoded.toCurrentTracks()
        assertEquals("a1", tracks.currentAudioTrack?.id)
        assertEquals(2, tracks.currentAudioTrack?.formatIndex)
        assertEquals(1080, tracks.allVideoTracks.single().height)
        assertEquals("sub", decoded.selectedSubtitleId)
        assertNotNull(tracks.allAudioTracks.single())
    }
}
