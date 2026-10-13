package app.thebox.data

import android.util.Xml
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.thebox.core.xmltv.XmltvParser
import app.thebox.core.xmltv.testutil.XmltvDump
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I-8 (§5.7, review S4): `XmltvParser(Xml::newPullParser)` on `entities.xml`, `latin1.xml` and `basic.xml` gives the
 * same values as the JVM `KXmlParser` run, including `&nbsp;` → U+00A0 and the DOCTYPE skipped.
 *
 * The expected text is the JVM dump pinned by core's `XmltvParityGoldenTest` (`xmltv/parity/<fixture>.txt`); the
 * fixtures and goldens are copied into `assets/xmltv/` and that test fails if the copies drift.
 */
@RunWith(AndroidJUnit4::class)
class I8ParserParityTest {
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    private fun read(path: String): String = assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun deviceDump(name: String): String =
        assets.open("xmltv/$name").use { XmltvDump.dump(XmltvParser(Xml::newPullParser), it) }

    @Test
    fun entitiesMatchKxml() {
        val dump = deviceDump("entities.xml")
        assertEquals(read("xmltv/parity/entities.xml.txt"), dump)
        assertTrue("&nbsp; decodes to U+00A0 on the platform parser", "Tom & Jerry été A\\u00A0B" in dump)
    }

    @Test
    fun latin1MatchesKxml() = assertEquals(read("xmltv/parity/latin1.xml.txt"), deviceDump("latin1.xml"))

    @Test
    fun basicMatchesKxml() = assertEquals(read("xmltv/parity/basic.xml.txt"), deviceDump("basic.xml"))
}
