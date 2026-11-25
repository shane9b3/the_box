package app.thebox.core.sports.hub

import app.thebox.core.events.EpgFiller
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.Market
import app.thebox.core.sports.NhlTeamPlaces
import app.thebox.core.sports.Sport
import app.thebox.core.sports.TeamLine
import app.thebox.core.sports.TvBroadcast
import app.thebox.core.sports.sport
import app.thebox.core.text.NameNormalizer
import java.util.Locale

/**
 * (Sports Hub, docs/design/m6-sports-hub.md §5.2 A; WP-3; A-B3, R-S2, R-S3) The broadcaster alias table and its pure
 * matcher.
 *
 * A channel matches a broadcaster code only when its **residue** (the name folded, a leading or bracketed country tag
 * from [isCountryTag] removed, split on anything but letters, digits and `+`, trailing quality and noise tokens dropped)
 * **equals** one of the code's aliases, or an alias plus one region word from that code's list. No prefix and no
 * subsequence matching: "Sportsnet 360" is not SN, "ESPN 2" is not ESPN, "MSG Sportsnet" is not MSG.
 *
 * Country tags come from an explicit list (ISO-3166 alpha-2 plus the provider spellings `UK`, `USA`, `CAN`, `LAT`, `EU`,
 * `ARB`, `AFR`, `EX-YU`) **minus every alias token of the table**, so "SN - Ontario" and "TSN: 3" keep their network
 * (SN is also Senegal in ISO). A tag that names another country than the broadcast drops the channel; no tag lowers the
 * level by one.
 *
 * Streaming-only codes (SN+, Prime Video, HBO MAX, ESPN+, any code with a `+`) and local over-the-air affiliates (ABC,
 * CBS, FOX, NBC, CTV, CBC, Citytv, call signs such as KONG) have **no aliases**: affiliates are found only through the
 * guide probes (R-S3). Plain string handling only (no regex): Android's ICU regex is stricter than the JVM's.
 */
object BroadcastAliases {

    /** What the matcher reads from a channel name. [country]: upper-case ISO-2 (or a region tag such as `LAT`) of [tag]. */
    data class Residue(val tag: String?, val country: String?, val tokens: List<String>) {
        val text: String get() = tokens.joinToString(" ")
    }

    /**
     * One channel–broadcast match. [key] is the canonical form shared by the quality variants and the spellings of one
     * network feed ("CA| SPORTSNET ONTARIO HD", "SN - Ontario" → "sportsnet ontario"); [national] is false for a regional
     * code or an H / A market.
     */
    data class AliasMatch(
        val code: String,
        val confidence: MatchConfidence,
        val national: Boolean,
        val key: String,
        val tagged: Boolean,
    )

    /** How a broadcaster code is treated. */
    enum class CodeKind { KNOWN, STREAMING, AFFILIATE, UNKNOWN }

    /** The FTS4 `MATCH` text for [network]'s aliases (quoted phrases joined by `OR`, no `*`), or null when it has none. */
    fun ftsMatch(network: String): String? {
        val code = codeOf(network)
        val phrases = when (kindOf(code)) {
            CodeKind.KNOWN -> TABLE.getValue(code).phrases
            CodeKind.UNKNOWN -> unknownAlias(code)?.let { listOf(it) } ?: return null
            CodeKind.STREAMING, CodeKind.AFFILIATE -> return null
        }
        return phrases.joinToString(" OR ") { "\"$it\"" }
    }

    /** How a channel named [channelName] matches [broadcast], or null for no match. */
    fun match(channelName: String, broadcast: TvBroadcast): AliasMatch? = match(residue(channelName), broadcast)

