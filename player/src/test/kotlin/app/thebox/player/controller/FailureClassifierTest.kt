package app.thebox.player.controller

import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.StuckPlayerException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import androidx.media3.exoplayer.source.BehindLiveWindowException
import app.thebox.core.playback.FailureSignal
import app.thebox.core.playback.LoadOrigin
import app.thebox.core.playback.NetworkFailureKind
import app.thebox.core.playback.StallKind
import app.thebox.player.datasource.FailureInfo
import app.thebox.player.datasource.LiveFailureKind
import app.thebox.player.datasource.LiveStreamException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ExecutionException
import javax.net.ssl.SSLHandshakeException

/** §5.3 WP-D `FailureClassifier`: the rows of §4.1 with real Media3 exception objects. */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FailureClassifierTest {
    private val classifier = FailureClassifier()
    private val start = SystemClock.elapsedRealtime()
    private val facts = FailureClassifier.Facts(currentTuneId = 7, sessionStartMs = start)
    private val spec = DataSpec(Uri.parse("http://panel.example:8080/x"))

    private fun source(cause: IOException, code: Int = PlaybackException.ERROR_CODE_IO_UNSPECIFIED) = ExoPlaybackException.createForSource(cause, code)

    private fun http(status: Int) = HttpDataSource.InvalidResponseCodeException(status, null, null, emptyMap(), spec, ByteArray(0))

    private fun network(root: Throwable) = HttpDataSource.HttpDataSourceException(
        IOException(ExecutionException(root)), spec, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, HttpDataSource.HttpDataSourceException.TYPE_OPEN,
    )

    private fun renderer(cause: Throwable, format: Format, code: Int) =
        ExoPlaybackException.createForRenderer(cause, "r", 0, format, C.FORMAT_HANDLED, null, false, code)

    @Test
    fun errorCreatedBeforeTheSessionIsStale() {
        val e = source(http(403))
        assertNull(classifier.classify(e, facts.copy(sessionStartMs = e.timestampMs + 1)))
    }

    @Test
    fun httpStatusOrigins() {
        assertEquals(FailureSignal.Http(403, LoadOrigin.PROGRESSIVE), classifier.classify(source(http(403)), facts))
        assertEquals(FailureSignal.Http(404, LoadOrigin.ARCHIVE), classifier.classify(source(http(404)), facts.copy(isArchive = true)))
        assertEquals(
            FailureSignal.Http(404, LoadOrigin.HLS_MANIFEST),
            classifier.classify(source(http(404)), facts.copy(isHls = true, lastLoadDataType = C.DATA_TYPE_MANIFEST)),
        )
        assertEquals(
            FailureSignal.Http(404, LoadOrigin.HLS_SEGMENT),
            classifier.classify(source(http(404)), facts.copy(isHls = true, lastLoadDataType = C.DATA_TYPE_MEDIA)),
        )
    }

    @Test
    fun hlsPlaylistExceptions() {
        assertEquals(FailureSignal.Stall(StallKind.HLS_NO_PROGRESS), classifier.classify(source(HlsPlaylistTracker.PlaylistStuckException(Uri.EMPTY)), facts))
        assertEquals(FailureSignal.BehindLiveWindow, classifier.classify(source(HlsPlaylistTracker.PlaylistResetException(Uri.EMPTY)), facts))
    }

    @Test
    fun networkRowsUseTheRootCause() {
        assertEquals(FailureSignal.Network(NetworkFailureKind.DNS, true), classifier.classify(source(network(UnknownHostException("h"))), facts))
        assertEquals(FailureSignal.Network(NetworkFailureKind.CONNECT, true), classifier.classify(source(network(ConnectException("c"))), facts))
        assertEquals(FailureSignal.Network(NetworkFailureKind.TIMEOUT, true), classifier.classify(source(network(SocketTimeoutException("t"))), facts))
        assertEquals(FailureSignal.Network(NetworkFailureKind.TLS, true), classifier.classify(source(network(SSLHandshakeException("s"))), facts))
        assertEquals(FailureSignal.Network(NetworkFailureKind.RESET, false), classifier.classify(source(network(IOException("r"))), facts.copy(bytesReceived = 10)))
        assertEquals(
            FailureSignal.Network(NetworkFailureKind.CLEARTEXT, true),
            classifier.classify(source(HttpDataSource.CleartextNotPermittedException(IOException(), spec)), facts),
        )
    }

    @Test
    fun behindLiveWindow() {
        assertEquals(FailureSignal.BehindLiveWindow, classifier.classify(source(BehindLiveWindowException(), PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW), facts))
    }

    @Test
    fun decoderRows() {
        val hevc = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setWidth(3840).setHeight(2160).build()
        val init = MediaCodecRenderer.DecoderInitializationException(hevc, null, false, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
        assertEquals(
            FailureSignal.DecoderInit(true, MimeTypes.VIDEO_H265, null),
            classifier.classify(renderer(init, hevc, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED), facts),
        )
        val mp2 = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_MPEG_L2).setChannelCount(2).build()
        assertEquals(
            FailureSignal.DecodingFailed(false, MimeTypes.AUDIO_MPEG_L2),
            classifier.classify(renderer(IllegalStateException(), mp2, PlaybackException.ERROR_CODE_DECODING_FAILED), facts),
        )
        assertEquals(
            FailureSignal.FormatUnsupported(true, MimeTypes.VIDEO_H265),
            classifier.classify(renderer(IllegalStateException(), hevc, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED), facts),
        )
        assertEquals(
            FailureSignal.ResourcesReclaimed,
            classifier.classify(renderer(IllegalStateException(), hevc, PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED), facts),
        )
    }

    @Test
    fun audioRows() {
        val ac3 = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC3).setChannelCount(6).build()
        val init = AudioSink.InitializationException("init", 0, ac3, false, null)
        assertEquals(
            FailureSignal.AudioTrackInit(true, 6, MimeTypes.AUDIO_AC3),
            classifier.classify(renderer(init, ac3, PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED), facts.copy(passthroughActive = true)),
        )
        assertEquals(
            FailureSignal.AudioWrite,
            classifier.classify(renderer(IllegalStateException(), ac3, PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED), facts),
        )
    }

    @Test
    fun passthroughInitFailureIsEncoded() {
        // Code review 2026-10-14, E-S3: passthroughActive is false at an init failure (onAudioTrackInitialized never
        // fired); the sink's input format says it all.
        for (mime in listOf(MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_TRUEHD)) {
            val f = Format.Builder().setSampleMimeType(mime).setChannelCount(6).build()
            val init = AudioSink.InitializationException("init", 0, f, false, null)
            assertEquals(mime, FailureSignal.AudioTrackInit(true, 6, mime), classifier.classify(renderer(init, f, PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED), facts.copy(passthroughActive = false)))
        }
    }

    @Test
    fun decodedPcmInitFailureIsNotEncoded() {
        // Decoding AC-3 to PCM: the sink input is audio/raw even though the renderer format is AC-3.
        val ac3 = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC3).setChannelCount(6).build()
        val pcm = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setChannelCount(6).build()
        val init = AudioSink.InitializationException("init", 0, pcm, false, null)
        assertEquals(
            FailureSignal.AudioTrackInit(false, 6, MimeTypes.AUDIO_RAW),
            classifier.classify(renderer(init, ac3, PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED), facts.copy(passthroughActive = true)),
        )
        // Error code only (no exception in the chain): the renderer's encoded MIME decides.
        assertEquals(
            FailureSignal.AudioTrackInit(true, 6, MimeTypes.AUDIO_AC3),
            classifier.classify(renderer(IllegalStateException(), ac3, PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED), facts),
        )
    }

    @Test
    fun parsingStuckAndUnknown() {
        assertEquals(FailureSignal.Parsing(false), classifier.classify(source(ParserException.createForMalformedContainer("x", null), PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED), facts))
        assertEquals(FailureSignal.Parsing(true), classifier.classify(source(ParserException.createForMalformedManifest("x", null), PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED), facts))
        val stuck = ExoPlaybackException.createForUnexpected(StuckPlayerException(StuckPlayerException.STUCK_BUFFERING_NO_PROGRESS, 30_000), PlaybackException.ERROR_CODE_TIMEOUT)
        assertEquals(FailureSignal.Stuck(StuckPlayerException.STUCK_BUFFERING_NO_PROGRESS), classifier.classify(stuck, facts))
        assertEquals(FailureSignal.Unknown(PlaybackException.ERROR_CODE_DRM_UNSPECIFIED), classifier.classify(PlaybackException("x", null, PlaybackException.ERROR_CODE_DRM_UNSPECIFIED), facts))
    }

    // ---- LiveStreamException rows (un-skipped at integration, 2026-10-13: WP-B's media3Reason() is real).

    private fun live(kind: LiveFailureKind, tuneId: Long = 7, status: Int? = null, bytes: Long = 0, net: NetworkFailureKind? = null, delta: Long? = null): LiveStreamException =
        LiveStreamException(kind, FailureInfo(status, "panel.example:8080", bytes, 1, FailureInfo.Phase.READ, net, delta, 0), DataSpec(Uri.parse("thebox-session://$tuneId")), null)

    private fun liveOrSkip(kind: LiveFailureKind, tuneId: Long = 7, status: Int? = null, bytes: Long = 0, net: NetworkFailureKind? = null, delta: Long? = null): PlaybackException {
        val e = live(kind, tuneId, status, bytes, net, delta)
        return source(e, e.reason)
    }

    @Test
    fun reconnectExhaustedNoBytesComesFromTheTimeoutKind() {
        // WP-B: noBytes = info.networkKind == TIMEOUT (bytes may have flowed before the last attempt).
        assertEquals(
            FailureSignal.ReconnectExhausted(null, true),
            classifier.classify(liveOrSkip(LiveFailureKind.RECONNECT_EXHAUSTED, bytes = 1_000, net = NetworkFailureKind.TIMEOUT), facts),
        )
    }

    @Test
    fun localLimitReadsTheSuppressedBrokerException() {
        val e = live(LiveFailureKind.LOCAL_LIMIT).apply { addSuppressed(app.thebox.core.playback.ConnectionLimitException(2, 2)) }
        assertEquals(FailureSignal.LocalConnectionLimit(2, 2), classifier.classify(source(e, e.reason), facts))
    }

    @Test
    fun tinyBodyCarriesTheBytes() {
        assertEquals(FailureSignal.TinyBody(37), classifier.classify(liveOrSkip(LiveFailureKind.TINY_BODY, bytes = 37), facts))
    }

    @Test
    fun liveRows() {
        assertEquals(FailureSignal.Http(458, LoadOrigin.PROGRESSIVE), classifier.classify(liveOrSkip(LiveFailureKind.HTTP_STATUS, status = 458), facts))
        assertEquals(FailureSignal.Network(NetworkFailureKind.TIMEOUT, true), classifier.classify(liveOrSkip(LiveFailureKind.NETWORK, net = NetworkFailureKind.TIMEOUT), facts))
        assertEquals(FailureSignal.ReconnectExhausted(503, false), classifier.classify(liveOrSkip(LiveFailureKind.RECONNECT_EXHAUSTED, status = 503, bytes = 10), facts))
        assertEquals(FailureSignal.HtmlInsteadOfStream, classifier.classify(liveOrSkip(LiveFailureKind.HTML_PAGE), facts))
        assertEquals(FailureSignal.TinyBody(0), classifier.classify(liveOrSkip(LiveFailureKind.TINY_BODY), facts))
        assertEquals(FailureSignal.FormatIsHls, classifier.classify(liveOrSkip(LiveFailureKind.FORMAT_IS_HLS), facts))
        assertEquals(FailureSignal.FormatUnrecognized, classifier.classify(liveOrSkip(LiveFailureKind.FORMAT_UNRECOGNIZED), facts))
        assertEquals(FailureSignal.PcrDiscontinuity(5_000), classifier.classify(liveOrSkip(LiveFailureKind.PCR_DISCONTINUITY, delta = 5_000), facts))
    }

    @Test
    fun liveErrorsOfAnotherTuneOrPreemptedAreStale() {
        assertNull(classifier.classify(liveOrSkip(LiveFailureKind.HTTP_STATUS, tuneId = 6, status = 403), facts))
        assertNull(classifier.classify(liveOrSkip(LiveFailureKind.PREEMPTED), facts))
    }
}
