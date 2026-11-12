package com.lagradost.cloudstream3.cast.remote

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** What [RemoteIPlayer] tells the media3 side about, split out so it can be replaced in tests */
interface RemoteMirror {
    fun refresh()
    fun release()
}

/**
 * A media3 [Player] that does not play anything, it only mirrors what the TV is doing.
 *
 * The existing player UI (seek bar, media session, notification) is built around media3's PlayerView
 * and expects a media3 [Player] to be attached to it. By attaching this one, all of that keeps
 * working unchanged, and the commands the UI sends end up at [RemoteIPlayer] which forwards them to the TV.
 */
@UnstableApi
class RemoteMedia3Player(private val owner: RemoteIPlayer) : SimpleBasePlayer(Looper.getMainLooper()), RemoteMirror {

    /** Everything needed to describe the current state, read from [RemoteIPlayer] */
    data class Snapshot(
        val hasMedia: Boolean,
        val title: String?,
        /** If the user wants it to play, also true while buffering */
        val playing: Boolean,
        /** One of the REMOTE_STATUS_* constants */
        val status: Int,
        val positionMs: Long,
        val durationMs: Long,
        val speed: Float,
    )

    /** Call after the remote state changed, must be on the main thread */
    override fun refresh() {
        invalidateState()
    }

    override fun getState(): State {
        val s = owner.snapshot()

        val commands = Player.Commands.Builder().addAll(
            COMMAND_PLAY_PAUSE,
            COMMAND_STOP,
            COMMAND_GET_CURRENT_MEDIA_ITEM,
            COMMAND_GET_TIMELINE,
            COMMAND_GET_METADATA,
            COMMAND_SET_SPEED_AND_PITCH,
            COMMAND_RELEASE,
        ).also {
            if (s.hasMedia && s.durationMs > 0) {
                it.addAll(
                    COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    COMMAND_SEEK_BACK,
                    COMMAND_SEEK_FORWARD,
                )
            }
        }.build()

        val builder = State.Builder()
            .setAvailableCommands(commands)
            .setPlaybackParameters(PlaybackParameters(s.speed))

        if (!s.hasMedia) {
            return builder
                .setPlaybackState(STATE_IDLE)
                .build()
        }

        val item = MediaItemData.Builder(/* uid= */ "remote")
            .setMediaItem(
                MediaItem.Builder()
                    .setMediaId("remote")
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(s.title).build())
                    .build()
            )
            .setDurationUs(if (s.durationMs > 0) s.durationMs * 1000 else C.TIME_UNSET)
            .setIsSeekable(s.durationMs > 0)
            .build()

        // Asked every time the controls update, [RemoteIPlayer] extrapolates and never passes the duration
        val position = PositionSupplier { owner.getPosition() ?: 0L }

        return builder
            .setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .setPlayWhenReady(s.playing, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(
                when (s.status) {
                    REMOTE_STATUS_BUFFERING -> STATE_BUFFERING
                    REMOTE_STATUS_ENDED -> STATE_ENDED
                    REMOTE_STATUS_IDLE -> STATE_IDLE
                    else -> STATE_READY
                }
            )
            .setContentPositionMs(position)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        owner.onProxyPlayWhenReady(playWhenReady)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> {
        owner.onProxySeek(if (positionMs == C.TIME_UNSET) 0L else positionMs)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        owner.onProxySpeed(playbackParameters.speed)
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        owner.onProxyStop()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
}
