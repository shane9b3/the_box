package app.thebox.core.xmltv

/**
 * One accepted `<programme>`. **Reusable and mutable**: the parser owns one instance and refills it for
 * every accepted programme, so it is valid only inside [XmltvHandler.onProgramme]. Copy what you keep.
 *
 * Int fields use sentinels instead of nullable boxes ([NONE], [XmltvTime.MISSING]).
 */
class XmltvProgramme {
    var channelId: String = ""
    /** UTC epoch minutes (floored). */
    var startMin: Int = 0
    /** UTC epoch minutes, or [XmltvTime.MISSING] when the element had no usable `stop`. */
    var stopMin: Int = XmltvTime.MISSING
    /** Never null for an accepted programme ("" when the element had no `<title>`). */
    var title: String = ""
    var subtitle: String? = null
    /** Capped at maxDescriptionChars on a code-point boundary; null when descriptions are not stored. */
    var description: String? = null
    /** Up to [MAX_CATEGORIES], preferred language, de-duplicated, document order. */
    val categories: ArrayList<String> = ArrayList(MAX_CATEGORIES)
    var episodeLabel: String? = null
    /** 1-based, or [NONE]. */
    var season: Int = NONE
    /** 1-based, or [NONE]. */
    var episode: Int = NONE
    /** 1-based, or [NONE]. */
    var part: Int = NONE
    var partTotal: Int = NONE
    /** dd_progid series part ("EP01234567"). */
    var seriesRef: String? = null
    var iconUrl: String? = null
    /** "MPAA:PG-13" (or just the value when the rating has no system). */
    var rating: String? = null
    /** "7.5/10". */
    var starRating: String? = null
    var year: Int = NONE
    /** Compact "d:Name\u001Fa:Name (Role)…", capped at [MAX_CREDITS_CHARS]. */
    var credits: String? = null
    /** [app.thebox.core.epg.ProgrammeFlags] bits found in the element (NEW, PREMIERE, …, HAS_CATCHUP_ID, HD, UHD). */
    var flags: Int = 0
    var catchupId: String? = null

    fun hasStop(): Boolean = stopMin != XmltvTime.MISSING

    fun reset() {
        channelId = ""; startMin = 0; stopMin = XmltvTime.MISSING
        title = ""; subtitle = null; description = null; categories.clear()
        episodeLabel = null; season = NONE; episode = NONE; part = NONE; partTotal = NONE; seriesRef = null
        iconUrl = null; rating = null; starRating = null; year = NONE; credits = null; flags = 0; catchupId = null
    }

    /** Joined categories as stored in epg_text.categories, or null when there are none. */
    fun categoriesJoined(): String? = when (categories.size) {
        0 -> null
        1 -> categories[0]
        else -> categories.joinToString(CATEGORY_SEPARATOR)
    }

    override fun toString(): String =
        "XmltvProgramme(channel=$channelId, start=$startMin, stop=$stopMin, title=$title, subtitle=$subtitle, " +
            "categories=$categories, episode=$episodeLabel S$season E$episode, flags=$flags, catchupId=$catchupId)"

    companion object {
        const val NONE = -1
        const val MAX_CATEGORIES = 5
        const val MAX_CREDITS_CHARS = 512
        const val CATEGORY_SEPARATOR = "\u001F"
    }
}
