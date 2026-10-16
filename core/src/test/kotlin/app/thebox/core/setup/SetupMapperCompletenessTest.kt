package app.thebox.core.setup

import app.thebox.core.common.AuthReason
import app.thebox.core.common.CredentialFailure
import app.thebox.core.common.NetworkKind
import app.thebox.core.common.NotASourceKind
import app.thebox.core.common.SourceError
import app.thebox.core.settings.SourceKind
import app.thebox.core.settings.Tone
import app.thebox.core.xtream.AccountStatus
import app.thebox.core.xtream.XtreamAccountInfo
import app.thebox.core.xtream.XtreamServerInfo
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * §3.4.4 / §6.1: every [SourceError] subtype × [NetworkKind] × [AuthReason] × [NotASourceKind] × [CredentialFailure],
 * every [AccountStatus] and every [InputProblem] → a headline (the reason) and a next step; no raw exception text.
 */
class SetupMapperCompletenessTest {
    private val strings = KeyStrings()
    private val m = SetupResultMapper(strings, { ZoneOffset.UTC }, { Locale.UK })
    private val now = 1_790_000_000_000L

    /** Exception-like text and URLs planted in every `detail`: none may reach a card. */
    private val secret = "java.net.UnknownHostException: http://u:p@panel.example.com/get.php?password=hunter2"

    private fun everyError(): List<SourceError> = buildList {
        NetworkKind.entries.forEach { add(SourceError.Network(it, secret)) }
        listOf(400, 401, 403, 404, 405, 406, 410, 429, 451, 458, 500, 502, 503, 509, 512).forEach { add(SourceError.Http(it)) }
        AuthReason.entries.forEach { add(SourceError.Auth(it)) }
        NotASourceKind.entries.forEach { add(SourceError.NotASource(it)) }
        add(SourceError.SuspiciousShrink(2_140, 12))
        add(SourceError.SuspiciousShrink(0, 0))
        add(SourceError.Parse(secret))
        add(SourceError.DiskSpace(300L shl 20, 100L shl 20))
        CredentialFailure.entries.forEach { add(SourceError.Credentials(it)) }
        add(SourceError.Unsupported(secret))
        add(SourceError.Cancelled)
        add(SourceError.Internal(secret))
    }

    /** Every sealed subtype of [SourceError] is in [everyError]: [subtype] is exhaustive, so a new one fails to compile. */
    @Test
    fun everySourceErrorSubtypeIsCovered() {
        assertEquals(SUBTYPES, everyError().map(::subtype).toSet())
    }

    private fun subtype(e: SourceError): String = when (e) {
        is SourceError.Network -> "Network"
        is SourceError.Http -> "Http"
        is SourceError.Auth -> "Auth"
        is SourceError.NotASource -> "NotASource"
        is SourceError.SuspiciousShrink -> "SuspiciousShrink"
        is SourceError.Parse -> "Parse"
        is SourceError.DiskSpace -> "DiskSpace"
        is SourceError.Credentials -> "Credentials"
        is SourceError.Unsupported -> "Unsupported"
        SourceError.Cancelled -> "Cancelled"
        is SourceError.Internal -> "Internal"
    }

    private companion object {
        val SUBTYPES = setOf("Network", "Http", "Auth", "NotASource", "SuspiciousShrink", "Parse", "DiskSpace", "Credentials", "Unsupported", "Cancelled", "Internal")
    }

    @Test
    fun everyErrorHasAReasonAndANextStepAndNoRawText() {
        for (kind in SourceKind.entries) for (host in listOf(null, "panel.example.com")) for (e in everyError()) {
            val card = m.error(e, SetupResultMapper.Context(kind, host, port = 8080))
            assertCard(card, e.toString())
            // A card about a failure is never GOOD and never offers to just continue.
            assertNotEquals(Tone.GOOD, card.tone, "$e")
            assertNotEquals(SetupAction.CONTINUE, card.primary, "$e")
        }
    }

