package app.thebox.core.epg

import app.thebox.core.common.Hash64
import app.thebox.core.epg.store.EpgSchema

/**
 * (2026-11-10, deferred-items review S1) Fingerprint of everything a full guide build reads besides the XMLTV bytes and
 * the clock. The orchestrator skips a build whose downloads are unchanged only when the current generation carries the
 * same fingerprint, so a NEXT_EPG_IMPORT setting change (past/future days, descriptions, languages, matching, the
 * guide sources) or an importer change applies at the next run, rebuilt from the cached files.
 *
 * The store's own versions ([EpgSchema.SCHEMA_VERSION], [EpgSchema.IMPORTER_VERSION]) are mixed in, so bumping
 * [EpgSchema.IMPORTER_VERSION] with a change to what the importer writes rebuilds unchanged guides once.
 */
object EpgBuildInputs {
    /** One active guide source: its library id and its assumed zone id as stored (null = UTC). */
    data class Source(val id: Long, val assumeZone: String?)

    /**
     * [sources] in any order (the build result does not depend on it); [matching] is the caller's stable text of
     * whatever its whitelist resolver reads (match settings, matcher version). A hex string.
     */
    fun fingerprint(
        sources: List<Source>,
        pastDays: Int,
        futureDays: Int,
        storeDescriptions: Boolean,
        maxDescriptionChars: Int,
        preferredLanguages: List<String>,
        matching: String,
    ): String {
        var h = Hash64.int(Hash64.OFFSET, EpgSchema.SCHEMA_VERSION)
        h = Hash64.str(h, EpgSchema.IMPORTER_VERSION)
        h = Hash64.int(h, pastDays)
        h = Hash64.int(h, futureDays)
        h = Hash64.bool(h, storeDescriptions)
        h = Hash64.int(h, maxDescriptionChars)
        h = Hash64.int(h, preferredLanguages.size)
        for (l in preferredLanguages) h = Hash64.str(h, l)
        h = Hash64.str(h, matching)
        val sorted = sources.sortedBy { it.id }
        h = Hash64.int(h, sorted.size)
        for (s in sorted) {
            h = Hash64.long(h, s.id)
            h = Hash64.str(h, s.assumeZone)
        }
        return java.lang.Long.toHexString(h)
    }
}
