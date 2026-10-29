package app.thebox.core.storage

import app.thebox.core.storage.ManualAddressParser.Reason
import app.thebox.core.storage.ManualAddressParser.Result.Invalid
import app.thebox.core.storage.ManualAddressParser.Result.Parsed
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * §16.1 `ManualAddressParserTest` (WP-S1): every §7.2 form and the errors. The phone page's mirror in app.js is checked
 * through `PhoneApiNetworkFolderTest.SPLIT_VECTORS` (its three fields, [ManualAddressParser.parseFields]).
 */
class ManualAddressParserTest {
    companion object {
        /** input → expected result. */
        val VECTORS: List<Pair<String, ManualAddressParser.Result>> = listOf(
            "nas" to Parsed("nas"),
            "  nas  " to Parsed("nas"),
            "nas.local" to Parsed("nas.local"),
            "192.168.1.20" to Parsed("192.168.1.20"),
            "192.168.1.20:4445" to Parsed("192.168.1.20", 4445),
            "\\\\NAS\\TV\\Rewind" to Parsed("NAS", share = "TV", path = "Rewind"),
            "\\\\NAS\\TV" to Parsed("NAS", share = "TV"),
            "\\\\NAS\\TV\\Recordings\\Rewind\\" to Parsed("NAS", share = "TV", path = "Recordings/Rewind"),
            "//nas/TV/Rewind" to Parsed("nas", share = "TV", path = "Rewind"),
            "smb://nas/TV/Rewind" to Parsed("nas", share = "TV", path = "Rewind"),
            "SMB://nas:4445/TV/" to Parsed("nas", 4445, share = "TV"),
            "smb://nas/My%20Videos/Live%20TV" to Parsed("nas", share = "My Videos", path = "Live TV"),
            "smb://10.0.2.2:4445/guest" to Parsed("10.0.2.2", 4445, share = "guest"),
            "DESKTOP-7F3K2LQ" to Parsed("DESKTOP-7F3K2LQ"),
            "nas/TV" to Parsed("nas", share = "TV"),
            "\\\\nas\\TV\\a\\\\b" to Parsed("nas", share = "TV", path = "a/b"),
            "" to Invalid(Reason.EMPTY),
            "   " to Invalid(Reason.EMPTY),
            "\\\\" to Invalid(Reason.EMPTY),
            "smb://" to Invalid(Reason.EMPTY),
            "http://nas/TV" to Invalid(Reason.UNSUPPORTED_SCHEME),
            "ftp://nas" to Invalid(Reason.UNSUPPORTED_SCHEME),
            "nas:0" to Invalid(Reason.BAD_PORT),
            "nas:65536" to Invalid(Reason.BAD_PORT),
            "nas:" to Invalid(Reason.BAD_PORT),
            "nas:44a" to Invalid(Reason.BAD_PORT),
            "192.168.1.300" to Invalid(Reason.BAD_HOST),
            "192.168.1" to Invalid(Reason.BAD_HOST),
            "user@nas" to Invalid(Reason.BAD_HOST),
            "smb://user@nas/TV" to Invalid(Reason.BAD_HOST),
            "na s" to Invalid(Reason.BAD_HOST),
            ".nas" to Invalid(Reason.BAD_HOST),
            "nas..local" to Invalid(Reason.BAD_HOST),
            "fe80::1" to Invalid(Reason.BAD_HOST),
            "smb://nas/%zz" to Invalid(Reason.BAD_HOST),
            // C-N4 (closure fix pass, R3): `.` dropped, `..` and control characters refused, IPv6 refused, names
            // start and end with a letter or digit, strict `%XX`.
            "\\\\nas\\TV\\.\\Rewind" to Parsed("nas", share = "TV", path = "Rewind"),
            "\\\\nas\\.\\TV" to Parsed("nas", share = "TV"),
            "smb://nas/Caf%C3%A9/./Live" to Parsed("nas", share = "Café", path = "Live"),
            "\\\\nas\\TV\\..\\x" to Invalid(Reason.BAD_PATH),
            "nas/.." to Invalid(Reason.BAD_PATH),
            "smb://nas/TV/%2e%2e" to Invalid(Reason.BAD_PATH),
            "smb://nas/TV/%00" to Invalid(Reason.BAD_PATH),
            "smb://nas/TV/a%0Ab" to Invalid(Reason.BAD_PATH),
            "nas/TV/a\u0001b" to Invalid(Reason.BAD_PATH),
            "nas/TV/Re\twind" to Invalid(Reason.BAD_PATH),
            "nas/TV/a\u007Fb" to Invalid(Reason.BAD_PATH),
            "[fe80::1]" to Invalid(Reason.BAD_HOST),
            "[fe80::1]:445/TV" to Invalid(Reason.BAD_HOST),
            "-nas" to Invalid(Reason.BAD_HOST),
            "nas-" to Invalid(Reason.BAD_HOST),
            "smb://nas/%+1" to Invalid(Reason.BAD_HOST),
            "smb://nas/%4" to Invalid(Reason.BAD_HOST),
            "bad host/../x" to Invalid(Reason.BAD_HOST),
        )
    }

    @Test
    fun everyVector() {
        for ((input, expected) in VECTORS) assertEquals(expected, ManualAddressParser.parse(input), "input \"$input\"")
    }

    /** The phone page's three fields (§7.4): the same grammar, a folder without a share refused. */
    @Test
    fun theThreeFieldsFollowTheSameRules() {
        assertEquals(Parsed("nas", share = "TV", path = "Rewind/Box"), ManualAddressParser.parseFields("nas", "TV\\Rewind", "Box"))
        assertEquals(Parsed("NAS2", share = "TV", path = "Rewind/Box"), ManualAddressParser.parseFields("nas", "\\\\NAS2\\TV\\Rewind", "Box"))
        assertEquals(Parsed("nas", 4445, share = "TV"), ManualAddressParser.parseFields("smb://nas:4445/Old/x", "TV", null))
        assertEquals(Invalid(Reason.BAD_PATH), ManualAddressParser.parseFields("nas", null, "Rewind"))
        assertEquals(Invalid(Reason.BAD_PATH), ManualAddressParser.parseFields("nas", "TV", "a/../b"))
        assertEquals(Invalid(Reason.BAD_PATH), ManualAddressParser.parseFields("nas", "TV\u0000", null))
        assertEquals(Invalid(Reason.BAD_HOST), ManualAddressParser.parseFields("[fe80::1]:445", "TV", null))
        assertEquals(Invalid(Reason.EMPTY), ManualAddressParser.parseFields("", "TV", null))
    }

    @Test
    fun theDefaultPortIs445() {
        assertEquals(StorageConstants.SMB_DEFAULT_PORT, (ManualAddressParser.parse("nas") as Parsed).port)
    }
}
