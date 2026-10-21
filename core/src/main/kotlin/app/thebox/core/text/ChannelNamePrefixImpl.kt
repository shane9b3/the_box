package app.thebox.core.text

import app.thebox.core.common.BoxLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The channel-name prefix rule (docs/design/m3-guide.md §3.12; lead sign-off §7 item 10, review S5).
 *
 * Auto strips **one** leading tag (`UK: `, `UK:`, `UK | `, `UK|`, `UK - `, `[UK] `, `(UK) `) when the tag is an
 * ISO-3166 alpha-2/alpha-3 code (or `UK`) that is not in [EXCLUDED_TAGS], or when it equals the entry's group name and
 * has no lower-case letter. No regex on the Auto path; pure and allocation-light (one substring per changed name).
 *
 * Thread-safe: the only state is the compiled custom regex, published through one volatile reference.
 */
class ChannelNamePrefixImpl : ChannelNamePrefix {
    private class CompiledRegex(val source: String, val pattern: UserRegex?)

    @Volatile private var compiled: CompiledRegex? = null

    override fun strip(name: String, groupName: String?, rule: NamePrefixRule): String? = when (rule) {
        NamePrefixRule.Off -> null
        NamePrefixRule.Auto -> auto(name, groupName)
        is NamePrefixRule.Custom -> custom(name, groupName, rule)
    }

    /** How many of [names] (name, group) the rule changes: the M5 editor's preview count (§3.12.2). */
    fun countChanged(names: Sequence<Pair<String, String?>>, rule: NamePrefixRule): Int = names.count { (n, g) -> strip(n, g, rule) != null }

    private fun custom(name: String, groupName: String?, rule: NamePrefixRule.Custom): String? {
        val regex = rule.regex
        if (regex != null) {
            val p = pattern(regex) ?: return auto(name, groupName)
            return try {
                val head = if (name.length > REGEX_MAX_CHARS) name.substring(0, REGEX_MAX_CHARS) else name
                val end = p.prefixEnd(head)
                if (end == null) null else finish(name.substring(end))
            } catch (e: StackOverflowError) {
                BoxLog.w(TAG, "custom prefix regex overflowed; using Auto")
                compiled = CompiledRegex(regex, null)
                auto(name, groupName)
            }
        }
        if (rule.literals.isEmpty()) return null
        val start = firstNonSpace(name)
        if (start < 0) return null
        for (lit in sortedLiterals(rule.literals)) {
            if (lit.isEmpty()) continue
            if (name.regionMatches(start, lit, 0, lit.length, ignoreCase = true)) {
                var k = start + lit.length
                k = skipSpace(name, k)
                if (k < name.length && (name[k] == ':' || name[k] == '|' || name[k] == '-')) k++
                return finish(name.substring(k))
            }
        }
        return null
    }

    private fun sortedLiterals(literals: List<String>): List<String> =
        if (literals.size < 2) literals else literals.sortedByDescending { it.length }

    private fun pattern(regex: String): UserRegex? {
        val c = compiled
        if (c != null && c.source == regex) return c.pattern
        val p = try {
            UserRegex.compile(regex)
        } catch (e: IllegalArgumentException) {
            // Includes saved pre-RE2 syntax and imported patterns: fail safely without a Java/ICU fallback.
            BoxLog.w(TAG, "custom prefix regex unsupported or over budget; using Auto")
            null
        }
        compiled = CompiledRegex(regex, p)
        return p
    }