    fun match(r: Residue, broadcast: TvBroadcast): AliasMatch? {
        if (r.tokens.isEmpty()) return null
        val code = codeOf(broadcast.network)
        val (base, national, key, country) = when (kindOf(code)) {
            CodeKind.KNOWN -> {
                val e = TABLE.getValue(code)
                val key = e.keyFor(r.tokens) ?: return null
                Quad(e.level, e.national, key, e.country)
            }
            CodeKind.UNKNOWN -> {
                val alias = unknownAlias(code) ?: return null
                if (r.text != alias) return null
                Quad(MatchConfidence.POSSIBLE, false, alias, null)
            }
            CodeKind.STREAMING, CodeKind.AFFILIATE -> return null
        }
        val expected = broadcast.country?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: country
        if (r.country != null && expected != null && r.country != expected) return null
        val regional = !national || broadcast.market != Market.NATIONAL
        var level = if (regional) MatchConfidence.POSSIBLE else base
        if (r.tag == null) level = lower(level)
        return AliasMatch(code, level, !regional, key, tagged = r.tag != null)
    }

    fun kindOf(network: String): CodeKind {
        val code = codeOf(network)
        return when {
            code.isEmpty() -> CodeKind.UNKNOWN
            code in TABLE -> CodeKind.KNOWN
            '+' in code || code in STREAMING -> CodeKind.STREAMING
            code in AFFILIATES || isCallSign(code) -> CodeKind.AFFILIATE
            else -> CodeKind.UNKNOWN
        }
    }

    /** Codes with aliases (tests, the alias coverage note). */
    val codes: Set<String> get() = TABLE.keys

    // ------------------------------------------------------------------ residue

    /** The residue of [name] (§5.2 A rule 1). */
    fun residue(name: String): Residue {
        var s = NameNormalizer.fold(name).trim()
        var tag: String? = null
        // Leading "[CA] …" / "(CA) …".
        if (s.startsWith("[") || s.startsWith("(")) {
            val close = s.indexOf(if (s[0] == '[') ']' else ')')
            if (close > 0) {
                val inner = s.substring(1, close).trim()
                if (isCountryTag(inner)) { tag = inner; s = s.substring(close + 1).trim() }
            }
        }
        // Leading "CA| …", "|CA| …", "US: …", "UK - …", "EX-YU: …".
        if (tag == null) {
            var i = 0
            while (i < s.length && (s[i] == '|' || s[i] == ' ')) i++
            val start = i
            val candidate: String? = if (s.startsWith("ex-yu", i)) {
                i += 5
                "ex-yu"
            } else {
                while (i < s.length && s[i] in 'a'..'z') i++
                if (i > start) s.substring(start, i) else null
            }
            if (candidate != null) {
                var j = i
                while (j < s.length && s[j] == ' ') j++
                if (j < s.length && s[j] in TAG_SEPARATORS && isCountryTag(candidate)) {
                    tag = candidate
                    s = s.substring(j + 1).trim()
                }
            }
        }
        // Trailing "… [CA]" / "… (CA)".
        if (tag == null && (s.endsWith("]") || s.endsWith(")"))) {
            val open = s.lastIndexOf(if (s.last() == ']') '[' else '(')
            if (open >= 0) {
                val inner = s.substring(open + 1, s.length - 1).trim()
                if (isCountryTag(inner)) { tag = inner; s = s.substring(0, open).trim() }
            }
        }
        val tokens = tokenize(s)
        dropTrailingNoise(tokens)
        return Residue(tag, tag?.let(::countryOfTag), tokens)
    }

    /** True for a token accepted as a country tag (R-S2): listed, and never an alias token of the table. */
    fun isCountryTag(token: String): Boolean {
        val t = token.lowercase(Locale.ROOT)
        return (t in ISO2 || t in PROVIDER_TAGS) && t !in ALIAS_TOKENS
    }

    private fun countryOfTag(tag: String): String = when (tag) {
        "uk" -> "GB"
        "usa" -> "US"
        "can" -> "CA"
        else -> tag.uppercase(Locale.ROOT)
    }