    @Test
    fun everyAccountStatusMapsToACard() {
        for (status in AccountStatus.entries) for (exp in listOf(null, now / 1000 + 86_400 * 152L, now / 1000 - 86_400 * 3L)) {
            val info = XtreamAccountInfo(
                auth = true, status = status, expiresAtSec = exp, activeConnections = 1, maxConnections = 2,
                allowedOutputFormats = listOf("ts", "m3u8"), message = "  Your account is\nbanned.  ",
                server = XtreamServerInfo(timestampNow = now / 1000 + 240), measuredAtDeviceMs = now,
            )
            val card = m.login(info, now)
            assertCard(card, "$status/$exp")
            when (status) {
                AccountStatus.ACTIVE, AccountStatus.UNKNOWN -> assertEquals(SetupAction.CONTINUE, card.primary)
                AccountStatus.EXPIRED -> assertEquals(SetupAction.SAVE_ANYWAY to SetupAction.EDIT, card.primary to card.secondary)
                AccountStatus.BANNED, AccountStatus.DISABLED -> assertEquals(SetupAction.EDIT to SetupAction.SAVE_ANYWAY, card.primary to card.secondary)
            }
        }
    }

    @Test
    fun anUnauthenticatedLoginIsBadCredentials() {
        val info = XtreamAccountInfo(auth = false, status = AccountStatus.ACTIVE, measuredAtDeviceMs = now)
        assertEquals(m.error(SourceError.Auth(AuthReason.BAD_CREDENTIALS)), m.login(info, now))
    }

    @Test
    fun everyInputProblemHasACard() {
        for (p in InputProblem.entries) assertCard(m.input(p), p.name)
    }

    @Test
    fun theConnectedCardCarriesExpiryConnectionsFormatsAndClock() {
        val info = XtreamAccountInfo(
            auth = true, status = AccountStatus.ACTIVE, expiresAtSec = now / 1000 + 86_400 * 152L - 3_600,
            activeConnections = 1, maxConnections = 2, allowedOutputFormats = listOf("ts", "m3u8", "TS"),
            server = XtreamServerInfo(timestampNow = now / 1000 + 240), measuredAtDeviceMs = now,
        )
        val card = m.login(info, now)
        assertEquals("CARD_CONNECTED", card.headline)
        assertEquals(
            listOf(
                "CARD_ACTIVE_EXPIRES(20 Feb 2027|152|)",
                "CARD_CONNECTIONS_IN_USE(1|2)",
                "CARD_FORMATS(MPEG-TS, HLS)",
                "CARD_CLOCK_AHEAD(4)",
            ),
            card.lines,
        )
        val synced = m.login(info.copy(expiresAtSec = null, isTrial = true, server = XtreamServerInfo(timestampNow = now / 1000 + 20)), now)
        assertEquals(listOf("CARD_ACTIVE_NEVER_EXPIRES(CARD_TRIAL_SUFFIX)", "CARD_CONNECTIONS_IN_USE(1|2)", "CARD_FORMATS(MPEG-TS, HLS)", "CARD_CLOCK_IN_SYNC"), synced.lines)
    }

