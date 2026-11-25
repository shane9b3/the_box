package app.thebox.data.sports

import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * docs/design/m6-sports-hub.md §6.2, §10.2 `KeyScanTest`: synthetic scripts only (never a real key): an empty default before
 * the real candidate, quote and spacing variants, a match across a read boundary, no match, the decoded cap. The gzip body
 * runs through OkHttp in [NhlClipResolverTest].
 */
class KeyScanTest {
    private val fake = "FAKE-KEY-FOR-TESTS-ONLY-0001"

    private fun scan(text: String, max: Long = 2_097_152L, step: Int = Int.MAX_VALUE): String? =
        KeyScan.scan(trickle(text, step), max)

    /** A source that hands out at most [step] bytes per read, so candidates straddle read boundaries. */
    private fun trickle(text: String, step: Int): BufferedSource {
        val all = Buffer().writeUtf8(text)
        return object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (all.size == 0L) return -1
                val n = minOf(byteCount, step.toLong(), all.size)
                sink.write(all, n)
                return n
            }
            override fun timeout() = Timeout.NONE
            override fun close() = Unit
        }.buffer()
    }

    @Test fun anEmptyDefaultIsSkippedAndTheScanContinues() {
        val script = """!function(){var d={policyKey:"",accountId:"1",playerId:"default"};}(); x.configure({policyKey:"$fake"});"""
        assertEquals(fake, scan(script))
    }

    @Test fun placeholdersShortValuesAndOtherNamesAreSkipped() {
        val script = """a={policyKey:"{{policyKey}}"};b={policyKey:"short"};c={xpolicyKey:"$fake-X"};d={policyKeys:"$fake-Y"};""" +
            """e={policyKey:"bad key with spaces in it 0123456789"};f={policyKey:"$fake"}"""
        assertEquals(fake, scan(script))
    }

    @Test fun quoteAndSpacingVariants() {
        assertEquals(fake, scan("""c={policyKey:'$fake'}"""))
        assertEquals(fake, scan("""c={"policyKey":"$fake"}"""))
        assertEquals(fake, scan("""c={"policyKey" :  "$fake"}"""))
        assertEquals(fake, scan("c={policyKey\n:\t'$fake'}"))
        assertEquals(fake, scan("""c={'policyKey':'$fake'}"""))
        // Mismatched quotes are not a candidate.
        assertNull(scan("""c={policyKey:"$fake'}"""))
        assertNull(scan("""c={"policyKey:"$fake"}"""))
    }

    @Test fun aMatchAcrossEveryReadBoundaryIsFound() {
        val script = "x".repeat(8_190) + """;q={policyKey:"",z:1};r={policyKey : "$fake"};"""
        for (step in listOf(1, 3, 7, 64, 8_192, 8_200, Int.MAX_VALUE)) assertEquals("step $step", fake, scan(script, step = step))
    }

    @Test fun noCandidateIsNull() {
        assertNull(scan("var a=1;function policy(){return 'key'};"))
        assertNull(scan(""))
        assertNull("an unterminated value at the end of the script", scan("""a={policyKey:"$fake"""))
        assertNull("over 400 characters", scan("""a={policyKey:"${"A".repeat(401)}"}"""))
    }

    @Test fun theDecodedCapStopsTheScan() {
        val script = "x".repeat(5_000) + """c={policyKey:"$fake"}"""
        assertNull(scan(script, max = 4_096))
        assertNull("the value is cut by the cap", scan(script, max = 5_020))
        assertEquals(fake, scan(script, max = 6_000))
        assertEquals(fake, scan(script, max = 6_000, step = 5))
    }

    @Test fun theLongestValidValueIsAccepted() {
        val long = "A".repeat(400)
        assertEquals(long, scan("""a={policyKey:"$long"}"""))
        val min = "B".repeat(20)
        assertEquals(min, scan("""a={policyKey:"$min"}"""))
        assertNull(scan("""a={policyKey:"${"B".repeat(19)}"}"""))
    }
}