    /** Letters, digits and `+` make tokens; a token of `+` alone is dropped. */
    private fun tokenize(s: String): MutableList<String> {
        val out = ArrayList<String>(6)
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotEmpty() && sb.any { it != '+' }) out += sb.toString()
            sb.setLength(0)
        }
        for (ch in s) if (ch.isLetterOrDigit() || ch == '+') sb.append(ch) else flush()
        flush()
        return out
    }

    private fun dropTrailingNoise(t: MutableList<String>) {
        while (t.size > 1) {
            val last = t.last()
            when {
                last in NOISE -> t.removeAt(t.lastIndex)
                // "H.265" / "H 264" split into two tokens; "50 FPS" too.
                (last == "265" || last == "264") && t[t.lastIndex - 1] == "h" && t.size > 2 -> { t.removeAt(t.lastIndex); t.removeAt(t.lastIndex) }
                last == "fps" && t.size > 2 && t[t.lastIndex - 1].all { it.isDigit() } -> { t.removeAt(t.lastIndex); t.removeAt(t.lastIndex) }
                else -> return
            }
        }
    }

    // ------------------------------------------------------------------ the table

    private class Entry(
        val code: String,
        /** Residues that match as they are. */
        val exact: List<String>,
        /** Residues that match followed by one of [regions]. */
        val stems: List<String> = emptyList(),
        val regions: List<String> = emptyList(),
        val national: Boolean,
        val level: MatchConfidence,
        /** The broadcast's country when the feed gives none. */
        val country: String?,
        /** The canonical key of an exact match; a region match is "[family] region". */
        val key: String = exact.first(),
        val family: String = stems.firstOrNull() ?: key,
    ) {
        /** FTS phrases: the exact aliases and stem + region, without a phrase another one already covers. */
        val phrases: List<String> = run {
            val all = (exact + stems.flatMap { s -> regions.map { "$s $it" } }).distinct()
            all.filter { p -> all.none { q -> q != p && p.startsWith("$q ") } }
        }

        fun keyFor(tokens: List<String>): String? {
            val text = tokens.joinToString(" ")
            if (text in exact) return key
            if (tokens.size >= 2 && regions.isNotEmpty()) {
                val region = tokens.last()
                if (region in regions) {
                    val head = tokens.subList(0, tokens.size - 1).joinToString(" ")
                    if (head in stems || head in exact) return "$family $region"
                }
            }
            return null
        }
    }

    private data class Quad(val level: MatchConfidence, val national: Boolean, val key: String, val country: String?)

    private val L = MatchConfidence.LIKELY
    private val P = MatchConfidence.POSSIBLE

    private val SN_REGIONS = listOf("ontario", "east", "west", "pacific")

    private fun national(code: String, country: String, vararg exact: String) =
        Entry(code, exact.toList(), national = true, level = L, country = country)

    private fun regional(code: String, country: String?, vararg exact: String) =
        Entry(code, exact.toList(), national = false, level = P, country = country)

    /** One regional Sportsnet feed under its own NHL code (SNO, SNE, SNW, SNP): "sportsnet ontario", "sn ontario", "sno". */
    private fun snRegional(code: String, region: String) =
        Entry(code, listOf(code.lowercase(Locale.ROOT)), stems = listOf("sportsnet", "sn"), regions = listOf(region), national = false, level = P, country = "CA", key = "sportsnet $region", family = "sportsnet")

    /** FanDuel Sports Network regionals (and their Bally Sports codes): "fanduel sports [network] <region>", "bally sports <region>". */
    private fun fanDuel(codes: List<String>, region: String): List<Entry> = codes.map { code ->
        Entry(
            code,
            listOf("fanduel sports $region", "fanduel sports network $region", "fdsn $region", "bally sports $region"),
            national = false, level = P, country = "US", key = "fanduel sports $region",
        )
    }

    private val ENTRIES: List<Entry> = listOf(
        // Canada
        Entry("SN", listOf("sportsnet"), stems = listOf("sportsnet", "sn"), regions = SN_REGIONS, national = true, level = L, country = "CA"),
        snRegional("SNO", "ontario"),
        snRegional("SNE", "east"),
        snRegional("SNW", "west"),
        snRegional("SNP", "pacific"),
        national("SN1", "CA", "sportsnet one", "sn1", "sportsnet 1"),
        national("SN360", "CA", "sportsnet 360", "sn360", "sn 360"),
        national("TVAS", "CA", "tva sports", "tvas"),
        national("TVAS2", "CA", "tva sports 2", "tvas2", "tvas 2"),
        // TSN / RDS are seeded at POSSIBLE at most (A-N7); bare TSN is the NFL set's national code.
        national("TSN", "CA", "tsn"),
        regional("TSN1", "CA", "tsn 1", "tsn1"),
        regional("TSN2", "CA", "tsn 2", "tsn2"),
        regional("TSN3", "CA", "tsn 3", "tsn3"),
        regional("TSN4", "CA", "tsn 4", "tsn4"),
        regional("TSN5", "CA", "tsn 5", "tsn5"),
        regional("RDS", "CA", "rds"),
        regional("RDS2", "CA", "rds 2", "rds2"),
        // United States, national
        national("TNT", "US", "tnt"),
        national("TRUTV", "US", "trutv", "tru tv"),
        national("ESPN", "US", "espn"),
        national("ESPN2", "US", "espn2", "espn 2"),
        national("NHLN", "US", "nhl network", "nhln"),
        national("NFLN", "US", "nfl network", "nfln"),
        // United States, regional
        regional("MSG", "US", "msg", "msg network"),
        regional("MSGSN", "US", "msg sportsnet", "msgsn"),
        regional("MSG2", "US", "msg2", "msg 2"),
        regional("MSGSN2", "US", "msg sportsnet 2", "msgsn2"),
        regional("NESN", "US", "nesn"),
        regional("ALT", "US", "altitude", "altitude sports"),
        regional("NBCSCA", "US", "nbc sports california"),
        regional("NBCSBA", "US", "nbc sports bay area"),
        regional("NBCSP", "US", "nbc sports philadelphia"),
        regional("NBCSWA", "US", "nbc sports washington"),
        regional("MNMT", "US", "monumental sports network", "monumental sports"),
        regional("CHSN", "US", "chicago sports network"),
        regional("SNPIT", "US", "sportsnet pittsburgh"),
    ) + listOf(
        listOf("FDSNSO", "BSSO") to "south",
        listOf("FDSNSE", "BSSE") to "southeast",
        listOf("FDSNDET", "BSDET") to "detroit",
        listOf("FDSNFL", "BSFL") to "florida",
        listOf("FDSNSUN", "BSSUN") to "sun",
        listOf("FDSNMW", "BSMW") to "midwest",
        listOf("FDSNN", "FDSNNO", "BSN") to "north",
        listOf("FDSNOH", "BSOH") to "ohio",
        listOf("FDSNWI", "BSWI") to "wisconsin",
        listOf("FDSNW", "BSW") to "west",
        listOf("FDSNSC", "BSSC") to "socal",
        listOf("FDSNSW", "BSSW") to "southwest",
    ).flatMap { (codes, region) -> fanDuel(codes, region) }

    private val TABLE: Map<String, Entry> = ENTRIES.associateBy { it.code }

    /** Streaming-only services (rule 4); any code containing `+` is one too. */
    private val STREAMING = setOf(
        "SN NOW", "SNNOW", "PRIME VIDEO", "PRIME", "AMAZON", "AMAZON PRIME VIDEO", "HBO MAX", "MAX", "PEACOCK", "NETFLIX",
        "APPLE TV", "YOUTUBE", "YOUTUBE TV", "DAZN", "FUBO", "SLING", "NHL.TV", "NHL POWER PLAY", "VICTORY", "ESPN APP",
    )

    /** Over-the-air networks and stations (rule 6, R-S3): guide only. Call signs are recognised by [isCallSign]. */
    private val AFFILIATES = setOf(
        "ABC", "CBS", "FOX", "NBC", "CTV", "CBC", "CITY", "CITYTV", "OMNI", "GLOBAL", "CW", "ION", "MNT", "MYNETWORKTV",
        "TELEMUNDO", "UNIVISION", "TSN-OTA", "RADIO-CANADA", "SRC", "TVA", "NOOVO", "KONG", "KCOP",
    )

    /** A US call sign (3 or 4 letters starting with K or W, optionally followed by digits: "KONG", "WGN", "KTVD2"). */
    private fun isCallSign(code: String): Boolean {
        if (code.length !in 3..6 || (code[0] != 'K' && code[0] != 'W')) return false
        var i = 0
        while (i < code.length && code[i] in 'A'..'Z') i++
        if (i !in 3..4) return false
        while (i < code.length && code[i].isDigit()) i++
        return i == code.length
    }

    private fun codeOf(network: String): String = network.trim().uppercase(Locale.ROOT)

    /** Rule 5: an unknown code matches only a residue equal to the code itself, of at least 3 letters or digits. */
    private fun unknownAlias(code: String): String? {
        val t = tokenize(NameNormalizer.fold(code))
        val alias = t.joinToString(" ")
        return alias.takeIf { a -> a.count { it.isLetterOrDigit() } >= 3 && '+' !in a }
    }

    private fun lower(c: MatchConfidence): MatchConfidence = when (c) {
        MatchConfidence.CONFIRMED -> MatchConfidence.LIKELY
        MatchConfidence.LIKELY, MatchConfidence.POSSIBLE -> MatchConfidence.POSSIBLE
    }

    private const val TAG_SEPARATORS = "|:-–—"

    private val NOISE = setOf(
        "hd", "fhd", "uhd", "sd", "4k", "8k", "hevc", "h265", "h264", "raw", "50fps", "60fps", "backup", "alt", "1080p",
        "1080i", "720p", "2160p", "hdr", "hd+", "fhd+", "uhd+",
    )

    private val ISO2: Set<String> = Locale.getISOCountries().mapTo(HashSet()) { it.lowercase(Locale.ROOT) }

    private val PROVIDER_TAGS = setOf("uk", "usa", "can", "lat", "eu", "arb", "afr", "ex-yu")

    /** Every token of every alias, code and no-alias network: never a country tag (R-S2). */
    private val ALIAS_TOKENS: Set<String> = buildSet {
        fun addAll(s: String) { addAll(s.lowercase(Locale.ROOT).split(' ', '+', '-', '.').filter { it.isNotEmpty() }) }
        for (e in ENTRIES) {
            addAll(e.code)
            e.exact.forEach(::addAll)
            e.stems.forEach(::addAll)
        }
        AFFILIATES.forEach(::addAll)
        STREAMING.forEach(::addAll)
        listOf("sn", "tsn", "cbc", "abc", "tnt", "msg", "rds", "ctv", "nbc", "cbs", "fox", "tva", "bein", "sky", "bt").forEach(::addAll)
    }
}

