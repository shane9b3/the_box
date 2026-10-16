package app.thebox.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/design/m3-guide.md §3.12 (the §3.12.1 table, the lead sign-off examples and counter-examples, §3.12.2). */
class ChannelNamePrefixTest {
    private val p = ChannelNamePrefixImpl()

    private fun auto(name: String, group: String? = null): String? = p.strip(name, group, NamePrefixRule.Auto)

    @Test
    fun designTable() {
        val rows = listOf(
            Triple("UK: BBC One HD", "UK Entertainment", "BBC One HD"),
            Triple("UK | Sky Sports Main Event", "Sports", "Sky Sports Main Event"),
            Triple("UK|ITV 1", null, "ITV 1"),
            Triple("DE - ZDF", null, "ZDF"),
            Triple("[FR] TF1", null, "TF1"),
            Triple("(es) La 1", null, "La 1"),
            Triple("NO: NRK1", null, "NRK1"),
            Triple("UK:BBC Two", null, "BBC Two"),
            Triple("SPORTS: ESPN", "Sports", "ESPN"),
            Triple("Sky Sports: F1", "Sky Sports", null),
            Triple("DE: DE: Das Erste", null, "DE: Das Erste"),
            Triple("USA: CNN", null, "CNN"),
            Triple("[GBR] BBC One", null, "BBC One"),
            Triple("AND: Then There Were None", null, null),
            Triple("UK-Gold", null, null),
            Triple("E! Entertainment", null, null),
            Triple("BBC One - HD", null, null),
            Triple("UK: ", null, null),
            Triple("UK | ---", null, null),
            Triple("TV: Live Events", null, null),
            Triple("FM: Radio Hits", "Radio", null),
            Triple("4K: Planet Earth", "4K", "Planet Earth"),
            Triple("4K: Planet Earth", "Documentaries", null),
        )
        for ((name, group, want) in rows) assertEquals(want, auto(name, group), "\"$name\" in \"$group\"")
    }

    @Test
    fun signOffExamplesAndCounterExamples() {
        assertEquals("BBC One", auto("UK: BBC One"))
        assertEquals("BBC One", auto("UK:BBC One"))
        assertEquals("ITV", auto("UK | ITV"))
        assertEquals("CNN", auto("[US] CNN"))
        assertEquals("CNN", auto("USA: CNN"))
        assertNull(auto("TV: Something"), "TV is Tuvalu, excluded")
        assertNull(auto("Sky Sports: F1", "Sky Sports"), "tag equals the group but has lower-case letters")
        assertEquals("F1", auto("SKY SPORTS: F1", "Sky Sports"), "upper-case tag equal to the group")
        assertNull(auto("SKY SPORTS: F1", "Football"), "upper-case tag that is not the group")
        assertNull(auto("SKY SPORTS: F1"), "no group")
    }

    @Test
    fun exclusionsAndEdgeForms() {
        for (t in listOf("TV", "FM", "AM", "AND", "ARE", "PER")) assertNull(auto("$t: Something"), t)
        for (t in listOf("US", "IN", "CAN", "MAR", "UK", "GB", "uk")) assertEquals("Something", auto("$t: Something"), t)
        assertEquals("BBC", auto("  [UK]: BBC  "), "bracket then a separator")
        assertEquals("BBC", auto("(UK) - BBC"))
        assertNull(auto("[UK BBC One"), "unclosed bracket")
        assertNull(auto("[" + "X".repeat(40) + "] BBC"), "tag longer than 32 chars")
        assertNull(auto("UKX: BBC"), "not a code")
        assertNull(auto("U1: BBC"), "digits are not letters")
        assertNull(auto(""))
        assertNull(auto("   "))
        assertNull(auto(": BBC", ""), "empty tag never equals an empty group")
        assertEquals("BBC One", auto("UK:   BBC One   "))
        assertNull(auto("Plain Name"))
    }

    @Test
    fun offIsAlwaysUnchanged() {
        assertNull(p.strip("UK: BBC One", null, NamePrefixRule.Off))
    }

    @Test
    fun customLiteralsLongestFirstCaseInsensitive() {
        val rule = NamePrefixRule.Custom(literals = listOf("VIP", "VIP | UK", "UK: "))
        assertEquals("BBC One", p.strip("vip | uk: BBC One", null, rule), "longest literal wins, then one separator")
        assertEquals("BBC One", p.strip("VIP - BBC One", null, rule))
        assertEquals("BBC One", p.strip("uk: BBC One", null, rule))
        assertNull(p.strip("ITV", null, rule))
        assertNull(p.strip("VIP | UK", null, rule), "empty rest")
        assertNull(p.strip("VIP | ---", null, rule), "rest without letter or digit")
        assertNull(p.strip("TV: Live", null, rule), "literals replace Auto: nothing else is stripped")
    }

    /** (M5 integration, G-L19) An exponential shape is refused up front (RegexSafety) and the rule falls back to Auto. */
    @Test
    fun riskyCustomRegexFallsBackToAuto() {
        val rule = NamePrefixRule.Custom(regex = "(\\w+\\s?)*:")
        assertEquals(auto("UK: BBC One"), p.strip("UK: BBC One", null, rule))
        assertEquals(auto("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa!"), p.strip("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa!", null, rule))
        assertTrue(app.thebox.core.setup.pages.PrefixRuleText.risky("/(a+)+/"))
        assertTrue(!app.thebox.core.setup.pages.PrefixRuleText.risky("/\\[[A-Z]+\\]/"))
    }

