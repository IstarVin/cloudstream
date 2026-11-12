package com.lagradost.cloudstream3.cast.remote

import com.lagradost.cloudstream3.ui.player.AudioTrack
import com.lagradost.cloudstream3.ui.player.CurrentTracks
import com.lagradost.cloudstream3.ui.player.TextTrack
import com.lagradost.cloudstream3.ui.player.VideoTrack

/** Conversions between the existing player track classes and the wire format. */

fun VideoTrack.toWire() = WireTrack(
    id = id, label = label, language = language, mimeType = sampleMimeType,
    width = width, height = height,
)

fun AudioTrack.toWire() = WireTrack(
    id = id, label = label, language = language, mimeType = sampleMimeType,
    channelCount = channelCount, formatIndex = formatIndex,
)

fun TextTrack.toWire() = WireTrack(
    id = id, label = label, language = language, mimeType = sampleMimeType,
)

fun WireTrack.toVideoTrack() = VideoTrack(
    id = id, label = label, language = language,
    width = width, height = height, sampleMimeType = mimeType,
)

fun WireTrack.toAudioTrack() = AudioTrack(
    id = id, label = label, language = language, sampleMimeType = mimeType,
    channelCount = channelCount, formatIndex = formatIndex,
)

fun WireTrack.toTextTrack() = TextTrack(
    id = id, label = label, language = language, sampleMimeType = mimeType,
)

fun CurrentTracks.toWire(loadId: Int, selectedSubtitleId: String?) = TracksMessage(
    loadId = loadId,
    currentVideo = currentVideoTrack?.toWire(),
    currentAudio = currentAudioTrack?.toWire(),
    currentText = currentTextTracks.map { it.toWire() },
    video = allVideoTracks.map { it.toWire() },
    audio = allAudioTracks.map { it.toWire() },
    text = allTextTracks.map { it.toWire() },
    selectedSubtitleId = selectedSubtitleId,
)

fun TracksMessage.toCurrentTracks() = CurrentTracks(
    currentVideoTrack = currentVideo?.toVideoTrack(),
    currentAudioTrack = currentAudio?.toAudioTrack(),
    currentTextTracks = currentText.map { it.toTextTrack() },
    allVideoTracks = video.map { it.toVideoTrack() },
    allAudioTracks = audio.map { it.toAudioTrack() },
    allTextTracks = text.map { it.toTextTrack() },
)

val EMPTY_TRACKS = CurrentTracks(
    currentVideoTrack = null,
    currentAudioTrack = null,
    currentTextTracks = emptyList(),
    allVideoTracks = emptyList(),
    allAudioTracks = emptyList(),
    allTextTracks = emptyList(),
)
