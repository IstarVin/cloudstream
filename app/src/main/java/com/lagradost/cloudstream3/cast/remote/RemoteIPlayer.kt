package com.lagradost.cloudstream3.cast.remote

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Rational
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.ui.player.CS3IPlayer
import com.lagradost.cloudstream3.ui.player.CSPlayerEvent
import com.lagradost.cloudstream3.ui.player.CSPlayerLoading
import com.lagradost.cloudstream3.ui.player.CurrentTracks
import com.lagradost.cloudstream3.ui.player.EmbeddedSubtitlesFetchedEvent
import com.lagradost.cloudstream3.ui.player.EpisodeSeekEvent
import com.lagradost.cloudstream3.ui.player.ErrorEvent
import com.lagradost.cloudstream3.ui.player.ExtractorUri
import com.lagradost.cloudstream3.ui.player.IPlayer
import com.lagradost.cloudstream3.ui.player.PauseEvent
import com.lagradost.cloudstream3.ui.player.PlayEvent
import com.lagradost.cloudstream3.ui.player.PlayerAttachedEvent
import com.lagradost.cloudstream3.ui.player.PlayerEvent
import com.lagradost.cloudstream3.ui.player.PlayerEventSource
import com.lagradost.cloudstream3.ui.player.PositionEvent
import com.lagradost.cloudstream3.ui.player.ResizedEvent
import com.lagradost.cloudstream3.ui.player.StatusEvent
import com.lagradost.cloudstream3.ui.player.SubtitleCue
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import com.lagradost.cloudstream3.ui.player.SubtitlesUpdatedEvent
import com.lagradost.cloudstream3.ui.player.TimestampInvokedEvent
import com.lagradost.cloudstream3.ui.player.TimestampSkippedEvent
import com.lagradost.cloudstream3.ui.player.TracksChangedEvent
import com.lagradost.cloudstream3.ui.player.VideoEndedEvent
import com.lagradost.cloudstream3.ui.subtitles.SaveCaptionStyle
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkPlayList
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.videoskip.VideoSkipStamp

/**
 * An [IPlayer] that does not play anything itself, it remote controls the video player on a CloudStream TV.
 *
 * Everything is still resolved on the phone (links, subtitles, source switching), this only forwards the
 * result and the commands to the TV, and turns the TV's reported state back into the normal [PlayerEvent]s.
 * That is why the existing player UI works unchanged: it simply talks to a different [IPlayer].
 */
