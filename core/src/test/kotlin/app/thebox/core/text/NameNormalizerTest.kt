package app.thebox.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** The §3.1 table of docs/design/m1-data-layer.md. forKey/forGroup feed persisted keys: frozen after M1. */
class NameNormalizerTest {
    private fun check(input: String, key: String, match: String) {
        assertEquals(key, NameNormalizer.forKey(input), "forKey($input)")
        assertEquals(match, NameNormalizer.forMatch(input), "forMatch($input)")
    }

    @Test
    fun designTable() {
        check("BBC One HD", "bbc one", "bbc one")
        check("BBC One FHD ᴴᴰ", "bbc one", "bbc one")
        check("UK: BBC One (Backup)", "uk bbc one", "bbc one")
        check("HBO (East)", "hbo east", "hbo")
        check("HBO (West)", "hbo west", "hbo")
        check("Channel 4 +1", "channel 4 +1", "channel 4 +1")
        check("Télé-Québec", "tele quebec", "tele quebec")
        check("|DE| Das Erste 4K", "de das erste", "das erste")
        check("HD", "hd", "hd")
    }

    @Test
    fun qualityTokensOnlyAsWholeWords() {
        assertEquals("hdtv", NameNormalizer.forKey("HDTV"))
        assertEquals("sky sports", NameNormalizer.forKey("Sky Sports 1080p HEVC"))
        assertEquals("shd", NameNormalizer.forKey("SHD"))
    }

    @Test
    fun nonIsoPrefixIsKept() {
        // "zz" is not an ISO-3166 code, so it is not a country prefix.
        assertEquals("zz bbc one", NameNormalizer.forMatch("ZZ: BBC One"))
    }

    @Test
    fun forGroupKeepsQualityAndBrackets() {
        // Review blocker B5: these are different provider groups.
        assertEquals("sports hd", NameNormalizer.forGroup("Sports HD"))
        assertEquals("sports sd", NameNormalizer.forGroup("Sports SD"))
        assertNotEquals(NameNormalizer.forGroup("Sports HD"), NameNormalizer.forGroup("Sports SD"))
        assertNotEquals(NameNormalizer.forGroup("UK HD"), NameNormalizer.forGroup("UK SD"))
        // forKey would fold them together, which is why groups must not use it.
        assertEquals(NameNormalizer.forKey("Sports HD"), NameNormalizer.forKey("Sports SD"))
        assertEquals("movies vip", NameNormalizer.forGroup("MOVIES | (VIP)"))
        assertEquals("cinema", NameNormalizer.forGroup("Cinéma"))
        assertEquals("cinema", NameNormalizer.forGroup("  CINÉMA  "))
        assertEquals("|", NameNormalizer.forGroup(" | "))
    }

    @Test
    fun compactAndTokens() {
        assertEquals("bbcone", NameNormalizer.compact("bbc one"))
        assertEquals(listOf("bbc", "one"), NameNormalizer.tokens("bbc one"))
        assertEquals(emptyList(), NameNormalizer.tokens(""))
    }

    @Test
    fun countryHint() {
        assertEquals("uk", NameNormalizer.countryHint("UK: BBC One", null))
        assertEquals("uk", NameNormalizer.countryHint("GB: BBC One", null))
        assertEquals("de", NameNormalizer.countryHint("|DE| Das Erste", null))
        assertEquals("uk", NameNormalizer.countryHint("BBC One", "BBCOne.uk"))
        assertEquals("fr", NameNormalizer.countryHint(null, "TF1.fr@SD"))
        assertNull(NameNormalizer.countryHint("BBC One", "bbc1"))
        assertNull(NameNormalizer.countryHint(null, null))
    }
}
