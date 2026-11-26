package app.thebox.core.full.feed

import app.thebox.core.events.ChannelEvent
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.guide.feed.eventProgrammeInfo

/**
 * (Owner report 2026-11-26; docs/design/m6-event-channels.md §6.8) The event read from a channel's name, laid over the
 * player feed's programmes with the guide's rules (`EventOverlay`, §6.1 and §6.2), so the info bar, the in-player
 * channel list and the recent channels show what the full guide shows. Pure; works with no listing and no EPG at all.
 */
internal object EventProgrammes {
    /** The channel's event when it is an EVENT with a title and a positive span; null otherwise (EMPTY, ordinary). */
    fun eventOf(ch: GuideChannel): ChannelEvent? {
        val ev = ch.event ?: return null
        return if (ev.kind == ChannelEvent.Kind.EVENT && ev.title != null && ev.endMin > ev.startMin) ev else null
    }

    /** §6.2: inside the event's span the event answers, unless a real (non-placeholder) listing airs at [atMin]. */
    fun at(channel: GuideChannel, atMin: Int, listed: ProgrammeInfo?, isFiller: (String) -> Boolean): ProgrammeInfo? {
        val ev = eventOf(channel) ?: return listed
        if (atMin < ev.startMin || atMin >= ev.endMin) return listed
        return if (listed == null || isFiller(listed.title)) eventProgrammeInfo(ev) else listed
    }

    /**
     * §6.1 over [list] (start order, the programmes read for `[fromMin, toMin)`): a real listing overlapping the event's
     * span wins and [list] is returned as it is; otherwise placeholders are clipped to the parts outside the span (a
     * piece keeps its raw start; one whose raw start is the event's own start minute is dropped, as in the guide) and
     * the event is inserted in start order.
     */
    fun overlay(channel: GuideChannel, list: List<ProgrammeInfo>, fromMin: Int, toMin: Int, isFiller: (String) -> Boolean): List<ProgrammeInfo> {
        val ev = eventOf(channel) ?: return list
        if (ev.endMin <= fromMin || ev.startMin >= toMin) return list
        for (p in list) if (p.startMin < ev.endMin && p.endMin > ev.startMin && !isFiller(p.title)) return list
        val out = ArrayList<ProgrammeInfo>(list.size + 2)
        fun sharesKey(p: ProgrammeInfo) = p.rawStartMin == ev.startMin && isFiller(p.title)
        for (p in list) {
            if (p.startMin >= ev.startMin) break
            if (sharesKey(p)) continue
            if (p.endMin <= ev.startMin) out += p else out += p.copy(endMin = ev.startMin)
        }
        out += eventProgrammeInfo(ev)
        for (p in list) {
            if (p.endMin <= ev.endMin || sharesKey(p)) continue
            out += if (p.startMin >= ev.endMin) p else p.copy(startMin = ev.endMin)
        }
        return out
    }
}
