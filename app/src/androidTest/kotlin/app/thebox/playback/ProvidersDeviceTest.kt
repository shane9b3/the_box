package app.thebox.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.thebox.core.common.Outcome
import app.thebox.core.model.StableKey
import app.thebox.core.playback.AspectMode
import app.thebox.core.playback.BufferMode
import app.thebox.core.playback.VideoDecoderMode
import app.thebox.core.playback.XtreamOutput
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.core.source.PlaylistType
import app.thebox.debug.PlaybackDebugActivity
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * §5.3 WP-D "Providers in :app" on the real Room database (the `:app` module has no Robolectric/Room-testing host
 * setup, so these run instrumented): column mapping, `playback_flags` bits, column-targeted writes that do not revert
 * `hidden`, learned output and base index, credentials mapping. Needs no fake panel.
 */
@RunWith(AndroidJUnit4::class)
class ProvidersDeviceTest {
    private val ep = EntryPointAccessors.fromApplication(
        InstrumentationRegistry.getInstrumentation().targetContext, PlaybackDebugActivity.DebugPlaybackEntryPoint::class.java,
    )
    private val created = mutableListOf<Long>()

    @After fun tearDown() = runBlocking { created.forEach { ep.playlists().delete(it) } }

    private fun xtream(): Long = runBlocking {
        (ep.playlists().addXtream("it-prov-${UUID.randomUUID()}", listOf("http://127.0.0.1:9", "http://127.0.0.1:10"), "user1", "pass1") as Outcome.Ok).value
    }.also { created += it }

    private fun m3u(): Long = runBlocking {
        (ep.playlists().addM3uUrl("it-prov-${UUID.randomUUID()}", "http://127.0.0.1:9/list.m3u") as Outcome.Ok).value
    }.also { created += it }

    @Test
    fun channelMemoryRoundTripsEveryColumnAndFlagBits() = runBlocking {
        val id = ChannelIdentity(m3u(), StableKey("k-${UUID.randomUUID()}"))
        val memory = ep.channelMemory()
        assertEquals(app.thebox.player.api.ChannelPlaybackMemory(), memory.read(id))
        memory.update(id) {
            it.copy(
                audioLanguage = "de", subtitleLanguage = "", aspect = AspectMode.ZOOM, videoDecoder = VideoDecoderMode.SOFTWARE_PREFERRED,
                bufferMode = BufferMode.LARGE, tsCompatLevel = 1, knownHdr = true, fpsMilli = 50_000,
            )
        }
        val m = memory.read(id)
        assertEquals("de", m.audioLanguage)
        assertEquals("", m.subtitleLanguage)
        assertEquals(AspectMode.ZOOM, m.aspect)
        assertEquals(VideoDecoderMode.SOFTWARE_PREFERRED, m.videoDecoder)
        assertEquals(BufferMode.LARGE, m.bufferMode)
        assertEquals(1, m.tsCompatLevel)
        assertTrue(m.knownHdr)
        assertEquals(50_000, m.fpsMilli)
        assertEquals(0b101, ep.channelPrefs().get(id)!!.playbackFlags)
    }

    @Test
    fun memoryWritesDoNotRevertHidden() = runBlocking {
        val id = ChannelIdentity(m3u(), StableKey("k-${UUID.randomUUID()}"))
        ep.channelMemory().update(id) { it.copy(fpsMilli = 25_000) }
        ep.channelPrefs().setHidden(id, true)
        ep.channelMemory().update(id) { it.copy(knownHdr = true, aspect = AspectMode.FILL) }
        val row = ep.channelPrefs().get(id)!!
        assertTrue(row.hidden)
        assertEquals(25_000, row.fpsMilli)
        ep.channelMemory().recordWatched(id)
        assertEquals(1, ep.channelPrefs().get(id)!!.watchCount)
    }

    @Test
    fun playlistMappingAndLearnedValues() = runBlocking {
        val pid = xtream()
        val provider = ep.sourceProvider()
        val info = provider.playlist(pid)!!
        assertEquals(PlaylistType.XTREAM, info.type)
        assertEquals(XtreamOutput.AUTO, info.xtreamOutput)
        assertNull(info.xtreamOutputLearned)
        provider.saveLearnedOutput(pid, XtreamOutput.HLS)
        provider.saveActiveBaseIndex(pid, 1)
        val after = provider.playlist(pid)!!
        assertEquals(XtreamOutput.HLS, after.xtreamOutputLearned)
        assertEquals(1, after.activeBaseIndex)
        provider.saveLearnedOutput(pid, null)
        assertNull(provider.playlist(pid)!!.xtreamOutputLearned)
    }

    @Test
    fun credentialsMapping() = runBlocking {
        val provider = ep.sourceProvider()
        val x = provider.credentials(xtream()) as Outcome.Ok
        val c = x.value!!
        assertEquals(2, c.baseUrls.size)
        assertEquals("user1", c.username)
        assertFalse(c.toString().contains("user1"))
        val plain = provider.credentials(m3u()) as Outcome.Ok
        assertNull("plain M3U has no credentials", plain.value)
        assertNull(provider.account(created.last()))
    }
}