    companion object {
        private const val TAG = "ChannelNamePrefix"
        /** The tag (bracketed or bare) must close within this many chars of its start. */
        const val MAX_TAG = 32
        /** A custom regex sees at most this many leading chars (§3.12.2). */
        const val REGEX_MAX_CHARS = 256

        /**
         * ISO 3166-1 alpha-2 (249 codes, 2026). A fixed list, not `Locale.getISOCountries()`: the platform list varies
         * between Android versions and a display name must not change with an OS update. Changing it bumps
         * [ChannelNamePrefix.RULE_VERSION].
         */
        val ISO_ALPHA2: Set<String> = (
            "AD AE AF AG AI AL AM AO AQ AR AS AT AU AW AX AZ BA BB BD BE BF BG BH BI BJ BL BM BN BO BQ BR BS BT BV BW BY BZ " +
                "CA CC CD CF CG CH CI CK CL CM CN CO CR CU CV CW CX CY CZ DE DJ DK DM DO DZ EC EE EG EH ER ES ET FI FJ FK FM " +
                "FO FR GA GB GD GE GF GG GH GI GL GM GN GP GQ GR GS GT GU GW GY HK HM HN HR HT HU ID IE IL IM IN IO IQ IR IS " +
                "IT JE JM JO JP KE KG KH KI KM KN KP KR KW KY KZ LA LB LC LI LK LR LS LT LU LV LY MA MC MD ME MF MG MH MK ML " +
                "MM MN MO MP MQ MR MS MT MU MV MW MX MY MZ NA NC NE NF NG NI NL NO NP NR NU NZ OM PA PE PF PG PH PK PL PM PN " +
                "PR PS PT PW PY QA RE RO RS RU RW SA SB SC SD SE SG SH SI SJ SK SL SM SN SO SR SS ST SV SX SY SZ TC TD TF TG " +
                "TH TJ TK TL TM TN TO TR TT TV TW TZ UA UG UM US UY UZ VA VC VE VG VI VN VU WF WS YE YT ZA ZM ZW"
            ).split(' ').toHashSet()

        /** ISO 3166-1 alpha-3 (249 codes, 2026). Same policy as [ISO_ALPHA2]. */
        val ISO_ALPHA3: Set<String> = (
            "ABW AFG AGO AIA ALA ALB AND ARE ARG ARM ASM ATA ATF ATG AUS AUT AZE BDI BEL BEN BES BFA BGD BGR BHR BHS BIH " +
                "BLM BLR BLZ BMU BOL BRA BRB BRN BTN BVT BWA CAF CAN CCK CHE CHL CHN CIV CMR COD COG COK COL COM CPV CRI CUB " +
                "CUW CXR CYM CYP CZE DEU DJI DMA DNK DOM DZA ECU EGY ERI ESH ESP EST ETH FIN FJI FLK FRA FRO FSM GAB GBR GEO " +
                "GGY GHA GIB GIN GLP GMB GNB GNQ GRC GRD GRL GTM GUF GUM GUY HKG HMD HND HRV HTI HUN IDN IMN IND IOT IRL IRN " +
                "IRQ ISL ISR ITA JAM JEY JOR JPN KAZ KEN KGZ KHM KIR KNA KOR KWT LAO LBN LBR LBY LCA LIE LKA LSO LTU LUX LVA " +
                "MAC MAF MAR MCO MDA MDG MDV MEX MHL MKD MLI MLT MMR MNE MNG MNP MOZ MRT MSR MTQ MUS MWI MYS MYT NAM NCL NER " +
                "NFK NGA NIC NIU NLD NOR NPL NRU NZL OMN PAK PAN PCN PER PHL PLW PNG POL PRI PRK PRT PRY PSE PYF QAT REU ROU " +
                "RUS RWA SAU SDN SEN SGP SGS SHN SJM SLB SLE SLV SMR SOM SPM SRB SSD STP SUR SVK SVN SWE SWZ SXM SYC SYR TCA " +
                "TCD TGO THA TJK TKL TKM TLS TON TTO TUN TUR TUV TWN TZA UGA UKR UMI URY USA UZB VAT VCT VEN VGB VIR VNM VUT " +
                "WLF WSM YEM ZAF ZMB ZWE"
            ).split(' ').toHashSet()

        /** Not a code but the usual tag for the United Kingdom. */
        const val UK = "UK"

        /**
         * Codes that are common words in channel names (lead decision, §3.12.1): Tuvalu, Micronesia, Armenia; Andorra,
         * UAE, Peru. Changing the list bumps [ChannelNamePrefix.RULE_VERSION].
         */
        val EXCLUDED_TAGS: Set<String> = hashSetOf("TV", "FM", "AM", "AND", "ARE", "PER")

        /** True when [tag] (already trimmed) is a country tag the Auto rule strips. */
        fun isCountryTag(tag: String): Boolean {
            if (tag.length !in 2..3) return false
            for (c in tag) if (c !in 'A'..'Z' && c !in 'a'..'z') return false
            val up = tag.uppercase()
            if (up in EXCLUDED_TAGS) return false
            return up == UK || (if (up.length == 2) up in ISO_ALPHA2 else up in ISO_ALPHA3)
        }

        /** §3.12.1 algorithm, no regex. Null when the name stays as it is. */
        internal fun auto(name: String, groupName: String?): String? {
            val i = firstNonSpace(name)
            if (i < 0) return null
            val tagStart: Int
            val tagEnd: Int
            var rest: Int
            val open = name[i]
            if (open == '[' || open == '(') {
                val close = if (open == '[') ']' else ')'
                val limit = minOf(name.length, i + 1 + MAX_TAG)
                var j = i + 1
                while (j < limit && name[j] != close) j++
                if (j >= limit) return null
                tagStart = i + 1
                tagEnd = j
                rest = skipSpace(name, j + 1)
                if (rest < name.length && (name[rest] == ':' || name[rest] == '|' || name[rest] == '-')) rest = skipSpace(name, rest + 1)
            } else {
                val limit = minOf(name.length, i + MAX_TAG)
                var j = i
                var sepEnd = -1
                while (j < limit) {
                    val c = name[j]
                    if (c == ':' || c == '|') { sepEnd = j + 1; break }
                    if (c == '-' && j > i && name[j - 1].isWhitespace() && j + 1 < name.length && name[j + 1].isWhitespace()) {
                        sepEnd = j + 1
                        break
                    }
                    j++
                }
                if (sepEnd < 0) return null
                tagStart = i
                tagEnd = j
                rest = skipSpace(name, sepEnd)
            }
            val tag = name.substring(tagStart, tagEnd).trim()
            if (tag.isEmpty() || !qualifies(tag, groupName)) return null
            return finish(name.substring(rest))
        }

        private fun qualifies(tag: String, groupName: String?): Boolean {
            if (isCountryTag(tag)) return true
            val g = groupName?.trim() ?: return false
            if (!g.equals(tag, ignoreCase = true)) return false
            for (c in tag) if (c.isLowerCase()) return false
            return true
        }

        /** Trims [rest]; null when nothing readable is left (no letter or digit). */
        private fun finish(rest: String): String? {
            val r = rest.trim()
            if (r.isEmpty()) return null
            for (c in r) if (c.isLetterOrDigit()) return r
            return null
        }

        private fun firstNonSpace(s: String): Int {
            for (k in s.indices) if (!s[k].isWhitespace()) return k
            return -1
        }

        private fun skipSpace(s: String, from: Int): Int {
            var k = from
            while (k < s.length && s[k].isWhitespace()) k++
            return k
        }
    }
}

