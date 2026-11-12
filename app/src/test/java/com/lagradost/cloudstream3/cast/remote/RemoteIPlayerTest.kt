package com.lagradost.cloudstream3.cast.remote

import android.content.ContextWrapper
import com.lagradost.cloudstream3.ui.player.CSPlayerEvent
import com.lagradost.cloudstream3.ui.player.CSPlayerLoading
import com.lagradost.cloudstream3.ui.player.EmbeddedSubtitlesFetchedEvent
import com.lagradost.cloudstream3.ui.player.EpisodeSeekEvent
import com.lagradost.cloudstream3.ui.player.ErrorEvent
import com.lagradost.cloudstream3.ui.player.PauseEvent
import com.lagradost.cloudstream3.ui.player.PlayEvent
import com.lagradost.cloudstream3.ui.player.PlayerAttachedEvent
import com.lagradost.cloudstream3.ui.player.PlayerEvent
import com.lagradost.cloudstream3.ui.player.PlayerEventSource
import com.lagradost.cloudstream3.ui.player.PositionEvent
import com.lagradost.cloudstream3.ui.player.StatusEvent
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import com.lagradost.cloudstream3.ui.player.TracksChangedEvent
import com.lagradost.cloudstream3.ui.player.VideoEndedEvent
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.videoskip.SkipStamp
import com.lagradost.cloudstream3.utils.videoskip.SkipType
import com.lagradost.cloudstream3.utils.videoskip.VideoSkipStamp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteIPlayerTest {
    private class FakeLink : RemoteLink {
        val sent = mutableListOf<Pair<RemoteOpcode, ByteArray>>()
        val listeners = mutableListOf<RemoteCastListener>()

        override fun send(opcode: RemoteOpcode, body: ByteArray) {
            sent += opcode to body
        }

        override fun addListener(listener: RemoteCastListener) {
            listeners += listener
        }

        override fun removeListener(listener: RemoteCastListener) {
            listeners -= listener
        }

        fun ops() = sent.map { it.first }
        fun count(op: RemoteOpcode) = sent.count { it.first == op }
        inline fun <reified T> last(op: RemoteOpcode): T =
            RemoteCastJson.decode(sent.last { it.first == op }.second)

        fun clear() = sent.clear()
    }

    private class FakeMirror : RemoteMirror {
        var refreshes = 0
        var released = false
        override fun refresh() {
            refreshes++
        }

        override fun release() {
            released = true
        }
    }

    private val link = FakeLink()
    private val mirror = FakeMirror()
    private var now = 1_000L
    private val notifications = mutableListOf<Int>()
    private val events = mutableListOf<PlayerEvent>()
    private val context = ContextWrapper(null)

    private val player = RemoteIPlayer(
        remote = link,
        clock = { now },
        mirrorFactory = { mirror },
        notify = { notifications += it },
    ).also { it.initCallbacks({ e -> events += e }, null) }

    private fun makeLink(name: String = "source", type: ExtractorLinkType = ExtractorLinkType.M3U8) = runBlocking {
        newExtractorLink(name, name, "https://example.com/$name", type) {
            referer = "https://example.com"
            headers = mapOf("h" to "v")
        }
    }

    private fun sub(name: String, origin: SubtitleOrigin = SubtitleOrigin.URL, url: String = "https://s/$name") =
        SubtitleData(name, "", url, origin, "text/vtt", emptyMap(), "en")

    private fun load(
        link: ExtractorLink = makeLink(),
        sameEpisode: Boolean = false,
        start: Long? = 0,
        subtitles: Set<SubtitleData> = emptySet(),
        subtitle: SubtitleData? = null,
        autoPlay: Boolean? = true,
    ) = player.loadPlayer(context, sameEpisode, link, null, start, subtitles, subtitle, autoPlay, false)

    private fun tvState(
        loadId: Int = this.link.last<LoadMessage>(RemoteOpcode.Load).loadId,
        position: Long = 0,
        duration: Long = 60_000,
        status: Int = REMOTE_STATUS_PLAYING,
        speed: Float = 1f,
    ) = player.onMessage(
        RemoteOpcode.State,
        RemoteCastJson.encode(StateMessage(loadId, position, duration, status, speed))
    )

    private inline fun <reified T : PlayerEvent> eventsOf() = events.filterIsInstance<T>()

    // ----- Loading -----

    @Test
    fun loadSendsLinkSubtitlesAndStartPositionToTheTv() {
        val s1 = sub("en")
        val link = makeLink("a")
        load(link = link, start = 5_000, subtitles = setOf(s1), subtitle = s1, autoPlay = false)

        val message = this.link.last<LoadMessage>(RemoteOpcode.Load)
        assertEquals(link.url, message.link.url)
        assertEquals(mapOf("h" to "v"), message.link.headers)
        assertEquals(5_000L, message.startPositionMs)
        assertFalse(message.autoPlay)
        assertEquals(listOf(s1.getId()), message.subtitles.map { it.getId() })
        assertEquals(s1.getId(), message.preferredSubtitle?.getId())
        assertFalse(message.sameEpisode)
        assertTrue(player.isActive())
        // The UI gets something to attach its controls to, and shows loading
        assertTrue(eventsOf<PlayerAttachedEvent>().any { it.player === mirror })
        assertEquals(CSPlayerLoading.IsBuffering, eventsOf<StatusEvent>().last().isPlaying)
    }

    @Test
    fun embeddedSubtitlesAreNotSentBackToTheTv() {
        val embedded = sub("Embedded", SubtitleOrigin.EMBEDDED_IN_VIDEO, url = "track-1")
        val external = sub("External")
        load(subtitles = setOf(embedded, external))
        assertEquals(listOf(external.getId()), link.last<LoadMessage>(RemoteOpcode.Load).subtitles.map { it.getId() })
    }

    @Test
    fun everyLoadGetsANewIdSoStaleStateIsIgnored() {
        load()
        val first = link.last<LoadMessage>(RemoteOpcode.Load).loadId
        load(link = makeLink("b"))
        val second = link.last<LoadMessage>(RemoteOpcode.Load).loadId
        assertTrue(second > first)

        // The old video is still reporting for a moment, it must not touch the new one
        tvState(loadId = first, position = 50_000, status = REMOTE_STATUS_PLAYING)
        assertEquals(0L, player.getPosition())
        assertNull(player.getDuration())

        tvState(loadId = second, position = 1_000)
        assertEquals(1_000L, player.getPosition())
    }

    @Test
    fun switchingSourceKeepsThePositionWhenStartIsNotGiven() {
        load(start = 0)
        tvState(position = 120_000, duration = 600_000, status = REMOTE_STATUS_PAUSED)

        // GeneratorPlayer passes null as start for the same episode
        load(link = makeLink("mirror"), sameEpisode = true, start = null)

        val message = link.last<LoadMessage>(RemoteOpcode.Load)
        assertEquals(120_000L, message.startPositionMs)
        assertTrue(message.sameEpisode)
    }

    @Test
    fun newEpisodeStartsOverWhenStartIsNotGiven() {
        load(start = 0)
        tvState(position = 120_000, duration = 600_000)
        load(link = makeLink("ep2"), sameEpisode = false, start = null)
        assertEquals(0L, link.last<LoadMessage>(RemoteOpcode.Load).startPositionMs)
    }

    @Test
    fun reloadResendsTheCurrentLinkAtTheCurrentPosition() {
        load()
        tvState(position = 30_000, status = REMOTE_STATUS_PLAYING)
        val before = link.count(RemoteOpcode.Load)

        player.reloadPlayer(context)

        assertEquals(before + 1, link.count(RemoteOpcode.Load))
        val message = link.last<LoadMessage>(RemoteOpcode.Load)
        assertEquals(30_000L, message.startPositionMs)
        assertTrue(message.autoPlay)
        assertTrue(message.sameEpisode)
    }

    @Test
    fun reloadWithoutLoadedVideoDoesNothing() {
        player.reloadPlayer(context)
        assertEquals(0, link.count(RemoteOpcode.Load))
    }

    @Test
    fun unsupportedSourcesAreReportedSoThePlayerTriesTheNextOne() {
        load(link = makeLink("t", ExtractorLinkType.TORRENT))
        load(link = makeLink("m", ExtractorLinkType.MAGNET))

        assertEquals(2, eventsOf<ErrorEvent>().size)
        assertEquals(0, link.count(RemoteOpcode.Load))
        assertFalse(player.isActive())
    }

    // ----- State from the TV -----

    @Test
    fun tvStateDrivesPositionDurationAndEvents() {
        load()
        tvState(position = 10_000, duration = 90_000, status = REMOTE_STATUS_PLAYING)

        assertEquals(10_000L, player.getPosition())
        assertEquals(90_000L, player.getDuration())
        assertTrue(player.getIsPlaying())
        assertEquals(CSPlayerLoading.IsPlaying, eventsOf<StatusEvent>().last().isPlaying)

        val position = eventsOf<PositionEvent>().last()
        assertEquals(10_000L, position.toMs)
        assertEquals(90_000L, position.durationMs)
        assertTrue(mirror.refreshes > 0)
    }

    @Test
    fun positionIsExtrapolatedBetweenReportsWhilePlayingOnly() {
        load()
        tvState(position = 10_000, status = REMOTE_STATUS_PLAYING, speed = 2f)
        now += 500
        assertEquals(11_000L, player.getPosition())

        now += 1_500
        tvState(position = 14_000, status = REMOTE_STATUS_PAUSED)
        // Paused: does not move
        now += 10_000
        assertEquals(14_000L, player.getPosition())
    }

    @Test
    fun positionNeverPassesTheDuration() {
        load()
        tvState(position = 59_900, duration = 60_000, status = REMOTE_STATUS_PLAYING)
        now += 5_000
        assertEquals(60_000L, player.getPosition())
    }

    @Test
    fun noPositionEventWhileDurationIsUnknown() {
        load()
        tvState(position = 1_000, duration = -1, status = REMOTE_STATUS_BUFFERING)
        assertTrue(eventsOf<PositionEvent>().isEmpty())
        assertNull(player.getDuration())
    }

    @Test
    fun endingTheVideoFiresVideoEndedOnlyOnce() {
        load()
        tvState(position = 59_000, status = REMOTE_STATUS_PLAYING)
        tvState(position = 60_000, status = REMOTE_STATUS_ENDED)
        tvState(position = 60_000, status = REMOTE_STATUS_ENDED)
        assertEquals(1, eventsOf<VideoEndedEvent>().size)
    }

    @Test
    fun tvNextButtonBecomesAnEpisodeSeekEvent() {
        load()
        player.onMessage(RemoteOpcode.Navigate, RemoteCastJson.encode(NavigateMessage(1)))
        player.onMessage(RemoteOpcode.Navigate, RemoteCastJson.encode(NavigateMessage(-1)))
        assertEquals(listOf(1, -1), eventsOf<EpisodeSeekEvent>().map { it.offset })
    }

    @Test
    fun tracksAndEmbeddedSubtitlesAreForwardedToTheUi() {
        load()
        val id = link.last<LoadMessage>(RemoteOpcode.Load).loadId
        val audio = WireTrack("a1", "English", "en", "audio/aac", channelCount = 2, formatIndex = 0)
        player.onMessage(
            RemoteOpcode.Tracks,
            RemoteCastJson.encode(TracksMessage(id, currentAudio = audio, audio = listOf(audio)))
        )
        assertEquals("a1", player.getVideoTracks().currentAudioTrack?.id)
        assertEquals(1, eventsOf<TracksChangedEvent>().size)

        val embedded = sub("Embedded", SubtitleOrigin.EMBEDDED_IN_VIDEO, "track-1")
        player.onMessage(
            RemoteOpcode.EmbeddedSubtitles,
            RemoteCastJson.encode(EmbeddedSubtitlesMessage(id, listOf(embedded)))
        )
        assertEquals(listOf(embedded.getId()), eventsOf<EmbeddedSubtitlesFetchedEvent>().single().tracks.map { it.getId() })
    }

    @Test
    fun tvErrorsBecomeErrorEventsSoMirrorFallbackWorks() {
        load()
        val id = link.last<LoadMessage>(RemoteOpcode.Load).loadId
        player.onMessage(RemoteOpcode.Error, RemoteCastJson.encode(ErrorMessage(id, "403 forbidden")))
        assertEquals("403 forbidden", eventsOf<ErrorEvent>().single().error.message)

        // An error for a previous video is not the current video's problem
        player.onMessage(RemoteOpcode.Error, RemoteCastJson.encode(ErrorMessage(id - 1, "old")))
        assertEquals(1, eventsOf<ErrorEvent>().size)
    }

    @Test
    fun garbageFromTheTvIsIgnored() {
        load()
        player.onMessage(RemoteOpcode.State, "not json".toByteArray())
        player.onMessage(RemoteOpcode.Tracks, ByteArray(0))
        assertTrue(player.isActive())
    }

    // ----- Commands to the TV -----

    @Test
    fun pauseAndPlayAreSentAndShownImmediately() {
        load()
        tvState(position = 5_000, status = REMOTE_STATUS_PLAYING)

        player.handleEvent(CSPlayerEvent.Pause, PlayerEventSource.UI)
        assertEquals(RemoteOpcode.Pause, link.sent.last().first)
        assertFalse(player.getIsPlaying())
        assertEquals(1, eventsOf<PauseEvent>().size)
        assertEquals(CSPlayerLoading.IsPaused, eventsOf<StatusEvent>().last().isPlaying)

        player.handleEvent(CSPlayerEvent.Play, PlayerEventSource.UI)
        assertEquals(RemoteOpcode.Play, link.sent.last().first)
        assertEquals(1, eventsOf<PlayEvent>().size)
    }

    @Test
    fun staleStatusRightAfterPausingDoesNotFlipTheButtonBack() {
        load()
        tvState(position = 5_000, status = REMOTE_STATUS_PLAYING)
        player.handleEvent(CSPlayerEvent.Pause, PlayerEventSource.UI)

        // A report that was already on its way when we paused
        now += 200
        tvState(position = 5_300, status = REMOTE_STATUS_PLAYING)
        assertFalse(player.getIsPlaying())
        assertEquals(CSPlayerLoading.IsPaused, eventsOf<StatusEvent>().last().isPlaying)

        // After the guard time the TV is believed again
        now += 5_000
        tvState(position = 5_000, status = REMOTE_STATUS_PAUSED)
        assertFalse(player.getIsPlaying())
    }

    @Test
    fun togglePausesWhenPlayingAndPlaysWhenPaused() {
        load()
        tvState(status = REMOTE_STATUS_PLAYING)
        player.handleEvent(CSPlayerEvent.PlayPauseToggle)
        assertEquals(RemoteOpcode.Pause, link.sent.last().first)

        now += 5_000
        tvState(status = REMOTE_STATUS_PAUSED)
        player.handleEvent(CSPlayerEvent.PlayPauseToggle)
        assertEquals(RemoteOpcode.Play, link.sent.last().first)
    }

    @Test
    fun seekIsClampedSentAndShownImmediately() {
        load()
        tvState(position = 10_000, duration = 60_000, status = REMOTE_STATUS_PAUSED)

        player.seekTo(25_000)
        assertEquals(25_000L, link.last<SeekMessage>(RemoteOpcode.Seek).positionMs)
        assertEquals(25_000L, player.getPosition())

        player.seekTo(-5)
        assertEquals(0L, link.last<SeekMessage>(RemoteOpcode.Seek).positionMs)

        player.seekTo(10_000_000)
        assertEquals(60_000L, link.last<SeekMessage>(RemoteOpcode.Seek).positionMs)
    }

    @Test
    fun seekReportsPositionSoWatchProgressIsSaved() {
        load()
        tvState(position = 10_000, status = REMOTE_STATUS_PAUSED)
        events.clear()
        player.seekTo(40_000, PlayerEventSource.UI)
        val event = eventsOf<PositionEvent>().single()
        assertEquals(10_000L, event.fromMs)
        assertEquals(40_000L, event.toMs)
        assertEquals(PlayerEventSource.UI, event.source)
    }

    @Test
    fun positionReportedBeforeTheSeekReachedTheTvIsIgnored() {
        load()
        tvState(position = 10_000, status = REMOTE_STATUS_PAUSED)
        player.seekTo(40_000)

        now += 300
        tvState(position = 10_300, status = REMOTE_STATUS_PAUSED) // old, from before the seek
        assertEquals(40_000L, player.getPosition())

        now += 5_000
        tvState(position = 41_000, status = REMOTE_STATUS_PAUSED)
        assertEquals(41_000L, player.getPosition())
    }

    @Test
    fun seekButtonsMoveThirtySeconds() {
        load()
        tvState(position = 100_000, duration = 600_000, status = REMOTE_STATUS_PAUSED)
        player.handleEvent(CSPlayerEvent.SeekForward)
        assertEquals(130_000L, link.last<SeekMessage>(RemoteOpcode.Seek).positionMs)
        player.handleEvent(CSPlayerEvent.SeekBack)
        assertEquals(100_000L, link.last<SeekMessage>(RemoteOpcode.Seek).positionMs)
    }

    @Test
    fun commandsBeforeAnythingIsLoadedAreIgnored() {
        player.handleEvent(CSPlayerEvent.Play)
        player.handleEvent(CSPlayerEvent.Pause)
        player.seekTo(1_000)
        assertTrue(link.sent.isEmpty())
    }

    @Test
    fun nextAndPreviousEpisodeAreLeftToThePlayerUi() {
        load()
        player.handleEvent(CSPlayerEvent.NextEpisode)
        player.handleEvent(CSPlayerEvent.PrevEpisode)
        assertEquals(listOf(1, -1), eventsOf<EpisodeSeekEvent>().map { it.offset })
        assertEquals(1, link.count(RemoteOpcode.Load))
    }

    @Test
    fun speedIsSentAndKeepsThePositionContinuous() {
        load()
        tvState(position = 10_000, status = REMOTE_STATUS_PLAYING)
        now += 1_000
        player.setPlaybackSpeed(2f)
        assertEquals(2f, link.last<SetSpeedMessage>(RemoteOpcode.SetSpeed).speed)
        // 1s at 1x already happened, the new speed only applies from now
        assertEquals(11_000L, player.getPosition())
        now += 1_000
        assertEquals(13_000L, player.getPosition())
    }

    @Test
    fun skipChapterSeeksToTheEndOfTheStamp() {
        load()
        tvState(position = 10_000, duration = 600_000, status = REMOTE_STATUS_PLAYING)
        val stamp = VideoSkipStamp(SkipStamp(SkipType.Opening, 5_000, 90_000, null), false, "test")
        player.addTimeStamps(listOf(stamp))

        player.handleEvent(CSPlayerEvent.SkipCurrentChapter)

        assertEquals(90_001L, link.last<SeekMessage>(RemoteOpcode.Seek).positionMs)
    }

    // ----- Subtitles and tracks -----

    @Test
    fun choosingASubtitleSendsItAndNeverAsksThePhoneToReload() {
        val a = sub("a")
        val b = sub("b")
        load(subtitles = setOf(a, b), subtitle = a)

        player.setActiveSubtitles(setOf(a, b))
        val needsReload = player.setPreferredSubtitles(b)

        assertFalse(needsReload)
        val message = link.last<SetSubtitlesMessage>(RemoteOpcode.SetSubtitles)
        assertEquals(b.getId(), message.preferred?.getId())
        assertEquals(setOf(a.getId(), b.getId()), message.subtitles.map { it.getId() }.toSet())
        assertEquals(b.getId(), player.getCurrentPreferredSubtitle()?.getId())
    }

    @Test
    fun turningSubtitlesOffSendsNull() {
        val a = sub("a")
        load(subtitles = setOf(a), subtitle = a)
        player.setPreferredSubtitles(null)
        assertNull(link.last<SetSubtitlesMessage>(RemoteOpcode.SetSubtitles).preferred)
        assertNull(player.getCurrentPreferredSubtitle())
    }

    @Test
    fun theTvsOwnSubtitleChoiceIsReported() {
        val a = sub("a")
        val b = sub("b")
        load(subtitles = setOf(a, b), subtitle = a)
        val id = link.last<LoadMessage>(RemoteOpcode.Load).loadId
        player.onMessage(
            RemoteOpcode.Tracks,
            RemoteCastJson.encode(TracksMessage(id, selectedSubtitleId = b.getId()))
        )
        assertEquals(b.getId(), player.getCurrentPreferredSubtitle()?.getId())
    }

    @Test
    fun addingSubtitlesThenReloadingSendsThemInTheLoad() {
        val a = sub("a")
        load(subtitles = setOf(a), subtitle = a)
        val added = sub("added")
        player.setActiveSubtitles(setOf(a, added))
        player.reloadPlayer(context)
        val message = link.last<LoadMessage>(RemoteOpcode.Load)
        assertEquals(setOf(a.getId(), added.getId()), message.subtitles.map { it.getId() }.toSet())
    }

    @Test
    fun subtitleOffsetAndQualityAreForwarded() {
        load()
        player.setSubtitleOffset(-750)
        assertEquals(-750L, link.last<SetSubtitleOffsetMessage>(RemoteOpcode.SetSubtitleOffset).offsetMs)
        assertEquals(-750L, player.getSubtitleOffset())

        player.setMaxVideoSize(1280, 720, null)
        val size = link.last<SetMaxVideoSizeMessage>(RemoteOpcode.SetMaxVideoSize)
        assertEquals(1280, size.width)
        assertEquals(720, size.height)
        assertNull(size.id)
    }

    // ----- Lifecycle -----

    @Test
    fun phoneLifecycleDoesNotTouchTheTv() {
        load()
        link.clear()
        player.onPause()
        player.onStop()
        player.onResume(context)
        player.saveData()
        assertTrue(link.sent.isEmpty())
    }

    @Test
    fun releaseOnlyDetachesButLeavingThePlayerScreenStopsTheTv() {
        load()
        link.clear()
        player.release()
        assertEquals(0, link.count(RemoteOpcode.Stop))
        assertTrue(eventsOf<PlayerAttachedEvent>().last().player == null)

        player.releaseCallbacks()
        assertEquals(1, link.count(RemoteOpcode.Stop))
        assertTrue(link.listeners.isEmpty())
        assertTrue(mirror.released)
    }

    @Test
    fun releasingCallbacksBeforeLoadingDoesNotBreakTheControls() {
        // PlayerView does this when it is set up: release, then init, then load
        val made = mutableListOf<FakeMirror>()
        val p = RemoteIPlayer(link, { now }, { FakeMirror().also { made += it } }, { })
        val seen = mutableListOf<PlayerEvent>()
        p.releaseCallbacks()
        p.initCallbacks({ seen += it }, null)
        p.loadPlayer(context, false, makeLink(), null, 0, emptySet(), null, true, false)

        val attached = seen.filterIsInstance<PlayerAttachedEvent>().single().player as FakeMirror
        assertFalse("The player given to the UI must not be released", attached.released)

        // A real release later gives the next load a fresh one
        p.releaseCallbacks()
        assertTrue(attached.released)
        p.initCallbacks({ seen += it }, null)
        p.loadPlayer(context, false, makeLink(), null, 0, emptySet(), null, true, false)
        val next = seen.filterIsInstance<PlayerAttachedEvent>().last().player as FakeMirror
        assertFalse(next.released)
        assertTrue(next !== attached)
    }

    @Test
    fun theUiSubtitleRefreshSeekIsNotSentToTheTv() {
        load()
        tvState(position = 10_000, status = REMOTE_STATUS_PLAYING)
        player.seekTime(-1)
        player.seekTime(1)
        assertEquals(0, link.count(RemoteOpcode.Seek))
    }

    @Test
    fun noStopIsSentWhenNothingWasLoaded() {
        player.releaseCallbacks()
        assertEquals(0, link.count(RemoteOpcode.Stop))
    }

    @Test
    fun whenTheTvUserClosesThePlayerPlayLoadsTheVideoAgainWhereItWas() {
        load()
        tvState(position = 33_000, status = REMOTE_STATUS_PLAYING)
        player.onMessage(RemoteOpcode.Stopped, ByteArray(0))
        assertFalse(player.getIsPlaying())
        assertTrue(notifications.isNotEmpty())
        val loads = link.count(RemoteOpcode.Load)

        player.handleEvent(CSPlayerEvent.Play)

        assertEquals(loads + 1, link.count(RemoteOpcode.Load))
        val message = link.last<LoadMessage>(RemoteOpcode.Load)
        assertEquals(33_000L, message.startPositionMs)
        assertTrue(message.autoPlay)
        // No point in telling an already closed TV to stop
        player.releaseCallbacks()
        assertEquals(1, link.count(RemoteOpcode.Stop))
    }

    @Test
    fun noStopIsSentIfTheTvAlreadyClosed() {
        load()
        player.onMessage(RemoteOpcode.Stopped, ByteArray(0))
        player.releaseCallbacks()
        assertEquals(0, link.count(RemoteOpcode.Stop))
    }

    @Test
    fun losingTheConnectionPausesTheUiAndTellsTheUser() {
        load()
        tvState(status = REMOTE_STATUS_PLAYING)
        player.onStatusChanged(RemoteCastStatus.Reconnecting)
        player.onStatusChanged(RemoteCastStatus.Disconnected)
        assertEquals(2, notifications.size)
        assertEquals(CSPlayerLoading.IsPaused, eventsOf<StatusEvent>().last().isPlaying)
    }

    @Test
    fun playerReportsItselfAsListeningOnlyWhileCallbacksAreInit() {
        assertEquals(1, link.listeners.size)
        player.releaseCallbacks()
        assertTrue(link.listeners.isEmpty())
        assertNotNull(player.getVideoTracks())
        assertNull(player.getPreview(0.5f))
        assertFalse(player.hasPreview())
    }
}
