package app.thebox.core.xmltv

/**
 * Push callbacks of [XmltvParser], all on the parsing thread.
 *
 * Flow per `<programme>`: the parser reads only the attributes, parses the times and asks
 * [acceptProgramme]. On `false` the subtree is skipped without building any text (§3.5.3). On `true`
 * the children are read into the reusable [XmltvProgramme] and [onProgramme] is called.
 */
interface XmltvHandler {
    fun onChannel(c: XmltvChannel)

    /** [stopMin] is [XmltvTime.MISSING] when the programme has no (usable) stop. */
    fun acceptProgramme(channelId: String, startMin: Int, stopMin: Int): Boolean

    /** [p] is valid only during the call. */
    fun onProgramme(p: XmltvProgramme)

    /** A programme was dropped for an unusable start, or stop ≤ start after flooring (`badTimes`). */
    fun onBadTime(channelId: String) {}

    /** The document ended normally (not called when parsing throws). */
    fun onEnd() {}
}
