package app.thebox.core.xmltv.testutil

import app.thebox.core.xmltv.XmltvChannel
import app.thebox.core.xmltv.XmltvHandler
import app.thebox.core.xmltv.XmltvParser
import app.thebox.core.xmltv.XmltvProgramme
import java.io.InputStream

/**
 * Deterministic text dump of everything [XmltvParser] reports for a document: every channel, every field of every
 * accepted programme, bad-time drops and the end callback. Used by the I-8 parity check (docs/design/m1-data-layer.md
 * §5.7, review S4): the JVM run with `KXmlParser` pins `xmltv/parity/<fixture>.txt`, and the device run with
 * `Xml.newPullParser()` must produce the same text. Control characters and non-breaking spaces are escaped so a
 * difference (for example `&nbsp;` dropped instead of decoded to U+00A0) is visible in the golden file.
 */
object XmltvDump {
    fun dump(parser: XmltvParser, input: InputStream, options: XmltvParser.Options = XmltvParser.Options.DEFAULT): String {
        val out = StringBuilder()
        parser.parse(input, Collector(out), options = options)
        return out.toString()
    }

    private class Collector(private val out: StringBuilder) : XmltvHandler {
        override fun onChannel(c: XmltvChannel) {
            out.append("channel id=").append(esc(c.id)).append(" icon=").append(esc(c.iconUrl))
            for (n in c.displayNames) out.append(" name[").append(esc(n.lang)).append("]=").append(esc(n.name))
            out.append('\n')
        }

        override fun acceptProgramme(channelId: String, startMin: Int, stopMin: Int): Boolean = true

        override fun onProgramme(p: XmltvProgramme) {
            out.append("programme channel=").append(esc(p.channelId))
                .append(" start=").append(p.startMin).append(" stop=").append(p.stopMin).append('\n')
            field("title", p.title)
            field("subtitle", p.subtitle)
            field("description", p.description)
            field("categories", p.categories.joinToString(" | ") { esc(it) })
            field("episodeLabel", p.episodeLabel)
            field("season/episode/part/partTotal", "${p.season}/${p.episode}/${p.part}/${p.partTotal}")
            field("seriesRef", p.seriesRef)
            field("icon", p.iconUrl)
            field("rating", p.rating)
            field("starRating", p.starRating)
            field("year", p.year.toString())
            field("credits", p.credits)
            field("flags", p.flags.toString())
            field("catchupId", p.catchupId)
        }

        override fun onBadTime(channelId: String) {
            out.append("badTime channel=").append(esc(channelId)).append('\n')
        }

        override fun onEnd() {
            out.append("end\n")
        }

        private fun field(name: String, value: String?) {
            out.append("  ").append(name).append('=').append(esc(value)).append('\n')
        }
    }

    private fun esc(s: String?): String {
        if (s == null) return "<null>"
        val b = StringBuilder(s.length + 8)
        for (ch in s) {
            when {
                ch == '\\' -> b.append("\\\\")
                ch == '\n' -> b.append("\\n")
                ch < ' ' || ch == '\u007F' || ch == ' ' || ch in ' '..'‏' || ch == '﻿' ->
                    b.append("\\u").append(ch.code.toString(16).uppercase(java.util.Locale.ROOT).padStart(4, '0'))
                else -> b.append(ch)
            }
        }
        return b.toString()
    }
}
