package app.thebox.core.playback.diag

import app.thebox.core.common.Redact
import app.thebox.core.playback.PlaybackEventType
import app.thebox.core.playback.StreamFormat
import app.thebox.core.playback.ZapRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackEventLogTest {
    private var mono = 5_000L
    private var wall = 1_791_919_800_000L
    private val log = PlaybackEventLogImpl({ mono }, { wall })

    @Test
    fun eventsCarryBothClocks() {
        log.add(PlaybackEventType.TUNE, 7, "tune", "ch=1")
        val e = log.events().single()
        assertEquals(1_791_919_800_000L, e.wallMs)
        assertEquals(5_000L, e.elapsedMs)
        assertEquals(7L, e.tuneId)
        assertEquals(PlaybackEventType.TUNE, e.type)
        assertEquals("ch=1", e.detail)
    }

    @Test
    fun eventRingWrapsAtTwoHundred() {
        repeat(250) { mono = it.toLong(); log.add(PlaybackEventType.READY, it.toLong(), "c$it") }
        val events = log.events()
        assertEquals(DiagConstants.EVENT_LOG_CAPACITY, events.size)
        assertEquals(50L, events.first().tuneId)
        assertEquals(249L, events.last().tuneId)
        assertEquals((50L..249L).toList(), events.map { it.tuneId })
    }

    @Test
    fun zapRingWrapsAtFifty() {
        repeat(73) { log.addZap(ZapRecord(it.toLong(), "xc:$it", StreamFormat.TS, 1, 2, 3, 4, 5, "ok")) }
        val zaps = log.zaps()
        assertEquals(DiagConstants.ZAP_HISTORY, zaps.size)
        assertEquals(23L, zaps.first().tuneId)
        assertEquals(72L, zaps.last().tuneId)
    }

    @Test
    fun detailIsRedacted() {
        Redact.registerSecret("hunter2-secret")
        log.add(PlaybackEventType.OPEN, 1, "open", "GET http://h.example/live/joe/pw/1.ts")
        log.add(PlaybackEventType.OPEN, 1, "open", "redirect http://cdn.example/s.ts?token=abc&x=1 done")
        log.add(PlaybackEventType.ERROR, 1, "err", "password hunter2-secret leaked")
        val d = log.events().map { it.detail }
        assertEquals("GET http://h.example/live/***/***/1.ts", d[0])
        assertFalse(d[1].contains("abc"))
        assertTrue(d[1].startsWith("redirect http://cdn.example/s.ts?***"), d[1])
        assertEquals("password *** leaked", d[2])
    }

    @Test
    fun detailIsCutToOneHundredSixtyAfterRedaction() {
        val long = "x".repeat(400)
        log.add(PlaybackEventType.STALL, 1, "stall", long)
        assertEquals(DiagConstants.EVENT_DETAIL_MAX_CHARS, log.events().single().detail.length)
        // The secret straddles the cut: it is masked before cutting, so no prefix of it survives.
        Redact.registerSecret("tailsecret99")
        log.add(PlaybackEventType.STALL, 1, "stall", "y".repeat(155) + "tailsecret99")
        val d = log.events().last().detail
        assertEquals("y".repeat(155) + "***", d)
    }

    @Test
    fun snapshotsAreCopies() {
        log.add(PlaybackEventType.TUNE, 1, "a")
        val snap = log.events()
        log.add(PlaybackEventType.TUNE, 2, "b")
        assertEquals(1, snap.size)
        assertEquals(2, log.events().size)
    }

    @Test
    fun concurrentWritersDoNotLoseEvents() {
        val threads = (0 until 8).map { t -> Thread { repeat(25) { log.add(PlaybackEventType.RECONNECT, t.toLong(), "r") } } }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        assertEquals(200, log.events().size)
    }
}
