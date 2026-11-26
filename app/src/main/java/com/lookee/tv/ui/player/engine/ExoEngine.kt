package com.lookee.tv.ui.player.engine

import android.content.Context
import android.graphics.Color as AndroidColor
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.lookee.tv.R
import com.lookee.tv.ui.player.codecName
import com.lookee.tv.ui.player.describe
import com.lookee.tv.ui.player.summary
import com.lookee.tv.ui.player.trackChoices
import com.lookee.tv.ui.player.videoFrameRate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * ExoPlayer: hardware decoding for the formats the box's MediaCodec handles. When a file
 * has no audio this box can decode, or ExoPlayer can't read it, it asks for VLC instead
 * ([EngineState.fallback]) rather than showing an error.
 */
@OptIn(UnstableApi::class)
class ExoEngine(context: Context, private val log: (String, Boolean) -> Unit) : PlaybackEngine {
    override val name = "exo"
    override val canResizeSubtitles = true

    private val _state = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private var label = ""
    private var described = false
    private var subtitleFraction = SubtitleSize.Medium.fraction
    private var view: PlayerView? = null
    override val videoView: android.view.View? get() = view

    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setRenderersFactory(
            object : DefaultRenderersFactory(context) {
                // No passthrough: on the Mi Box, sending AC3 straight to HDMI failed when the
                // audio output started ("AudioTrack init failed"). The box's own Dolby decoder
                // (OMX.amlogic) turns it into PCM instead.
                @Suppress("DEPRECATION")
                override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                    DefaultAudioSink.Builder(context)
                        .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .build()
            }.setEnableDecoderFallback(true),
        )
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .build()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = _state.update { it.copy(playing = isPlaying) }

        override fun onPlaybackStateChanged(state: Int) = _state.update {
            it.copy(buffering = state == Player.STATE_BUFFERING, ended = state == Player.STATE_ENDED)
        }

        override fun onTracksChanged(tracks: Tracks) {
            _state.update {
                it.copy(
                    audio = tracks.trackChoices(C.TRACK_TYPE_AUDIO),
                    text = tracks.trackChoices(C.TRACK_TYPE_TEXT),
                    summary = tracks.summary(),
                    frameRate = tracks.videoFrameRate() ?: it.frameRate,
                )
            }
            if (tracks.isEmpty || described) return
            described = true
            log("tracks $label: ${tracks.describe()}", false)
            val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            if (audio.isNotEmpty() && audio.none { it.isSupported }) {
                val codec = codecName(audio[0].getTrackFormat(0).sampleMimeType) ?: "this"
                _state.update { it.copy(fallback = "no playable audio ($codec)") }
            }
            val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            if (video.isNotEmpty() && video.none { it.isSupported }) {
                val codec = codecName(video[0].getTrackFormat(0).sampleMimeType) ?: "this"
                _state.update { it.copy(fallback = "no hardware decoder for $codec video") }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val detail = "${error.errorCodeName} ${error.cause?.message.orEmpty()}".trim()
            log("exo error $label: $detail", true)
            _state.update {
                when (error.errorCode) {
                    // A missing file or a dead stream isn't something VLC can fix.
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
                        it.copy(error = "The file isn't there anymore. Is the drive connected?", buffering = false)
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                        it.copy(error = "The stream stopped responding.", buffering = false)
                    else -> it.copy(fallback = error.errorCodeName, buffering = false)
                }
            }
        }
    }

    init {
        player.addListener(listener)
    }

    override fun load(source: Source, startMs: Long) {
        label = source.label
        described = false
        _state.value = EngineState()
        val media = MediaItem.Builder()
            .setUri(source.uri)
            .build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setPreferredTextLanguages(*listOfNotNull(source.preferredText).toTypedArray())
            .build()
        player.setMediaItem(media, startMs)
        player.prepare()
        player.playWhenReady = true
    }

    /** Position for the bar; ExoPlayer has no position events. Called by the screen's ticker. */
    override fun poll() = _state.update {
        it.copy(
            positionMs = player.currentPosition,
            durationMs = player.duration.takeIf { d -> d != C.TIME_UNSET } ?: 0,
            bufferedMs = player.bufferedPosition,
        )
    }

    override fun positionNow(): Long = player.currentPosition

    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun togglePlay() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        if (player.isPlaying) player.pause() else player.play()
    }

    override fun seekTo(ms: Long) = player.seekTo(ms.coerceIn(0, player.duration.coerceAtLeast(0)))
    override fun seekBy(deltaMs: Long) = seekTo(player.currentPosition + deltaMs)

    override fun selectAudio(id: String) = select(C.TRACK_TYPE_AUDIO, id)

    override fun selectText(id: String?) {
        if (id == null) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        } else {
            select(C.TRACK_TYPE_TEXT, id)
        }
    }

    private fun select(type: Int, id: String) {
        val (groupIndex, track) = id.split('/').let { it[0].toInt() to it[1].toInt() }
        val group = player.currentTracks.groups.getOrNull(groupIndex) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track))
            .build()
    }

    override fun setSubtitleSize(size: SubtitleSize) {
        subtitleFraction = size.fraction
        view?.subtitleView?.setFractionalTextSize(size.fraction)
    }

    override fun release() {
        player.removeListener(listener)
        player.release()
    }

    @Composable
    override fun VideoSurface(modifier: Modifier) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    keepScreenOn = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(AndroidColor.BLACK)
                    player = this@ExoEngine.player
                    subtitleView?.apply {
                        setApplyEmbeddedFontSizes(false)
                        setFractionalTextSize(subtitleFraction)
                        setStyle(
                            CaptionStyleCompat(
                                AndroidColor.WHITE,
                                AndroidColor.TRANSPARENT,
                                AndroidColor.TRANSPARENT,
                                CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                                AndroidColor.BLACK,
                                ResourcesCompat.getFont(ctx, R.font.inter_semi_bold),
                            ),
                        )
                    }
                    view = this
                }
            },
            onRelease = { view = null },
            modifier = modifier,
        )
    }
}
