package com.lagradost.cloudstream3.cast.remote

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.FragmentPlayerBinding
import com.lagradost.cloudstream3.ui.player.CSPlayerEvent
import com.lagradost.cloudstream3.ui.player.CSPlayerLoading
import com.lagradost.cloudstream3.ui.player.FullScreenPlayer
import com.lagradost.cloudstream3.ui.player.PlayerEventSource
import com.lagradost.cloudstream3.ui.player.SubtitleData

/**
 * The player shown on the TV while it is being controlled by a phone.
 *
 * It is the normal [FullScreenPlayer] UI (so the TV remote still works), but instead of
 * resolving links it only plays what the phone sends, and reports its state back to the phone.
 */
class RemoteCastReceiverFragment : FullScreenPlayer(), RemoteCastReceiverHost {
    private val handler = Handler(Looper.getMainLooper())

    private var loadId = 0

    /** If true the phone closed the player, so it does not need to be told about it */
    var stoppedByController = false
        private set

    private val stateRunnable = object : Runnable {
        override fun run() {
            sendState()
            handler.postDelayed(this, STATE_INTERVAL_MS)
        }
    }

    override fun onBindingCreated(binding: FragmentPlayerBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)
        RemoteCastReceiver.attachHost(this)?.let { load(it) }
        handler.postDelayed(stateRunnable, STATE_INTERVAL_MS)
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        RemoteCastReceiver.detachHost(this, notifyController = !stoppedByController)
        super.onDestroyView()
    }

    // ----- Phone -> TV -----

    override fun load(message: LoadMessage) {
        val ctx = context ?: return
        loadId = message.loadId

        val subtitles = message.subtitles.toSet()
        player.setPlaybackSpeed(message.speed)
        message.subtitleStyle?.let { player.updateSubtitleStyle(it) }
        player.setSubtitleOffset(message.subtitleOffsetMs)
        player.setActiveSubtitles(subtitles)
        // When the source changes but not the episode the player keeps its subtitle choice,
        // so the one from the phone is set before loading
        if (message.sameEpisode) player.setPreferredSubtitles(message.preferredSubtitle)

        playerBinding?.playerVideoTitle?.text = message.title ?: message.link.name

        player.loadPlayer(
            context = ctx,
            sameEpisode = message.sameEpisode,
            link = message.link,
            data = null,
            startPosition = message.startPositionMs,
            subtitles = subtitles,
            subtitle = message.preferredSubtitle,
            autoPlay = message.autoPlay,
            preview = false,
        )
    }

    override fun play() {
        player.handleEvent(CSPlayerEvent.Play, PlayerEventSource.Sync)
    }

    override fun pause() {
        player.handleEvent(CSPlayerEvent.Pause, PlayerEventSource.Sync)
    }

    override fun seek(positionMs: Long) {
        player.seekTo(positionMs, PlayerEventSource.Sync)
    }

    override fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
    }

    override fun setSubtitles(message: SetSubtitlesMessage) {
        val ctx = context ?: return
        val wasPlaying = player.getIsPlaying()
        player.setActiveSubtitles(message.subtitles.toSet())
        if (player.setPreferredSubtitles(message.preferred)) {
            // The subtitle is not part of the loaded media, so the player has to be rebuilt
            player.saveData()
            player.reloadPlayer(ctx)
            if (wasPlaying) player.handleEvent(CSPlayerEvent.Play, PlayerEventSource.Sync)
        }
    }

    override fun setSubtitleStyle(message: SetSubtitleStyleMessage) {
        player.updateSubtitleStyle(message.style)
        // Subtitles are pre-processed, seeking refreshes them
        player.seekTime(-1)
    }

    override fun setSubtitleOffset(offsetMs: Long) {
        player.setSubtitleOffset(offsetMs)
    }

    override fun setAudioTrack(message: SetAudioTrackMessage) {
        player.setPreferredAudioTrack(message.language, message.id, message.formatIndex)
    }

    override fun setMaxVideoSize(message: SetMaxVideoSizeMessage) {
        player.setMaxVideoSize(message.width, message.height, message.id)
    }

    override fun stop() {
        stoppedByController = true
        activity?.finish()
    }

    // ----- TV -> Phone -----

    private fun status(): Int {
        if (!player.isActive()) return REMOTE_STATUS_IDLE
        return when (currentPlayerStatus) {
            CSPlayerLoading.IsPlaying -> REMOTE_STATUS_PLAYING
            CSPlayerLoading.IsPaused -> REMOTE_STATUS_PAUSED
            CSPlayerLoading.IsBuffering -> REMOTE_STATUS_BUFFERING
            CSPlayerLoading.IsEnded -> REMOTE_STATUS_ENDED
        }
    }

    private fun sendState() {
        val aspect = player.getAspectRatio()
        RemoteCastReceiver.sendState(
            StateMessage(
                loadId = loadId,
                positionMs = player.getPosition() ?: 0L,
                durationMs = player.getDuration()?.takeIf { it > 0 } ?: -1L,
                status = status(),
                speed = player.getPlaybackSpeed(),
                width = aspect?.numerator ?: 0,
                height = aspect?.denominator ?: 0,
                subtitleOffsetMs = player.getSubtitleOffset(),
            )
        )
    }

    private fun sendTracks() {
        val selected = player.getCurrentPreferredSubtitle()?.getId()
        RemoteCastReceiver.sendTracks(player.getVideoTracks().toWire(loadId, selected))
    }

    override fun playerStatusChanged() {
        super.playerStatusChanged()
        sendState()
    }

    override fun onTracksInfoChanged() {
        sendTracks()
    }

    override fun subtitlesChanged() {
        super.subtitlesChanged()
        sendTracks()
    }

    override fun embeddedSubtitlesFetched(subtitles: List<SubtitleData>) {
        RemoteCastReceiver.sendEmbeddedSubtitles(EmbeddedSubtitlesMessage(loadId, subtitles))
    }

    override fun playerError(exception: Throwable) {
        super.playerError(exception)
        RemoteCastReceiver.sendError(
            ErrorMessage(loadId, exception.message ?: exception::class.java.simpleName)
        )
    }

    override fun nextEpisode() {
        RemoteCastReceiver.sendNavigate(1)
    }

    override fun prevEpisode() {
        RemoteCastReceiver.sendNavigate(-1)
    }

    // Sources, tracks and subtitles are all picked on the phone, the base class would throw for these
    private fun changeOnPhone() = showToast(R.string.remote_cast_change_on_phone)

    override fun showMirrorsDialogue() = changeOnPhone()
    override fun showTracksDialogue() = changeOnPhone()
    override fun showEpisodesOverlay() = changeOnPhone()
    override fun openOnlineSubPicker(
        context: Context,
        loadResponse: LoadResponse?,
        dismissCallback: () -> Unit
    ) = changeOnPhone()

    // The phone picks the source, so there is nothing to fall back to here
    override fun hasNextMirror(): Boolean = false
    override fun nextMirror() = Unit
    override fun playerPositionChanged(position: Long, duration: Long) = Unit

    companion object {
        private const val STATE_INTERVAL_MS = 1000L
    }
}