    @Test
    fun customRegexAndPreviewCount() {
        val rule = NamePrefixRule.Custom(regex = "\\[[A-Z]+\\]\\s*")
        assertEquals("CNN", p.strip("[US] CNN", null, rule))
        assertEquals("Sky", p.strip("[VIPHD] Sky", null, rule))
        assertNull(p.strip("CNN [US]", null, rule), "lookingAt anchors at the start")
        assertNull(p.strip("[US]", null, rule))
        val names = sequenceOf("[US] CNN" to null, "[UK] BBC" to null, "ITV" to null, "[x] lower" to null, "[DE]" to null)
        assertEquals(2, p.countChanged(names, rule))
        assertEquals(2, p.countChanged(sequenceOf("UK: A" to null, "TV: B" to null, "USA|C" to null), NamePrefixRule.Auto))
    }

    @Test
    fun badRegexFallsBackToAuto() {
        val rule = NamePrefixRule.Custom(regex = "([unclosed")
        assertEquals("BBC One", p.strip("UK: BBC One", null, rule))
        assertNull(p.strip("Plain", null, rule))
    }

    @Test
    fun regexSeesAtMost256Chars() {
        val rule = NamePrefixRule.Custom(regex = "a{300}")
        val name = "a".repeat(300) + " BBC"
        assertNull(p.strip(name, null, rule), "the 300-char prefix cannot match inside the 256-char window")
        val rule2 = NamePrefixRule.Custom(regex = "a{10}")
        assertEquals("a".repeat(290) + " BBC", p.strip(name, null, rule2))
    }

    @Test
    fun parseRules() {
        assertEquals(NamePrefixRule.Auto, NamePrefixRules.parse("AUTO", null))
        assertEquals(NamePrefixRule.Off, NamePrefixRules.parse("OFF", null))
        assertEquals(NamePrefixRule.Off, NamePrefixRules.parse("off", "{\"literals\":[\"x\"]}"))
        assertEquals(NamePrefixRule.Custom(literals = listOf("UK: ", "VIP | ")), NamePrefixRules.parse("CUSTOM", "{\"literals\":[\"UK: \",\"VIP | \"]}"))
        assertEquals(NamePrefixRule.Custom(regex = "^\\[[A-Z]+\\]\\s*"), NamePrefixRules.parse("CUSTOM", "{\"regex\":\"^\\\\[[A-Z]+\\\\]\\\\s*\"}"))
        assertEquals(NamePrefixRule.Auto, NamePrefixRules.parse("CUSTOM", "{\"literals\":[\"a\"],\"regex\":\"b\"}"), "both keys")
        assertEquals(NamePrefixRule.Auto, NamePrefixRules.parse("CUSTOM", "{not json"))
        assertEquals(NamePrefixRule.Auto, NamePrefixRules.parse("CUSTOM", null))
        assertEquals(NamePrefixRule.Auto, NamePrefixRules.parse("CUSTOM", "{\"literals\":[]}"))
        assertEquals(NamePrefixRule.Auto, NamePrefixRules.parse("WHATEVER", null))
        for (r in listOf(NamePrefixRule.Auto, NamePrefixRule.Off, NamePrefixRule.Custom(literals = listOf("A: ")), NamePrefixRule.Custom(regex = "x+"))) {
            val (mode, json) = NamePrefixRules.encode(r)
            assertEquals(r, NamePrefixRules.parse(mode, json))
        }
    }

    @Test
    fun isoListsAreFixedAndComplete() {
        assertEquals(249, ChannelNamePrefixImpl.ISO_ALPHA2.size)
        assertEquals(249, ChannelNamePrefixImpl.ISO_ALPHA3.size)
        assertTrue(ChannelNamePrefixImpl.EXCLUDED_TAGS.all { it in ChannelNamePrefixImpl.ISO_ALPHA2 || it in ChannelNamePrefixImpl.ISO_ALPHA3 })
    }

    /** The CPU part of the recompute pass (§3.12.3) over 200k names on the JVM. Logged; loose bound as a regression guard. */
    @Test
    fun recompute200kTiming() {
        val names = Array(200_000) { i ->
            when (i % 5) {
                0 -> "UK: Channel $i HD"
                1 -> "[DE] Sender $i"
                2 -> "Plain Channel $i"
                3 -> "Sky Sports: Match $i"
                else -> "USA|News $i"
            }
        }
        repeat(2) { for (n in names) auto(n, "Sky Sports") } // warm-up
        val t0 = System.nanoTime()
        var changed = 0
        for (n in names) if (auto(n, "Sky Sports") != null) changed++
        val ms = (System.nanoTime() - t0) / 1e6
        println("ChannelNamePrefix: 200k Auto strips in ${"%.1f".format(ms)} ms ($changed changed)")
        assertEquals(120_000, changed)
        // Wall-clock bound only as a hang guard: this runs in the regular suite, often next to other builds.
        assertTrue(ms < 30_000, "200k strips took $ms ms")
    }
}
