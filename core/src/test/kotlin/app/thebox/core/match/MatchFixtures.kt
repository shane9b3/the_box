package app.thebox.core.match

import app.thebox.core.epg.EpgChannelInfo
import app.thebox.core.model.StableKey
import app.thebox.core.refresh.ChannelIdentity
import kotlin.random.Random

internal object MatchFixtures {
    fun subject(name: String, tvgId: String? = null, tvgName: String? = null, key: String = name, playlistId: Long = 1) =
        MatchSubject(ChannelIdentity(playlistId, StableKey("ng:$key")), name, tvgId, tvgName, null)

    fun epg(xmltvId: String, vararg names: String, programmes: Int = 10, sourceId: Long = 1) =
        EpgChannelInfo(sourceId, xmltvId, names.toList().ifEmpty { listOf(xmltvId) }, null, true, programmes)

    private val GENERIC = listOf("tv", "news", "sport", "movies", "kids", "music", "one", "plus", "channel", "hd", "max", "cinema", "24")
    private val QUALITY = listOf("", "", "", " HD", " FHD", " 4K", " SD")
    private val COUNTRIES = listOf("uk", "us", "de", "fr", "es", "it", "nl", "pl", "pt", "ca")
    private const val LETTERS = "bcdfghjklmnprstvwz"
    private const val VOWELS = "aeiou"

    /** A pronounceable random word of 2–4 syllables (deterministic for a given [rnd]). */
    fun word(rnd: Random): String = buildString {
        repeat(2 + rnd.nextInt(3)) {
            append(LETTERS[rnd.nextInt(LETTERS.length)])
            append(VOWELS[rnd.nextInt(VOWELS.length)])
        }
    }

    class Corpus(val subjects: List<MatchSubject>, val channels: List<EpgChannelInfo>)

    /**
     * [subjects] library channels and [epgChannels] EPG channels. About 60 % of the subjects have a
     * counterpart in the EPG: exact id, case-changed id, iptv-org feed id, the same name with a quality
     * suffix or country prefix, or a one-letter typo. The rest of the EPG is unrelated names.
     */
    fun corpus(subjects: Int, epgChannels: Int, seed: Int = 42): Corpus {
        val rnd = Random(seed)
        val subs = ArrayList<MatchSubject>(subjects)
        val chans = ArrayList<EpgChannelInfo>(epgChannels)
        val brands = HashSet<String>()
        fun brand(): String {
            while (true) {
                val w = word(rnd)
                if (brands.add(w)) return w
            }
        }
        for (i in 0 until subjects) {
            val b = brand()
            val g = GENERIC[rnd.nextInt(GENERIC.size)]
            val num = if (rnd.nextInt(5) == 0) " ${1 + rnd.nextInt(5)}" else ""
            val cc = COUNTRIES[rnd.nextInt(COUNTRIES.size)]
            val base = "${b.replaceFirstChar { it.uppercase() }} ${g.replaceFirstChar { it.uppercase() }}$num"
            val prefix = if (rnd.nextInt(4) == 0) "${cc.uppercase()}: " else ""
            val name = prefix + base + QUALITY[rnd.nextInt(QUALITY.size)]
            val id = "${b.replaceFirstChar { it.uppercase() }}${g.replaceFirstChar { it.uppercase() }}${num.trim()}.$cc"
            val hasId = rnd.nextInt(3) != 0
            subs += MatchSubject(ChannelIdentity(1, StableKey("s:$i")), name, if (hasId) id else null, null, null)
            if (chans.size < epgChannels && rnd.nextInt(10) < 6) {
                val variant = rnd.nextInt(6)
                chans += when (variant) {
                    0 -> epg(id, base, programmes = rnd.nextInt(3) * 10)
                    1 -> epg(id.lowercase(), base)
                    2 -> epg("$id@HD", "$base HD")
                    3 -> epg("x$i.$cc", base + QUALITY[rnd.nextInt(QUALITY.size)])
                    4 -> epg("y$i.$cc", typo(base, rnd))
                    else -> epg("z$i.$cc", "$base ${word(rnd)}")
                }
            }
        }
        var j = 0
        while (chans.size < epgChannels) {
            val names = Array(1 + rnd.nextInt(2)) { "${brand().replaceFirstChar { c -> c.uppercase() }} ${GENERIC[rnd.nextInt(GENERIC.size)]}" }
            chans += epg("other${j++}.${COUNTRIES[rnd.nextInt(COUNTRIES.size)]}", *names)
        }
        chans.shuffle(rnd)
        return Corpus(subs, chans)
    }

    private fun typo(s: String, rnd: Random): String {
        val letters = s.indices.filter { s[it].isLetter() }
        val at = letters[rnd.nextInt(letters.size)]
        return s.substring(0, at) + s.substring(at + 1)
    }
}
