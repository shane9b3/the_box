package app.thebox.core.storage

import app.thebox.core.storage.ManualAddressParser.Reason
import app.thebox.core.storage.ManualAddressParser.Result.Invalid
import app.thebox.core.storage.ManualAddressParser.Result.Parsed
import kotlin.test.Test
import kotlin.test.assertEquals

/** §16.1 `ManualAddressParserTest` (WP-S1): every §7.2 form and the errors. WP-S6's app.js runs the same [VECTORS]. */
class ManualAddressParserTest {
    companion object {
        /** input → expected result. Shared with the phone page's mirror (PhoneApiTest fixtures, WP-S6). */
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
        )
    }

    @Test
    fun everyVector() {
        for ((input, expected) in VECTORS) assertEquals(expected, ManualAddressParser.parse(input), "input \"$input\"")
    }

    @Test
    fun theDefaultPortIs445() {
        assertEquals(StorageConstants.SMB_DEFAULT_PORT, (ManualAddressParser.parse("nas") as Parsed).port)
    }
}
