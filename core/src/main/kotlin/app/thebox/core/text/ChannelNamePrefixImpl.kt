package app.thebox.core.text

/** WP-B stub (docs/design/m3-guide.md §1.3, §3.12): the Auto/Off/Custom rule. */
class ChannelNamePrefixImpl : ChannelNamePrefix {
    override fun strip(name: String, groupName: String?, rule: NamePrefixRule): String? = TODO("WP-B")
}

/** WP-B stub (§3.12.2, review B7): parses `playlist.name_prefix_mode` + `name_prefix_custom`. */
object NamePrefixRules {
    fun parse(mode: String, customJson: String?): NamePrefixRule = TODO("WP-B")
}