/**
 * `playlist.name_prefix_mode` + `name_prefix_custom` → [NamePrefixRule] (§3.12.2, review B7). `OFF` → Off; `CUSTOM` with
 * `{"literals":[…]}` → literals, with `{"regex":"…"}` → regex; both keys, neither, an empty literal list or malformed JSON
 * → Auto (logged); anything else → Auto.
 */
object NamePrefixRules {
    private const val TAG = "NamePrefixRules"
    const val AUTO = "AUTO"
    const val OFF = "OFF"
    const val CUSTOM = "CUSTOM"

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(mode: String, customJson: String?): NamePrefixRule = when (mode.trim().uppercase()) {
        OFF -> NamePrefixRule.Off
        CUSTOM -> parseCustom(customJson)
        else -> NamePrefixRule.Auto
    }

    private fun parseCustom(customJson: String?): NamePrefixRule {
        if (customJson.isNullOrBlank()) return fallback("empty custom rule")
        val obj = try {
            json.parseToJsonElement(customJson) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return fallback("custom rule is not a JSON object")
        val literals = obj["literals"]
        val regex = obj["regex"]
        if (literals != null && regex != null) return fallback("custom rule sets both literals and regex")
        if (literals is JsonArray) {
            val list = literals.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.filter { it.isNotEmpty() }
            return if (list.isEmpty()) fallback("custom rule has no literals") else NamePrefixRule.Custom(literals = list)
        }
        if (regex is JsonPrimitive && regex.isString && regex.content.isNotEmpty()) return NamePrefixRule.Custom(regex = regex.content)
        return fallback("custom rule is malformed")
    }

    private fun fallback(why: String): NamePrefixRule {
        BoxLog.w(TAG, "$why; using Auto")
        return NamePrefixRule.Auto
    }

    /** The inverse of [parse]: `(name_prefix_mode, name_prefix_custom)` for `PlaylistDao.setNamePrefix`. */
    fun encode(rule: NamePrefixRule): Pair<String, String?> = when (rule) {
        NamePrefixRule.Auto -> AUTO to null
        NamePrefixRule.Off -> OFF to null
        is NamePrefixRule.Custom -> CUSTOM to (
            if (rule.regex != null) {
                buildJsonObject { put("regex", rule.regex) }
            } else {
                buildJsonObject { putJsonArray("literals") { rule.literals.forEach { add(JsonPrimitive(it)) } } }
            }
            ).toString()
    }
}
