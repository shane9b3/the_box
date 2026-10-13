package app.thebox.core.xmltv

import app.thebox.core.xmltv.testutil.XmltvDump
import org.kxml2.io.KXmlParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * JVM half of I-8 (docs/design/m1-data-layer.md §5.7, review S4). Pins what `XmltvParser(::KXmlParser)` reports for
 * the parity fixtures in `xmltv/parity/<fixture>.txt`; the instrumented `I8ParserParityTest` checks that the
 * platform parser (`Xml.newPullParser()`) gives the same text. A missing golden is written to the source tree
 * (review it, then rerun). The androidTest asset copies must stay byte-identical to the fixtures and goldens here.
 */
class XmltvParityGoldenTest {
    private val fixtures = listOf("entities.xml", "latin1.xml", "basic.xml")

    @Test
    fun kxmlRunMatchesTheGoldenDumps() {
        for (name in fixtures) {
            val actual = res("xmltv/$name").inputStream().use { XmltvDump.dump(XmltvParser(::KXmlParser), it) }
            val golden = resOrNull("xmltv/parity/$name.txt")
            if (golden == null) {
                val f = File("src/test/resources/xmltv/parity/$name.txt").apply { parentFile.mkdirs(); writeText(actual) }
                fail("missing golden for $name: written to ${f.absolutePath}; review it and rerun")
            }
            assertEquals(golden.readText(), actual, "KXmlParser dump of $name")
        }
        // Sanity: the S4 behaviours the device run is compared on are in the goldens.
        val entities = res("xmltv/parity/entities.xml.txt").readText()
        assertTrue("Tom & Jerry été A\\u00A0B" in entities, "&nbsp; → U+00A0 pinned")
    }

    @Test
    fun androidTestAssetCopiesAreInSync() {
        val assets = File("../data/src/androidTest/assets/xmltv")
        if (!assets.isDirectory) return // :core built on its own
        for (name in fixtures) {
            for (path in listOf(name, "parity/$name.txt")) {
                val copy = File(assets, path)
                assertTrue(copy.isFile, "missing androidTest asset ${copy.path}")
                assertTrue(res("xmltv/$path").readBytes().contentEquals(copy.readBytes()), "${copy.path} differs from core's xmltv/$path; re-copy it")
            }
        }
    }

    private fun res(path: String): File = resOrNull(path) ?: fail("missing resource $path")

    private fun resOrNull(path: String): File? = javaClass.getResource("/$path")?.let { File(it.toURI()) }
}