/**
 * (Sports Hub §5.2 B and C; WP-3) Whether a guide airing's title and subtitle name a game: both teams (nickname or place),
 * or one team with a sport word ("NHL", "Hockey", "LNH"; NFL "NFL", "Football"), because nicknames collide across leagues
 * (Jets, Panthers, Rangers, Kings). Pure; plain string handling.
 */
object GameListing {
    enum class Kind {
        /** Names this game. */
        GAME,
        /** A sport-word airing that names no team (a network carrying the sport, not the game confirmed). */
        SPORT,
        /** A sport-word airing that names two other teams ("Kings at Ducks"). */
        OTHER_GAME,
        NONE,
    }

    /** The searchable names of one game: per side, folded token lists (nickname, place, full name). */
    class Teams(val away: List<List<String>>, val home: List<List<String>>, val sportWords: Set<String>) {
        /** Guide-title FTS queries (§5.2 B): the two nicknames, then the two places. */
        val queries: List<String> get() = listOfNotNull(
            joinFirst(0),
            joinFirst(1),
        ).distinct()

        private fun joinFirst(i: Int): String? {
            val a = away.getOrNull(i) ?: return null
            val h = home.getOrNull(i) ?: return null
            return (a + h).joinToString(" ")
        }
    }

    fun teamsOf(game: GameSnapshot): Teams {
        val league = game.id.league
        return Teams(namesOf(game.away, league), namesOf(game.home, league), sportWords(league))
    }

