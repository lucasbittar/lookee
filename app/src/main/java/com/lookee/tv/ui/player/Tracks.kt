package com.lookee.tv.ui.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.lookee.tv.ui.player.engine.Choice
import java.util.Locale

/** Subtitle sidecars are loaded with this id prefix, so the panel can say "Next to the video". */
const val SIDECAR_ID = "sidecar:"

/** The audio or subtitle tracks as choices; ids are "group/track" indexes into [Tracks.groups]. */
fun Tracks.trackChoices(type: Int): List<Choice> = groups.withIndex().filter { it.value.type == type }.flatMap { (gi, g) ->
    (0 until g.length).map { i ->
        val f = g.getTrackFormat(i)
        Choice(
            id = "$gi/$i",
            label = trackLabel(f, type),
            caption = if (type == C.TRACK_TYPE_AUDIO) audioCaption(f) else if (f.id?.contains(SIDECAR_ID) == true) "Next to the video" else "In the file",
            selected = g.isTrackSelected(i),
            supported = g.isTrackSupported(i),
        )
    }
}

private fun trackLabel(f: Format, type: Int): String {
    val language = f.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }?.let(::languageName)
    val label = f.label?.takeIf { it.isNotBlank() }
    return when {
        language != null && label != null && !label.equals(language, ignoreCase = true) -> "$language · $label"
        language != null -> language
        label != null -> label
        type == C.TRACK_TYPE_AUDIO -> "Audio"
        else -> "Subtitles"
    }
}

/** "Português (Brasil)": each language in its own words. */
fun languageName(tag: String): String {
    val locale = Locale.forLanguageTag(tag)
    val name = locale.getDisplayName(locale)
    return if (name.isBlank() || name == tag) tag else name.replaceFirstChar { it.titlecase(locale) }
}

private fun audioCaption(f: Format): String = listOfNotNull(codecName(f.sampleMimeType), channels(f.channelCount)).joinToString(" · ")

fun channels(n: Int): String? = when (n) {
    Format.NO_VALUE, 0 -> null
    1 -> "Mono"
    2 -> "Stereo"
    6 -> "5.1"
    8 -> "7.1"
    else -> "$n ch"
}

@OptIn(UnstableApi::class)
fun codecName(mime: String?): String? = when (mime) {
    null -> null
    MimeTypes.VIDEO_H264 -> "H.264"
    MimeTypes.VIDEO_H265 -> "HEVC"
    MimeTypes.VIDEO_VP9 -> "VP9"
    MimeTypes.VIDEO_AV1 -> "AV1"
    MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
    MimeTypes.VIDEO_MP4V -> "MPEG-4"
    MimeTypes.VIDEO_DIVX -> "DivX"
    MimeTypes.VIDEO_VC1 -> "VC-1"
    MimeTypes.AUDIO_AC3 -> "AC3"
    MimeTypes.AUDIO_E_AC3 -> "E-AC3"
    MimeTypes.AUDIO_E_AC3_JOC -> "Atmos"
    MimeTypes.AUDIO_DTS -> "DTS"
    MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
    MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
    MimeTypes.AUDIO_TRUEHD -> "TrueHD"
    MimeTypes.AUDIO_AAC -> "AAC"
    MimeTypes.AUDIO_MPEG -> "MP3"
    MimeTypes.AUDIO_MPEG_L2 -> "MP2"
    MimeTypes.AUDIO_OPUS -> "Opus"
    MimeTypes.AUDIO_VORBIS -> "Vorbis"
    MimeTypes.AUDIO_FLAC -> "FLAC"
    MimeTypes.AUDIO_RAW -> "PCM"
    else -> mime.substringAfter('/').uppercase()
}

/** "1080p", "4K"… from the video size. */
fun resolution(f: Format): String? {
    val h = f.height
    val w = f.width
    if (h <= 0 && w <= 0) return null
    return when {
        w >= 3500 || h >= 2000 -> "4K"
        w >= 1800 || h >= 1000 -> "1080p"
        w >= 1200 || h >= 700 -> "720p"
        else -> "${h}p"
    }
}

/** The chip in the player header: "1080p · H.264 · AC3". */
fun Tracks.summary(): String {
    val video = groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let(g::getTrackFormat) }
    val audio = groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let(g::getTrackFormat) }
    return listOfNotNull(video?.let(::resolution), codecName(video?.sampleMimeType), codecName(audio?.sampleMimeType)).joinToString(" · ")
}

/** One line for the diagnostics log: every track, and whether this box can play it. */
fun Tracks.describe(): String = groups.joinToString("; ") { g ->
    val kind = when (g.type) {
        C.TRACK_TYPE_VIDEO -> "video"
        C.TRACK_TYPE_AUDIO -> "audio"
        C.TRACK_TYPE_TEXT -> "text"
        else -> "other"
    }
    val tracks = (0 until g.length).joinToString(", ") { i ->
        val f = g.getTrackFormat(i)
        val what = listOfNotNull(codecName(f.sampleMimeType) ?: f.codecs, resolution(f), channels(f.channelCount), f.language).joinToString(" ")
        "$what ${if (g.isTrackSupported(i)) "ok" else "UNSUPPORTED"}"
    }
    "$kind: $tracks"
}
