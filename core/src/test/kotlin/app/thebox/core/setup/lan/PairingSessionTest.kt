package app.thebox.core.setup.lan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [PairingSession] rules of docs/design/m5-setup-settings.md §3.5.2, including the same-IP re-pair of the owner finding
 * 2026-11-01 (the phone's QR scanner fetched the link before the browser did).
 */
class PairingSessionTest {
    private var counter = 0
    private var changes = 0
    private val a = "192.168.1.50"
    private val b = "192.168.1.77"

    private fun session() = PairingSession({ 1_000L }, { bytes -> for (i in bytes.indices) bytes[i] = (counter++ * 41 + i).toByte() }, onChange = { changes++ })

    private fun wrongCode(s: PairingSession) = if (s.code == "000000") "111111" else "000000"

    @Test
    fun sameIpTokenRePairRotatesTheCookieAndRefusesTheOldOne() {
        val s = session()
        val first = assertIs<PairingSession.PairResult.Paired>(s.pairWithToken(s.token, a)).cookie
        val before = changes
        val second = assertIs<PairingSession.PairResult.Paired>(s.pairWithToken(s.token, a)).cookie
        assertNotEquals(first, second)
        assertEquals(before + 1, changes, "the TV screen hears about the re-pair")
        assertEquals(a, s.pairedFrom)
        assertEquals(PairingSession.Auth.NO_SESSION, s.authorize(first, a))
        assertEquals(PairingSession.Auth.OK, s.authorize(second, a))
    }

    @Test
    fun sameIpCodeRePairAfterATokenPairing() {
        val s = session()
        val first = assertIs<PairingSession.PairResult.Paired>(s.pairWithToken(s.token, a)).cookie
        assertEquals(PairingSession.Auth.NO_SESSION, s.authorize(null, a)) // the browser arrived without the cookie
        val second = assertIs<PairingSession.PairResult.Paired>(s.pairWithCode(PairingSession.displayCode(s.code), a)).cookie
        assertNotEquals(first, second)
        assertEquals(PairingSession.Auth.NO_SESSION, s.authorize(first, a))
        assertEquals(PairingSession.Auth.OK, s.authorize(second, a))
    }

    @Test
    fun anotherIpIsStillBusyOnBothPaths() {
        val s = session()
        val cookie = assertIs<PairingSession.PairResult.Paired>(s.pairWithToken(s.token, a)).cookie
        assertEquals(PairingSession.PairResult.Busy, s.pairWithToken(s.token, b))
        assertEquals(PairingSession.PairResult.Busy, s.pairWithCode(s.code, b))
        assertEquals(PairingSession.PairResult.Busy, s.pairWithCode(wrongCode(s), b))
        assertEquals(0, s.wrongCodes, "another IP's codes are refused uncounted")
        assertEquals(a, s.pairedFrom)
        assertEquals(PairingSession.Auth.OK, s.authorize(cookie, a))
        assertEquals(PairingSession.Auth.OTHER_PHONE, s.authorize(cookie, b))
    }

    @Test
    fun wrongTokenAfterPairingIsBusyAndAWrongCodeFromThePairedIpCounts() {
        val s = session()
        val cookie = assertIs<PairingSession.PairResult.Paired>(s.pairWithCode(s.code, a)).cookie
        assertEquals(PairingSession.PairResult.Busy, s.pairWithToken("AAAAAAAAAAAAAAAAAAAAAA", a))
        assertEquals(PairingSession.PairResult.Wrong(LanLimits.PAIR_MAX_WRONG - 1), s.pairWithCode(wrongCode(s), a))
        assertEquals(1, s.wrongCodes)
        assertEquals(PairingSession.Auth.OK, s.authorize(cookie, a), "a wrong attempt does not drop the session")
    }

    @Test
    fun wrongTokenBeforePairingNeverPairs() {
        val s = session()
        assertEquals(PairingSession.PairResult.Stopped(null), s.pairWithToken("AAAAAAAAAAAAAAAAAAAAAA", a))
        assertEquals(null, s.pairedFrom)
    }

    // F2 (security review 2026-11-09): wrong codes are counted per remote address.

    @Test
    fun oneAddressExhaustingItsTriesIsBlockedButTheSessionStaysOpen() {
        val s = session()
        val first = s.code
        for (left in LanLimits.PAIR_MAX_WRONG - 1 downTo 1) assertEquals(PairingSession.PairResult.Wrong(left), s.pairWithCode(wrongCode(s), b))
        assertEquals(PairingSession.PairResult.Wrong(0), s.pairWithCode(wrongCode(s), b), "the 5th rotates the code")
        assertNotEquals(first, s.code)
        for (left in LanLimits.PAIR_MAX_WRONG - 1 downTo 1) assertEquals(PairingSession.PairResult.Wrong(left), s.pairWithCode(wrongCode(s), b))
        assertEquals(PairingSession.PairResult.Stopped(null), s.pairWithCode(wrongCode(s), b), "the 10th blocks the address")
        assertEquals(false, s.ended)
        val counted = s.wrongCodes
        assertEquals(PairingSession.PairResult.Stopped(null), s.pairWithCode(s.code, b), "a blocked address cannot pair, even with the right code")
        repeat(100) { s.pairWithCode(wrongCode(s), b) }
        assertEquals(counted, s.wrongCodes, "a blocked address is not counted again")
        assertEquals(false, s.ended)
        assertIs<PairingSession.PairResult.Paired>(s.pairWithCode(s.code, a))
        assertEquals(a, s.pairedFrom)
    }

    @Test
    fun theGlobalCeilingEndsTheSessionAndNeedsSeveralAddresses() {
        val s = session()
        val perAddress = 2 * LanLimits.PAIR_MAX_WRONG
        var guesses = 0
        var ip = 0
        while (!s.ended) {
            val r = s.pairWithCode(wrongCode(s), "10.0.0.${ip + 1}")
            guesses++
            if (guesses % perAddress == 0) ip++
            if (r is PairingSession.PairResult.Stopped && r.failure != null) break
            check(guesses <= 1_000) { "the session never ended" }
        }
        assertEquals(LanLimits.PAIR_MAX_WRONG_TOTAL, guesses, "worst case: this many guesses at a 10^6 code per session")
        assertEquals(LanFailure.TOO_MANY_WRONG_CODES, s.failure)
        assertTrue((LanLimits.PAIR_MAX_WRONG_TOTAL + perAddress - 1) / perAddress >= 3, "one or two hosts cannot end it")
    }

    @Test
    fun thePairedAddressExhaustingItsTriesStillEndsTheSession() {
        val s = session()
        assertIs<PairingSession.PairResult.Paired>(s.pairWithCode(s.code, a))
        var last: PairingSession.PairResult? = null
        repeat(2 * LanLimits.PAIR_MAX_WRONG) { last = s.pairWithCode(wrongCode(s), a) }
        assertEquals(PairingSession.PairResult.Stopped(LanFailure.TOO_MANY_WRONG_CODES), last)
        assertEquals(true, s.ended)
    }
}