    @Test
    fun theTableRowsOfDesign344() {
        assertEquals("CARD_EXPIRED_ON(3 Oct 2026)", m.login(XtreamAccountInfo(true, AccountStatus.EXPIRED, expiresAtSec = 1_791_000_000L, measuredAtDeviceMs = now), now).headline)
        val banned = m.login(XtreamAccountInfo(true, AccountStatus.BANNED, message = "x".repeat(300), measuredAtDeviceMs = now), now)
        assertEquals(SetupConstants.PROVIDER_MESSAGE_MAX_CHARS, banned.lines.first().length, "provider message cut to 120 chars")
        assertEquals(SetupAction.EDIT to SetupAction.PHONE_SETUP, m.error(SourceError.Auth(AuthReason.BAD_CREDENTIALS)).let { it.primary to it.secondary })
        assertEquals("CARD_HTTP_LOGIN_REFUSED(403)", m.error(SourceError.Http(403)).headline)
        assertEquals("CARD_HTTP_REFUSED(403)", m.error(SourceError.Http(403), SetupResultMapper.Context(SourceKind.M3U)).headline)
        val dns = m.error(SourceError.Network(NetworkKind.DNS), SetupResultMapper.Context(host = "panel.example.com"))
        assertEquals("CARD_DNS_HOST(panel.example.com)", dns.headline)
        assertEquals(SetupAction.USE_SECURE_DNS to SetupAction.EDIT, dns.primary to dns.secondary)
        assertEquals("CARD_NO_ANSWER_PORT(8080)", m.error(SourceError.Network(NetworkKind.TIMEOUT), SetupResultMapper.Context(port = 8080)).headline)
        assertEquals(SetupAction.RETRY to SetupAction.EDIT, m.error(SourceError.Network(NetworkKind.RESET)).let { it.primary to it.secondary })
        assertEquals("CARD_TLS", m.error(SourceError.Network(NetworkKind.TLS)).headline)
        assertEquals("CARD_HTML", m.error(SourceError.NotASource(NotASourceKind.HTML_PAGE)).headline)
        assertEquals(SetupAction.ADD_AS_SINGLE_CHANNEL, m.error(SourceError.NotASource(NotASourceKind.HLS_MANIFEST)).primary)
        val shrink = m.error(SourceError.SuspiciousShrink(2_140, 12))
        assertEquals("CARD_SHRINK(12|2,140)", shrink.headline)
        assertEquals("ACTION_ACCEPT_ANYWAY", m.actionLabel(shrink.primary, SourceError.SuspiciousShrink(2_140, 12)))
        assertEquals("ACTION_SAVE_ANYWAY", m.actionLabel(SetupAction.SAVE_ANYWAY))
        assertEquals(SetupAction.RETRY to null, m.error(SourceError.Credentials(CredentialFailure.UNAVAILABLE)).let { it.primary to it.secondary })
        assertEquals("CARD_INTERNAL_NEXT(internal)", m.error(SourceError.Internal(secret)).lines.single(), "code key only")
    }

    @Test
    fun errorCodesRoundTripToTheSameCard() {
        for (e in everyError()) {
            val back = SetupResultMapper.errorFromCode(e.code)
            assertEquals(e.code, back.code, "$e")
            // Kinds and statuses survive; only the free-text details (never shown) are lost.
            if (e !is SourceError.SuspiciousShrink && e !is SourceError.DiskSpace) assertEquals(m.error(e).headline, m.error(back).headline, "$e")
        }
        assertEquals("LINE_REASON_NEXT(CARD_HTTP_LOGIN_REFUSED(403)|ACTION_EDIT_SOURCE)", m.refreshFailureLine("http.403"))
        assertEquals("LINE_REASON_NEXT(CARD_NO_ANSWER|ACTION_RETRY)", m.refreshFailureLine("net.timeout"))
        assertTrue(m.refreshFailureLine("weird.code").startsWith("LINE_REASON_NEXT(CARD_INTERNAL"))
    }

    @Test
    fun everyActionHasALabel() {
        for (a in SetupAction.entries) assertTrue(m.actionLabel(a).startsWith("ACTION_"), a.name)
    }

    private fun assertCard(card: SetupCard, what: String) {
        assertTrue(card.headline.isNotBlank(), "$what: headline")
        // The next step: a line saying what to do, or (for the two §3.4.4 rows without lines) the action itself.
        assertTrue(card.lines.isNotEmpty(), "$what: a next-step line")
        assertTrue(card.lines.all { it.isNotBlank() }, "$what: blank line")
        assertNotEquals(card.primary, card.secondary, "$what: two different actions")
        val text = (listOf(card.headline) + card.lines).joinToString("\n")
        for (bad in listOf("Exception", "hunter2", "http://u:p@", "get.php", "java.")) assertFalse(bad in text, "$what: raw text '$bad' in $text")
    }
}

/** [SetupStrings] that renders `KEY(arg|arg)`: tests assert on keys and arguments, not on English. */
class KeyStrings : SetupStrings {
    override fun text(key: SetupText, vararg args: Any): String =
        if (args.isEmpty()) key.name else key.name + args.joinToString("|", "(", ")")
}