class RemoteIPlayer(
    private val remote: RemoteLink = RemoteCast,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    /** What the media3 controls are attached to, see [RemoteMedia3Player] */
    private val mirrorFactory: (RemoteIPlayer) -> RemoteMirror = { RemoteMedia3Player(it) },
    private val notify: (Int) -> Unit = { CommonActivity.showToast(it) },
) : IPlayer, RemoteCastListener {
    private var eventHandler: ((PlayerEvent) -> Unit)? = null

    /**
     * Used by the media3 PlayerView controls (seek bar, media session).
     * Made when needed and made again after a release, a released player can not be attached again.
     */
    private var mirror: RemoteMirror? = null
    private val proxy: RemoteMirror
        get() = mirror ?: mirrorFactory(this).also { mirror = it }

    // What we asked the TV to play
    private var loadId = 0
    private var currentLink: ExtractorLink? = null
    private var allSubtitles: Set<SubtitleData> = emptySet()
    private var preferredSubtitle: SubtitleData? = null
    private var subtitleStyle: SaveCaptionStyle? = null
    private var subtitleOffsetMs = 0L
    private var speed = 1.0f
    private var timeStamps: List<VideoSkipStamp> = emptyList()

    // What the TV reported
    private var remoteStatus = REMOTE_STATUS_IDLE
    private var remotePositionMs = 0L
    private var remoteDurationMs = -1L
    private var remoteStateTime = 0L
    private var tracks: CurrentTracks = EMPTY_TRACKS
    private var selectedSubtitleId: String? = null
    private var aspect: Rational? = null
    private var lastStatus = CSPlayerLoading.IsBuffering

    /** If the user wants playback to run, used to answer immediately before the TV confirms */
    private var wantsPlay = true

    /** The TV user closed the player, so the next play has to load the video again */
    private var tvStopped = false

    /** Ignore position reports older than a seek we just sent */
    private var seekGuardUntil = 0L

    /** Ignore status reports older than a play/pause we just sent, or the button would flicker */
    private var statusGuardUntil = 0L

    // ----- Events -----

    private fun event(event: PlayerEvent) {
        eventHandler?.invoke(event)
    }

    override fun initCallbacks(
        eventHandler: (PlayerEvent) -> Unit,
        requestedListeningPercentages: List<Int>?,
    ) {
        this.eventHandler = eventHandler
        remote.addListener(this)
    }

    override fun releaseCallbacks() {
        eventHandler = null
        remote.removeListener(this)
        // Leaving the player screen ends the cast, so the TV is not left playing on its own
        if (currentLink != null && !tvStopped) {
            remote.send(RemoteOpcode.Stop)
        }
        currentLink = null
        // PlayerView also calls this when it is set up, before anything was created
        mirror?.release()
        mirror = null
    }

    // ----- Messages from the TV -----

    override fun onStatusChanged(status: RemoteCastStatus) {
        when (status) {
            RemoteCastStatus.Reconnecting -> notify(R.string.remote_cast_reconnecting)

            RemoteCastStatus.Disconnected -> {
                notify(R.string.remote_cast_disconnected)
                wantsPlay = false
                setStatus(CSPlayerLoading.IsPaused)
                proxy.refresh()
            }

            else -> Unit
        }
    }

    override fun onMessage(opcode: RemoteOpcode, body: ByteArray) {
        try {
            when (opcode) {
                RemoteOpcode.State -> onState(RemoteCastJson.decode(body))
                RemoteOpcode.Tracks -> onTracks(RemoteCastJson.decode(body))
                RemoteOpcode.EmbeddedSubtitles -> onEmbeddedSubtitles(RemoteCastJson.decode(body))
                RemoteOpcode.Error -> onError(RemoteCastJson.decode(body))
                RemoteOpcode.Navigate -> {
                    val offset = RemoteCastJson.decode<NavigateMessage>(body).offset
                    if (offset != 0) event(EpisodeSeekEvent(offset.coerceIn(-1, 1), PlayerEventSource.UI))
                }

                RemoteOpcode.Stopped -> onTvStopped()
                else -> Unit
            }
        } catch (t: Throwable) {
            // A malformed message from the TV must not crash the phone
            t.printStackTrace()
        }
    }

    private fun onState(m: StateMessage) {
        if (m.loadId != loadId || currentLink == null) return
        tvStopped = false

        val previousPosition = getPosition() ?: 0L
        val now = clock()
        if (now >= seekGuardUntil) {
            remotePositionMs = m.positionMs
            remoteStateTime = now
        }
        remoteDurationMs = m.durationMs
        speed = m.speed
        subtitleOffsetMs = m.subtitleOffsetMs

        if (m.width > 0 && m.height > 0) {
            val newAspect = Rational(m.width, m.height)
            if (newAspect != aspect) {
                aspect = newAspect
                event(ResizedEvent(height = m.height, width = m.width))
            }
        }

        var ended = false
        if (now >= statusGuardUntil) {
            remoteStatus = m.status
            // The TV is the source of truth for whether it is playing
            wantsPlay = m.status == REMOTE_STATUS_PLAYING ||
                    m.status == REMOTE_STATUS_BUFFERING && wantsPlay

            val newStatus = when (m.status) {
                REMOTE_STATUS_PLAYING -> CSPlayerLoading.IsPlaying
                REMOTE_STATUS_PAUSED -> CSPlayerLoading.IsPaused
                REMOTE_STATUS_ENDED -> CSPlayerLoading.IsEnded
                else -> CSPlayerLoading.IsBuffering
            }
            ended = newStatus == CSPlayerLoading.IsEnded && lastStatus != CSPlayerLoading.IsEnded
            setStatus(newStatus)
        }

        reportPosition(previousPosition, getPosition() ?: 0L, PlayerEventSource.Player)
        if (ended) event(VideoEndedEvent())
        proxy.refresh()
    }

    private fun onTracks(m: TracksMessage) {
        if (m.loadId != loadId) return
        tracks = m.toCurrentTracks()
        selectedSubtitleId = m.selectedSubtitleId
        event(TracksChangedEvent())
        event(SubtitlesUpdatedEvent())
    }

    private fun onEmbeddedSubtitles(m: EmbeddedSubtitlesMessage) {
        if (m.loadId != loadId) return
        event(EmbeddedSubtitlesFetchedEvent(tracks = m.tracks))
    }

    private fun onError(m: ErrorMessage) {
        if (m.loadId != loadId) return
        event(ErrorEvent(ErrorLoadingException(m.message)))
    }

    private fun onTvStopped() {
        tvStopped = true
        wantsPlay = false
        remoteStatus = REMOTE_STATUS_IDLE
        setStatus(CSPlayerLoading.IsPaused)
        notify(R.string.remote_cast_tv_stopped)
        proxy.refresh()
    }

    private fun setStatus(newStatus: CSPlayerLoading) {
        if (newStatus == lastStatus) return
        val old = lastStatus
        lastStatus = newStatus
        event(StatusEvent(old, newStatus))
    }

    private fun reportPosition(from: Long, to: Long, source: PlayerEventSource) {
        getCurrentTimestamp(to)?.let { event(TimestampInvokedEvent(it, source)) }
        val duration = remoteDurationMs
        if (duration > 0) {
            event(PositionEvent(source, fromMs = from, toMs = to, durationMs = duration))
        }
    }

    private fun getCurrentTimestamp(position: Long): VideoSkipStamp? {
        for (stamp in timeStamps) {
            if (stamp.timestamp.startMs <= position && position + 301 < stamp.timestamp.endMs) {
                return stamp
            }
        }
        return null
    }

    // ----- Used by RemoteMedia3Player -----

    fun snapshot() = RemoteMedia3Player.Snapshot(
        hasMedia = currentLink != null,
        title = currentLink?.name,
        playing = wantsPlay && remoteStatus != REMOTE_STATUS_ENDED,
        status = remoteStatus,
        positionMs = getPosition() ?: 0L,
        durationMs = remoteDurationMs,
        speed = speed,
    )

    fun onProxyPlayWhenReady(playWhenReady: Boolean) {
        handleEvent(if (playWhenReady) CSPlayerEvent.Play else CSPlayerEvent.Pause, PlayerEventSource.UI)
    }

    fun onProxySeek(positionMs: Long) = seekTo(positionMs, PlayerEventSource.UI)

    fun onProxySpeed(newSpeed: Float) = setPlaybackSpeed(newSpeed)

    fun onProxyStop() = handleEvent(CSPlayerEvent.Pause, PlayerEventSource.UI)

    // ----- Playback state -----

    override fun getPlaybackSpeed(): Float = speed

    override fun setPlaybackSpeed(speed: Float) {
        // Anchor the position first, or the extrapolation would jump with the new speed
        remotePositionMs = getPosition() ?: 0L
        remoteStateTime = clock()
        this.speed = speed
        remote.send(RemoteOpcode.SetSpeed, SetSpeedMessage(speed))
        proxy.refresh()
    }

    override fun getIsPlaying(): Boolean = remoteStatus == REMOTE_STATUS_PLAYING ||
            (wantsPlay && remoteStatus == REMOTE_STATUS_BUFFERING)

    override fun getDuration(): Long? = remoteDurationMs.takeIf { it > 0 }

    /** The last reported position plus the time since, as the TV only reports about once a second */
    override fun getPosition(): Long? {
        if (currentLink == null) return null
        var position = remotePositionMs
        if (remoteStatus == REMOTE_STATUS_PLAYING) {
            val elapsed = clock() - remoteStateTime
            position += (elapsed * speed).toLong()
        }
        if (remoteDurationMs > 0) position = position.coerceAtMost(remoteDurationMs)
        return position.coerceAtLeast(0)
    }

    override fun seekTime(time: Long, source: PlayerEventSource) {
        // The UI seeks by +-1ms to refresh subtitles, the TV renders those so there is nothing to refresh here
        if (time in -1L..1L) return
        seekTo((getPosition() ?: 0L) + time, source)
    }

    override fun seekTo(time: Long, source: PlayerEventSource) {
        if (currentLink == null) return
        var target = time.coerceAtLeast(0)
        if (remoteDurationMs > 0) target = target.coerceAtMost(remoteDurationMs)

        val from = getPosition() ?: 0L
        remotePositionMs = target
        remoteStateTime = clock()
        seekGuardUntil = remoteStateTime + SEEK_GUARD_MS
        remote.send(RemoteOpcode.Seek, SeekMessage(target))
        reportPosition(from, target, source)
        proxy.refresh()
    }

    override fun handleEvent(event: CSPlayerEvent, source: PlayerEventSource) {
        when (event) {
            CSPlayerEvent.Play -> play(source)
            CSPlayerEvent.Pause -> pause(source)
            CSPlayerEvent.PlayPauseToggle -> {
                if (getIsPlaying()) pause(source) else play(source)
            }

            CSPlayerEvent.SeekForward -> seekTime(SEEK_ACTION_MS, source)
            CSPlayerEvent.SeekBack -> seekTime(-SEEK_ACTION_MS, source)
            CSPlayerEvent.Restart -> seekTo(0, source)
            CSPlayerEvent.NextEpisode -> event(EpisodeSeekEvent(1, source))
            CSPlayerEvent.PrevEpisode -> event(EpisodeSeekEvent(-1, source))
            CSPlayerEvent.SkipCurrentChapter -> {
                getCurrentTimestamp(getPosition() ?: return)?.let { stamp ->
                    if (stamp.skipToNextEpisode) {
                        handleEvent(CSPlayerEvent.NextEpisode, source)
                    } else {
                        seekTo(stamp.timestamp.endMs + 1L, source)
                    }
                    event(TimestampSkippedEvent(timestamp = stamp, source = source))
                }
            }

            // The TV volume is controlled with the TV, and "play as audio" makes no sense here
            CSPlayerEvent.ToggleMute, CSPlayerEvent.PlayAsAudio -> Unit
        }
    }

    private fun play(source: PlayerEventSource) {
        if (currentLink == null) return
        if (tvStopped) {
            // The TV closed its player, so load the video again where we left off
            sendLoad(sameEpisode = true, autoPlay = true)
            return
        }
        // Anchor the position, or the paused position would be extrapolated from an old time
        remotePositionMs = getPosition() ?: 0L
        remoteStateTime = clock()
        wantsPlay = true
        statusGuardUntil = remoteStateTime + STATUS_GUARD_MS
        remote.send(RemoteOpcode.Play)
        event(PlayEvent(source))
        proxy.refresh()
    }

    private fun pause(source: PlayerEventSource) {
        if (currentLink == null) return
        remotePositionMs = getPosition() ?: 0L
        remoteStateTime = clock()
        wantsPlay = false
        statusGuardUntil = remoteStateTime + STATUS_GUARD_MS
        // Stop extrapolating immediately instead of waiting for the TV to confirm
        if (remoteStatus == REMOTE_STATUS_PLAYING) remoteStatus = REMOTE_STATUS_PAUSED
        remote.send(RemoteOpcode.Pause)
        event(PauseEvent(source))
        setStatus(CSPlayerLoading.IsPaused)
        proxy.refresh()
    }

    // ----- Loading -----

    override fun loadPlayer(
        context: Context,
        sameEpisode: Boolean,
        link: ExtractorLink?,
        data: ExtractorUri?,
        startPosition: Long?,
        subtitles: Set<SubtitleData>,
        subtitle: SubtitleData?,
        autoPlay: Boolean?,
        preview: Boolean,
    ) {
        val unsupported = when {
            data != null || link == null -> R.string.remote_cast_no_local_files
            link is DrmExtractorLink || link is ExtractorLinkPlayList -> R.string.remote_cast_unsupported_link
            link.type == ExtractorLinkType.TORRENT || link.type == ExtractorLinkType.MAGNET ->
                R.string.remote_cast_unsupported_link

            else -> null
        }
        if (unsupported != null) {
            // The player will then move on to the next source, like it would for any other failure
            event(PlayerAttachedEvent(proxy))
            event(ErrorEvent(ErrorLoadingException(context.getString(unsupported))))
            return
        }
        val sourceLink = link ?: return

        val position = startPosition ?: if (sameEpisode) getPosition() ?: 0L else 0L

        if (!sameEpisode) {
            tracks = EMPTY_TRACKS
            selectedSubtitleId = null
            remoteDurationMs = -1
        }
        currentLink = sourceLink
        allSubtitles = subtitles
        preferredSubtitle = subtitle
        aspect = null

        remotePositionMs = position
        remoteStateTime = clock()
        remoteStatus = REMOTE_STATUS_BUFFERING
        wantsPlay = autoPlay ?: wantsPlay

        sendLoad(sameEpisode, wantsPlay)
        event(PlayerAttachedEvent(proxy))
        // Always sent, like the local player, so the UI shows that something is loading
        event(StatusEvent(lastStatus, CSPlayerLoading.IsBuffering))
        lastStatus = CSPlayerLoading.IsBuffering
    }

    private fun sendLoad(sameEpisode: Boolean, autoPlay: Boolean) {
        val link = currentLink ?: return
        tvStopped = false
        loadId++
        val position = getPosition() ?: 0L

        remotePositionMs = position
        remoteStateTime = clock()
        remoteStatus = REMOTE_STATUS_BUFFERING
        wantsPlay = autoPlay

        remote.send(
            RemoteOpcode.Load,
            LoadMessage(
                loadId = loadId,
                link = link,
                subtitles = allSubtitles.filter { it.origin != SubtitleOrigin.EMBEDDED_IN_VIDEO },
                preferredSubtitle = preferredSubtitle,
                startPositionMs = position,
                autoPlay = autoPlay,
                sameEpisode = sameEpisode,
                speed = speed,
                subtitleOffsetMs = subtitleOffsetMs,
                subtitleStyle = subtitleStyle,
                title = link.name,
            )
        )
        proxy.refresh()
    }

    override fun reloadPlayer(context: Context) {
        if (currentLink == null) return
        sendLoad(sameEpisode = true, autoPlay = wantsPlay)
    }

    override fun saveData() = Unit

    // ----- Lifecycle, the TV keeps playing while the phone is locked or in the background -----

    override fun onStop() = Unit
    override fun onPause() = Unit
    override fun onResume(context: Context) = Unit

    override fun release() {
        // Called when switching source/episode too, so the TV is only told to stop in releaseCallbacks
        event(PlayerAttachedEvent(null))
    }

    override fun isActive(): Boolean = currentLink != null

    // ----- Subtitles -----

    override fun getSubtitleOffset(): Long = subtitleOffsetMs

    override fun setSubtitleOffset(offset: Long) {
        subtitleOffsetMs = offset
        remote.send(RemoteOpcode.SetSubtitleOffset, SetSubtitleOffsetMessage(offset))
    }

    override fun updateSubtitleStyle(style: SaveCaptionStyle) {
        // A font file on the phone does not exist on the TV
        val sanitized = style.copy(typefaceFilePath = null)
        if (sanitized == subtitleStyle) return
        subtitleStyle = sanitized
        if (currentLink != null) {
            remote.send(RemoteOpcode.SetSubtitleStyle, SetSubtitleStyleMessage(sanitized))
        }
    }

    override fun addTimeStamps(timeStamps: List<VideoSkipStamp>) {
        this.timeStamps = timeStamps
        val position = getPosition() ?: return
        reportPosition(position, position, PlayerEventSource.Player)
    }

    override fun setActiveSubtitles(subtitles: Set<SubtitleData>) {
        allSubtitles = subtitles
    }

    /**
     * Always false: the TV rebuilds its player itself if the subtitle is not part of the loaded media,
     * so the phone never has to reload.
     */
    override fun setPreferredSubtitles(subtitle: SubtitleData?): Boolean {
        preferredSubtitle = subtitle
        selectedSubtitleId = null
        if (currentLink != null) {
            remote.send(
                RemoteOpcode.SetSubtitles,
                SetSubtitlesMessage(
                    subtitles = allSubtitles.filter { it.origin != SubtitleOrigin.EMBEDDED_IN_VIDEO },
                    preferred = subtitle,
                )
            )
        }
        return false
    }

    override fun getCurrentPreferredSubtitle(): SubtitleData? {
        selectedSubtitleId?.let { id ->
            allSubtitles.firstOrNull { it.getId() == id }?.let { return it }
        }
        return preferredSubtitle
    }

    override fun getSubtitleCues(): List<SubtitleCue> = emptyList()

    // ----- Tracks -----

    override fun getVideoTracks(): CurrentTracks = tracks

    override fun getAspectRatio(): Rational? = aspect

    override fun setMaxVideoSize(width: Int, height: Int, id: String?) {
        remote.send(RemoteOpcode.SetMaxVideoSize, SetMaxVideoSizeMessage(width, height, id))
    }

    override fun setPreferredAudioTrack(trackLanguage: String?, id: String?, formatIndex: Int?) {
        // Same persistent setting the local player uses
        CS3IPlayer.preferredAudioTrackLanguage = trackLanguage
        remote.send(
            RemoteOpcode.SetAudioTrack,
            SetAudioTrackMessage(trackLanguage, id, formatIndex)
        )
    }

    // ----- No local video, so no previews -----

    override fun getPreview(fraction: Float): Bitmap? = null

    override fun hasPreview(): Boolean = false

    companion object {
        private const val SEEK_ACTION_MS = 30_000L
        private const val SEEK_GUARD_MS = 1_500L
        private const val STATUS_GUARD_MS = 1_200L
    }
}