    fun sportWords(league: League): Set<String> = when (league.sport) {
        Sport.FOOTBALL -> setOf("nfl", "football")
        Sport.HOCKEY -> if (league == League.NHL) setOf("nhl", "hockey", "lnh") else setOf("hockey", "ohl", "whl", "qmjhl", "lhjmq", "chl", "lhjq")
    }

    /** Index 0: nickname; index 1: place (when known); then the full name. */
    private fun namesOf(t: TeamLine, league: League): List<List<String>> {
        val full = words(t.name)
        val place: List<String>?
        val nick: List<String>
        when (league) {
            League.NHL -> {
                place = NhlTeamPlaces.of(t.abbrev)?.let(::words)
                nick = if (place != null && full.size > place.size && full.subList(0, place.size) == place) full.drop(place.size) else full
            }
            League.NFL -> {
                nick = t.shortName?.let(::words)?.takeIf { it.isNotEmpty() } ?: full
                place = if (full.size > nick.size && full.subList(full.size - nick.size, full.size) == nick) full.dropLast(nick.size) else null
            }
            else -> { nick = full; place = null }
        }
        return listOfNotNull(nick.takeIf { it.isNotEmpty() }, place?.takeIf { it.isNotEmpty() }, full.takeIf { it.isNotEmpty() && it != nick }).distinct()
    }

