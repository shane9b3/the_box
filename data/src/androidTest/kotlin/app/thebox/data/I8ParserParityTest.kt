package app.thebox.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I-8 (§5.7, S4): `XmltvParser(Xml::newPullParser)` on `entities.xml`, `latin1.xml`, `basic.xml` must give the
 * same `XmltvProgramme` values as the JVM KXmlParser run (`&nbsp;` → U+00A0, DOCTYPE skipped).
 *
 * Placeholder: `XmltvParser`/`XmltvHandler` are WP-B classes that do not exist on this branch, and the fixtures
 * live in core's test resources (not packaged into androidTest). Enabled when WP-B merges: copy the three
 * fixtures into `data/src/androidTest/assets/xmltv/` and compare against the expected values WP-B's JVM test pins.
 */
@Ignore("needs WP-B")
@RunWith(AndroidJUnit4::class)
class I8ParserParityTest {
    @Test
    fun platformParserMatchesKxml() = Unit
}
