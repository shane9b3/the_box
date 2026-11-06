package app.thebox.core.sports

import kotlinx.serialization.json.JsonObject

/**
 * NHL JSON to snapshots (docs/design/m6-score-bug.md §2.1; WP-1). Pure: no I/O, no clock. Reads `default` for every
 * localised field, ignores unknown keys, treats every field as optional. A body it cannot map yields null (the source
 * turns that into `Failed(BAD_BODY)`). [logoBase] is `SportsEndpoints.logoBase`: a landing `darkLogo` outside it is
 * replaced by the URL derived from the abbreviation (A-N2).
 *
 * Fixtures: `core/src/testFixtures/resources/sports/nhl/` (on the classpath of `:core`, `:data` and `:ui` tests).
 *
 * Commit 0: contract only; WP-1 fills the bodies.
 */
@Suppress("UNUSED_PARAMETER")
object NhlMapper {
    /** `/v1/score/now` or `/v1/score/{date}`: `currentDate` is the day (A-N10); games are grouped by `gameDate`. */
    fun day(body: JsonObject, logoBase: String): DayBoard? = TODO("WP-1")

    /** `/v1/gamecenter/{id}/landing`: names are `placeName + commonName`; the period is `periodDescriptor.number`. */
    fun landing(body: JsonObject, logoBase: String): GameSnapshot? = TODO("WP-1")

    /** `{logoBase}{ABBR}_dark.svg`. */
    fun darkLogoUrl(abbrev: String, logoBase: String): String = TODO("WP-1")
}