    /**
     * (Event channels, docs/design/m6-event-channels.md §8.1, §2.3) A provider placeholder listing ("No Event Today",
     * "Next Event: Golden Knights at Oilers at 07:30PM EST …", "Signing Off", "Upcoming - …"): never evidence for stages B
     * and C, though "Next Event:" blocks name both teams. `EpgFiller` decides; the title is the only signal.
     */
    fun isPlaceholder(title: String): Boolean = EpgFiller.isFiller(title)

    /** What [title] (and [subtitle]) say about [teams]'s game; [Kind.NONE] for a placeholder title ([isPlaceholder]). */
    fun classify(title: String, subtitle: String?, teams: Teams): Kind {
        if (isPlaceholder(title)) return Kind.NONE
        val text = if (subtitle.isNullOrBlank()) title else "$title $subtitle"
        val tokens = words(text)
        val away = teams.away.any { contains(tokens, it) }
        val home = teams.home.any { contains(tokens, it) }
        val sport = tokens.any { it in teams.sportWords }
        return when {
            away && home -> Kind.GAME
            (away || home) && sport -> Kind.GAME
            away || home -> Kind.NONE
            sport && isMatchup(NameNormalizer.fold(text)) -> Kind.OTHER_GAME
            sport -> Kind.SPORT
            else -> Kind.NONE
        }
    }

    /** True when [title] has a sport word (the cheap test before reading an airing's subtitle). */
    fun hasSportWord(title: String, teams: Teams): Boolean = words(title).any { it in teams.sportWords }

    fun words(s: String): List<String> {
        val out = ArrayList<String>(6)
        val sb = StringBuilder()
        for (ch in NameNormalizer.fold(s)) {
            if (ch.isLetterOrDigit()) sb.append(ch) else if (sb.isNotEmpty()) { out += sb.toString(); sb.setLength(0) }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    private fun contains(tokens: List<String>, seq: List<String>): Boolean {
        if (seq.isEmpty() || seq.size > tokens.size) return false
        outer@ for (i in 0..tokens.size - seq.size) {
            for (j in seq.indices) if (tokens[i + j] != seq[j]) continue@outer
            return true
        }
        return false
    }

    private val SEPARATORS = listOf(" at ", " vs ", " vs. ", " v ", " v. ", " @ ", " versus ", " contre ", " c. ")

    private fun isMatchup(folded: String): Boolean {
        val s = " $folded "
        return SEPARATORS.any { s.contains(it) }
    }
}
