package app.thebox.core.xmltv

/** One `<display-name>` of a channel. */
data class XmltvDisplayName(val name: String, val lang: String?)

/**
 * An XMLTV `<channel>` element. Immutable (channels are few). [displayNames] are ordered with the
 * preferred languages first (stable otherwise), so `displayNames.firstOrNull()` is the display name.
 */
data class XmltvChannel(
    val id: String,
    val displayNames: List<XmltvDisplayName>,
    val iconUrl: String?,
)
