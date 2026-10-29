package app.thebox.core.settings.state

import app.thebox.core.art.ArtworkStatus
import app.thebox.core.art.ProgrammeArtwork
import app.thebox.core.full.BackInPlayer
import app.thebox.core.full.OnLaunch
import app.thebox.core.full.PlayerSettings
import app.thebox.core.full.UpDownInPlayer
import app.thebox.core.guide.ExitConfirmation
import app.thebox.core.guide.PerformanceMode
import app.thebox.core.match.MatchLevel
import app.thebox.core.net.DnsChoice
import app.thebox.core.net.NetworkSettings
import app.thebox.core.net.PreferIpv4
import app.thebox.core.playback.AfrMode
import app.thebox.core.playback.AspectMode
import app.thebox.core.playback.AudioOutputMode
import app.thebox.core.playback.BufferMode
import app.thebox.core.playback.HdrPreviewMode
import app.thebox.core.playback.VideoDecoderMode
import app.thebox.core.playback.timeshift.TimeshiftConstants
import app.thebox.core.settings.ChoiceOption
import app.thebox.core.settings.ClockMode
import app.thebox.core.settings.DataPrefs
import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.GuidePrefs
import app.thebox.core.settings.PageModel
import app.thebox.core.settings.PlaybackPrefs
import app.thebox.core.settings.RowId
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.SettingsStore
import app.thebox.core.settings.StorageProtection
import app.thebox.core.settings.SubtitleMode
import app.thebox.core.settings.TimeshiftHold
import app.thebox.core.settings.TimeshiftLength
import app.thebox.core.settings.TextHint
import app.thebox.core.settings.Visibility
import app.thebox.core.settings.tree.GuideRows
import app.thebox.core.settings.tree.InterfaceRows
import app.thebox.core.settings.tree.NetworkRows
import app.thebox.core.settings.tree.PauseRewindRows
import app.thebox.core.settings.tree.PlaybackRows
import app.thebox.core.settings.tree.RemoteRows
import app.thebox.core.settings.tree.SettingsTree
import app.thebox.core.storage.StorageKind
import app.thebox.core.storage.StorageLimits
import app.thebox.core.storage.LocationState
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/** An installed app that plays video over http (`ACTION_VIEW` of any video type, §3.3.4, §9 F24). */
data class ExternalPlayerApp(val packageName: String, val label: String)

/** The device and app facts WP-A's pages read (implemented in `:ui`/`:app`; a plain fake in tests). Main-safe. */
interface WpaPageEnv {
    /** `Build.VERSION.SDK_INT` (frame-rate matching below API 30, N5). */
    val sdkInt: Int
    /** The device class says low-end (`DeviceClass.lowEnd`). */
    val deviceLowEnd: Boolean
    /** (owner ruling 2026-10-19) The build carries the app's TMDB key (`BuiltInKeys.tmdb`); false on a keyless build. */
    val hasTmdbKey: Boolean
    /** `DateFormat.is24HourFormat` (clock System). */
    fun system24h(): Boolean
    /** Installed external players (a `PackageManager` query, §3.3.4). */
    fun externalPlayers(): List<ExternalPlayerApp>
    /** `softwareAudioFallback` of the running engine, or null before one exists (Restart now, N9). */
    fun runningSoftwareAudioFallback(): Boolean?
    /** Restart now (N9): flush, relaunch the activity with `FLAG_ACTIVITY_CLEAR_TASK`, end the process. */
    fun restartNow()
    /** User key-binding overrides (the `remote.mapping` value). */
    fun keyOverrideCount(): Int

    /**
     * (M6 time-shift, docs/design/m6-timeshift.md §8.2, §12) The storage the ring file lives on (`cacheDir`): total and
     * free bytes and the ring file's current size, for "Pause and rewind" Auto and the "storage is limited" value.
     * Null (default, tests) = unknown: Auto reads as the small-storage length.
     */
    fun timeshiftStorage(): TimeshiftStorageFacts? = null

    /**
     * (M6, §8.3, §12) "Pause and rewind" was set to Off: close and delete the ring file now when no buffered cycle is
     * using it (the session factory's discard path); a buffered cycle still playing keeps it until its tune ends, when
     * the next tune's preparation deletes it. Default: nothing (tests).
     */
    fun timeshiftOff() = Unit
}

/** (M6 §8.2) The ring file's volume as `WpaPageEnv.timeshiftStorage` reports it. */
data class TimeshiftStorageFacts(val totalBytes: Long, val availableBytes: Long, val currentFileBytes: Long = 0L)

/**
 * (WP-S4, docs/design/m6-storage-locations.md §9.1) Where rewind is saved, as the Pause and rewind rows read it. [DEVICE]
 * (the default, kind INTERNAL, blank label) is today's behaviour, so every pre-existing caller and test is unchanged.
 * [label] is the value's form ("This device", "NAS", "USB drive"), [sentence] the form after "on" ("this device",
 * "NAS", "the USB drive"). [facts] are a non-internal place's free bytes (null = unknown). [offlineName] is the chosen
 * place's name while the cycle saves on this device instead ("NAS offline", amber); [problem] is why (the Save to
 * description). A blank [label] adds no place to the link's value. [failure] (O-6, closure fix pass; additive) is why a
 * DEMOTED folder was demoted when the keeper knows it (`LocationStatus.failure`: a ring file refused as ACCESS, FULL or
 * REMOVED), so the page names that reason instead of "kept dropping out".
 */
data class TimeshiftPlace(
    val kind: StorageKind = StorageKind.INTERNAL,
    val label: String = "",
    val sentence: String = "",
    val facts: TimeshiftStorageFacts? = null,
    val offlineName: String? = null,
    val problem: LocationState? = null,
    val failure: app.thebox.core.storage.StorageFailure? = null,
) {
    companion object { val DEVICE = TimeshiftPlace() }
}

/** (WP-S4) The current place; `StoragePages`' `TimeshiftPlaces` answers it from the stored locations. Main-safe, no I/O. */
fun interface TimeshiftPlaceSource {
    fun current(): TimeshiftPlace
    companion object { val DEVICE = TimeshiftPlaceSource { TimeshiftPlace.DEVICE } }
}

/**
 * WP-A's section pages (docs/design/m5-setup-settings.md §3.3.2, §3.3.3, §3.3.6, §3.3.7, §3.3.11): TV guide, Playback,
 * Interface, Remote control and Network. Every value comes from [store]; every intent writes it (main-safe: the store
 * applies in memory at once and persists on IO). Rows carry the [SettingsTree] spec's visibility and [EffectTiming];
 * advanced rows are absent (not dimmed) while the view is simple (§3.2.3); a row's description states its effect when
 * it is not LIVE (§2.1).
 */
class WpaPages(
    private val store: SettingsStore,
    private val texts: SettingsTexts,
    private val env: WpaPageEnv,
    /** (owner rulings 2026-10-18/19) Programme posters: [ArtworkStatus] drives the toggle's description; `guide.posters` is a pref. */
    private val artwork: ProgrammeArtwork = ProgrammeArtwork.NONE,
    /** (WP-S4) The place rewind saves to: the Playback link's value ("1 hour · NAS"). */
    private val places: TimeshiftPlaceSource = TimeshiftPlaceSource.DEVICE,
) : SettingsPageSource {
    private val version = AtomicLong(0)
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val notices: Flow<String> get() = _notices

    override fun handles(route: SettingsRoute): Boolean =
        // (WP-S4) PauseRewind is StoragePages' page (Q-D8, closure fix pass: the only route to it).
        route is SettingsRoute.Section && route.id in SECTIONS

    override val changes: Flow<Unit>
        get() = merge(
            store.flow(GuideDomain).drop(1).map { },
            store.flow(PlayerDomain).drop(1).map { },
            store.flow(PlaybackDomain).drop(1).map { },
            store.flow(DataDomain).drop(1).map { },
            store.flow(NetworkDomain).drop(1).map { },
            artwork.status.drop(1).map { },
        )

    override suspend fun page(route: SettingsRoute, ctx: PageContext): PageModel = build(route, ctx.advanced)

    /** Builds [route]'s page synchronously (tests and [page]). */
    fun build(route: SettingsRoute, advanced: Boolean): PageModel {
        val id = (route as SettingsRoute.Section).id
        val rows = when (id) {
            SectionId.GUIDE -> guideRows()
            SectionId.PLAYBACK -> playbackRows()
            SectionId.INTERFACE -> interfaceRows()
            SectionId.REMOTE -> remoteRows()
            SectionId.NETWORK -> networkRows()
            else -> emptyList()
        }.filter { advanced || it.visibility == Visibility.SIMPLE }
        return PageModel(route = route, title = texts.section(id), rows = rows, version = version.incrementAndGet())
    }

    // ---------------------------------------------------------------- TV guide (§3.3.2)

    private fun guideRows(): List<SettingsRow> {
        val d = store.flow(DataDomain).value
        val out = ArrayList<SettingsRow>()
        out += link(GuideRows.SOURCES, SettingsRoute.EpgSources, t(SettingsText.GUIDE_SOURCES), t(SettingsText.GUIDE_SOURCES_DESC))
        out += link(GuideRows.STATUS, SettingsRoute.SyncStatus, t(SettingsText.GUIDE_STATUS))
        out += link(GuideRows.FIX_UNMATCHED, SettingsRoute.FixGuide(null), t(SettingsText.GUIDE_FIX), t(SettingsText.GUIDE_FIX_DESC))
        val daily = d.epgDailyAtHour != null
        val intervals = INTERVALS.map { ChoiceOption(it.toString(), t(SettingsText.INTERVAL_HOURS, it)) } + ChoiceOption(DAILY, t(SettingsText.INTERVAL_DAILY))
        out += choice(GuideRows.INTERVAL, t(SettingsText.GUIDE_INTERVAL), intervals, if (daily) DAILY else d.epgRefreshIntervalH.toString())
        if (daily) {
            val h = d.epgDailyAtHour ?: 0
            out += row(GuideRows.DAILY_HOUR, t(SettingsText.GUIDE_DAILY_HOUR), RowKind.Number(h, 0, 23), value = t(SettingsText.HOUR_VALUE, h))
        }
        out += choice(
            GuideRows.MATCHING, t(SettingsText.GUIDE_MATCHING),
            listOf(
                MatchLevel.EXACT_ONLY to SettingsText.MATCH_EXACT, MatchLevel.EXACT_AND_NAMES to SettingsText.MATCH_NAMES,
                MatchLevel.SUGGEST_FUZZY to SettingsText.MATCH_SUGGEST, MatchLevel.AUTO_FUZZY to SettingsText.MATCH_AUTO,
            ).map { (l, k) -> ChoiceOption(l.name, t(k)) },
            d.matchLevel.name,
        )
        out += choice(GuideRows.SHIFT, t(SettingsText.GUIDE_SHIFT), shiftOptions(), d.globalShiftMin.toString())
        out += choice(
            GuideRows.PAST_DAYS, t(SettingsText.GUIDE_PAST_DAYS),
            PAST_DAYS.map { ChoiceOption(it.toString(), if (it == 0) t(SettingsText.PAST_AUTO) else days(it)) },
            d.epgPastDays.toString(),
        )
        out += choice(GuideRows.FUTURE_DAYS, t(SettingsText.GUIDE_FUTURE_DAYS), FUTURE_DAYS.map { ChoiceOption(it.toString(), days(it)) }, d.epgFutureDays.toString())
        out += toggle(GuideRows.DESCRIPTIONS, t(SettingsText.GUIDE_DESCRIPTIONS), d.storeDescriptions, t(SettingsText.GUIDE_DESCRIPTIONS_DESC))
        out += postersRows()
        return out
    }

    /**
     * (owner rulings 2026-10-18/19) Programme posters: the on/off toggle alone (the app's built-in TMDB key only). Its
     * description says when the build has no key (`WpaPageEnv.hasTmdbKey`, known at build time, so it never flickers
     * with the lookup's status) and when TMDB rejected the app's key.
     */
    private fun postersRows(): List<SettingsRow> {
        val g = store.flow(GuideDomain).value
        val description = when {
            artwork.status.value == ArtworkStatus.APP_KEY_REJECTED -> SettingsText.POSTERS_APP_KEY_REJECTED
            !env.hasTmdbKey -> SettingsText.GUIDE_POSTERS_DESC_NO_KEY
            else -> SettingsText.GUIDE_POSTERS_DESC
        }
        return listOf(toggle(GuideRows.POSTERS, t(SettingsText.GUIDE_POSTERS), g.posters, t(description)))
    }

    private fun shiftOptions(): List<ChoiceOption> = (-SHIFT_STEPS..SHIFT_STEPS).map { s ->
        val min = s * 30
        ChoiceOption(min.toString(), if (min == 0) t(SettingsText.SHIFT_NONE) else t(SettingsText.SHIFT_VALUE, shiftHours(min)))
    }

    private fun days(n: Int) = if (n == 1) t(SettingsText.DAYS_ONE) else t(SettingsText.DAYS_N, n)

    // ---------------------------------------------------------------- Playback (§3.3.3)

    private fun playbackRows(): List<SettingsRow> {
        val p = store.flow(PlaybackDomain).value
        val pl = store.flow(PlayerDomain).value
        val out = ArrayList<SettingsRow>()
        out += choice(
            PlaybackRows.BUFFER, t(SettingsText.PB_BUFFER),
            listOf(
                BufferMode.AUTO to SettingsText.BUF_AUTO, BufferMode.NONE to SettingsText.BUF_NONE, BufferMode.SMALL to SettingsText.BUF_SMALL,
                BufferMode.MEDIUM to SettingsText.BUF_MEDIUM, BufferMode.LARGE to SettingsText.BUF_LARGE, BufferMode.EXTRA_LARGE to SettingsText.BUF_XL,
            ).map { (m, k) -> ChoiceOption(m.name, t(k)) },
            p.bufferMode.name,
            t(SettingsText.PB_BUFFER_DESC), // (M6 §12, U-N7) the network buffer is not pause and rewind
        )
        val modern = env.sdkInt >= AFR_SEAMLESS_MIN_SDK
        val afr = buildList {
            add(ChoiceOption(AfrMode.OFF.name, t(SettingsText.AFR_OFF)))
            if (modern) add(ChoiceOption(AfrMode.SEAMLESS.name, t(SettingsText.AFR_SEAMLESS)))
            add(ChoiceOption(AfrMode.ALWAYS.name, t(SettingsText.AFR_ALWAYS)))
        }
        val afrShown = if (!modern && p.afrMode == AfrMode.SEAMLESS) AfrMode.OFF else p.afrMode
        out += choice(PlaybackRows.AFR, t(SettingsText.PB_AFR), afr, afrShown.name, if (modern) null else t(SettingsText.AFR_OLD_NOTE))
        out += choice(
            PlaybackRows.AUDIO_OUTPUT, t(SettingsText.PB_AUDIO_OUTPUT),
            listOf(
                ChoiceOption(AudioOutputMode.AUTO.name, t(SettingsText.AO_AUTO)),
                ChoiceOption(AudioOutputMode.ALWAYS_DECODE.name, t(SettingsText.AO_PCM)),
                ChoiceOption(AudioOutputMode.STEREO.name, t(SettingsText.AO_STEREO)),
                ChoiceOption(AudioOutputMode.FORCE_PASSTHROUGH.name, t(SettingsText.AO_PASSTHROUGH), detail = t(SettingsText.AO_PASSTHROUGH_DETAIL)),
            ),
            p.audioOutput.name,
            if (p.audioOutput == AudioOutputMode.FORCE_PASSTHROUGH) t(SettingsText.AO_PASSTHROUGH_DETAIL) else null,
        )
        out += choice(
            PlaybackRows.ASPECT, t(SettingsText.PB_ASPECT),
            ASPECTS.map { (m, k) -> ChoiceOption(m.name, t(k)) }, p.defaultAspect.name,
        )
        out += pauseRewindLink(p)
        out += choice(
            PlaybackRows.ON_LAUNCH, t(SettingsText.PB_ON_LAUNCH),
            listOf(
                OnLaunch.GUIDE_WITH_LAST to SettingsText.LAUNCH_GUIDE_LAST, OnLaunch.FULL_LAST to SettingsText.LAUNCH_FULL_LAST,
                OnLaunch.GUIDE_NOTHING to SettingsText.LAUNCH_GUIDE_NOTHING,
            ).map { (m, k) -> ChoiceOption(m.name, t(k)) },
            pl.onLaunch.name,
        )
        val langs = listOf(ChoiceOption(DEVICE_LANGUAGE, t(SettingsText.LANG_DEVICE))) + LANGUAGES.map { ChoiceOption(it, languageName(it)) }
        val firstLang = p.preferredAudioLanguages.firstOrNull() ?: DEVICE_LANGUAGE
        out += choice(PlaybackRows.AUDIO_LANGUAGE, t(SettingsText.PB_AUDIO_LANGUAGE), withCurrent(langs, firstLang) { languageName(it) }, firstLang)
        // (§9 F21 unverified) "Forced only" ships as Off/On until Media3's forced-track selection is checked.
        out += choice(
            PlaybackRows.SUBTITLES, t(SettingsText.PB_SUBTITLES),
            listOf(ChoiceOption(SubtitleMode.OFF.name, t(SettingsText.SUBS_OFF)), ChoiceOption(SubtitleMode.ON.name, t(SettingsText.SUBS_ON))),
            if (p.subtitles == SubtitleMode.OFF) SubtitleMode.OFF.name else SubtitleMode.ON.name,
        )
        out += choice(
            PlaybackRows.DECODER, t(SettingsText.PB_DECODER),
            DECODERS.map { (m, k) -> ChoiceOption(m.name, t(k)) }, p.videoDecoderMode.name,
        )
        out += toggle(PlaybackRows.FFMPEG, t(SettingsText.PB_FFMPEG), p.softwareAudioFallback, t(SettingsText.PB_FFMPEG_DESC))
        val running = env.runningSoftwareAudioFallback()
        if (running != null && running != p.softwareAudioFallback) {
            out += row(PlaybackRows.RESTART_NOW, t(SettingsText.PB_RESTART_NOW), RowKind.Action(), description = t(SettingsText.PB_RESTART_NOW_DESC))
        }
        out += toggle(PlaybackRows.TUNNELING, t(SettingsText.PB_TUNNELING), p.tunneling, t(SettingsText.PB_TUNNELING_DESC))
        out += choice(
            PlaybackRows.RECONNECT, t(SettingsText.PB_RECONNECT),
            RECONNECTS.map { ChoiceOption(it.toString(), if (it < 0) t(SettingsText.RECONNECT_UNLIMITED) else t(SettingsText.COUNT, it)) },
            p.reconnectAttempts.toString(),
        )
        out += toggle(PlaybackRows.FAILOVER, t(SettingsText.PB_FAILOVER), p.failoverToBackup, t(SettingsText.PB_FAILOVER_DESC))
        out += choice(
            PlaybackRows.AFTER_STANDBY, t(SettingsText.PB_AFTER_STANDBY),
            listOf(ChoiceOption(RESUME, t(SettingsText.STANDBY_RESUME)), ChoiceOption(GUIDE, t(SettingsText.STANDBY_GUIDE))),
            if (p.resumeAfterStandby) RESUME else GUIDE,
        )
        out += choice(
            PlaybackRows.HDR_PREVIEW, t(SettingsText.PB_HDR_PREVIEW),
            listOf(ChoiceOption(HdrPreviewMode.POSTER.name, t(SettingsText.HDR_POSTER)), ChoiceOption(HdrPreviewMode.ALLOW.name, t(SettingsText.HDR_PLAY))),
            p.hdrInPreview.name,
        )
        out += choice(PlaybackRows.INFO_BAR, t(SettingsText.PB_INFO_BAR), INFO_BAR_MS.map { ChoiceOption(it.toString(), seconds(it)) }, pl.infoBarMs.toString())
        out += choice(PlaybackRows.NUMBER_WAIT, t(SettingsText.PB_NUMBER_WAIT), NUMBER_WAIT_MS.map { ChoiceOption(it.toString(), seconds(it)) }, pl.numberEntryWaitMs.toString())
        val players = env.externalPlayers()
        val ext = listOf(ChoiceOption(NO_PLAYER, t(SettingsText.NONE))) + players.map { ChoiceOption(it.packageName, it.label) }
        out += choice(
            PlaybackRows.EXTERNAL_PLAYER, t(SettingsText.PB_EXTERNAL), withCurrent(ext, p.externalPlayer ?: NO_PLAYER) { it },
            p.externalPlayer ?: NO_PLAYER, if (players.isEmpty()) t(SettingsText.PB_EXTERNAL_NONE_INSTALLED) else null,
        )
        return out
    }

    /**
     * (WP-S4, docs/design/m6-storage-locations.md §9.1) The Playback page's "Pause and rewind" Link to
     * [SettingsRoute.PauseRewind]: SIMPLE, effect NONE. Value `<length> · <place>` ("1 hour · NAS", "Auto (1 h 11 min) ·
     * USB drive"), just "Off" when rewind is Off; while the cycle falls back: amber "1 hour · This device (NAS offline)".
     */
    private fun pauseRewindLink(p: PlaybackPrefs): SettingsRow {
        val place = places.current()
        val length = timeshiftRows(p, place).first { it.id == PauseRewindRows.TIMESHIFT }.value.orEmpty()
        val offline = place.offlineName?.let(::linkName)
        // O-6 (closure fix pass): a folder demoted because it refused the ring file (ACCESS) falls back as "sign in"
        // (StorageTargetsImpl: ACCESS → SIGN_IN_NEEDED), so the link says the same as the toast.
        val signIn = place.problem == app.thebox.core.storage.LocationState.SIGN_IN_NEEDED ||
            (place.problem == app.thebox.core.storage.LocationState.DEMOTED && place.failure == app.thebox.core.storage.StorageFailure.ACCESS)
        val value = when {
            p.timeshift == TimeshiftLength.OFF || place.label.isBlank() -> length
            // U-N2: a sign-in is not "offline"; the value says what is needed.
            offline != null && signIn -> StorageMarks.warn(t(SettingsText.PB_PAUSE_REWIND_VALUE_SIGN_IN, length, offline))
            offline != null -> StorageMarks.warn(t(SettingsText.PB_PAUSE_REWIND_VALUE_FALLBACK, length, offline))
            else -> t(SettingsText.PB_PAUSE_REWIND_VALUE, length, linkName(place.label))
        }
        return link(PlaybackRows.PAUSE_REWIND, SettingsRoute.PauseRewind, t(SettingsText.PB_TIMESHIFT), t(SettingsText.PB_PAUSE_REWIND_DESC)).copy(value = value)
    }

    /** Q-D7 (closure fix pass): the place's name in the link's value is at most [StorageConstants.LABEL_LINK_MAX_CHARS], "…" last. */
    private fun linkName(name: String): String {
        val max = app.thebox.core.storage.StorageConstants.LABEL_LINK_MAX_CHARS
        return if (name.length <= max) name else name.take(max - 1).trimEnd() + "…"
    }

    /**
     * (M6 time-shift, docs/design/m6-timeshift.md §8.2, §12) The three rows with their values. Auto is the length
     * `TimeshiftPolicy` gives the place's storage class (30 min on ≤ 16 GB, else 1 hour; 1 hour on a USB drive or a network
     * folder); when free space caps the ring the value reads "Auto (22 min: storage is limited)" and the description uses
     * the capped window and size. Off deletes the file at once ([WpaPageEnv.timeshiftOff]; the row itself is NEXT_TUNE).
     * Unknown storage: Auto reads as the small-storage length.
     *
     * (WP-S4, storage locations §3.2, §9.1) For a USB drive the 2-hour option carries "Up to 1 h 11 min on this drive"
     * (the FAT32 file cap) and Auto reads "Auto (1 h 11 min: USB drive is limited)"; the sizes follow the place ("Uses up
     * to 1.9 GB on NAS ..."); "Protect built-in storage" says it is not used while saving elsewhere. [place] defaults to
     * this device, where nothing changes. `StoragePages` builds the page; the rows keep their ids, so
     * [apply] still writes them (owner "A").
     */
    fun timeshiftRows(p: PlaybackPrefs, place: TimeshiftPlace = TimeshiftPlace.DEVICE): List<SettingsRow> {
        val internal = place.kind == StorageKind.INTERNAL
        val storage = if (internal) env.timeshiftStorage() else place.facts
        val limits = if (internal) null else StorageLimits.of(place.kind, 0L) // Q-U10: the contract's kind → limits
        val autoMs = if (limits != null) POLICY.lengthMs(TimeshiftLength.AUTO, 0L, limits)!!
        else storage?.let { POLICY.lengthMs(TimeshiftLength.AUTO, it.totalBytes) } ?: TimeshiftConstants.AUTO_LENGTH_SMALL_MS
        val autoSized = sized(autoMs, storage, limits)
        val autoLabel = when {
            !autoSized.limited -> t(SettingsText.TS_AUTO, lengthText(autoMs))
            place.kind == StorageKind.VOLUME -> t(SettingsText.TS_AUTO_LIMITED_USB, lengthText(autoSized.windowMs))
            else -> t(SettingsText.TS_AUTO_LIMITED, lengthText(autoSized.windowMs))
        }
        // The USB drive's file cap shortens the longest option; it says so up front (§3.2).
        fun detail(lengthMs: Long): String? {
            if (place.kind != StorageKind.VOLUME) return null
            val w = sized(lengthMs, storage, limits)
            return if (w.windowMs < lengthMs) t(SettingsText.TS_UP_TO_ON_DRIVE, lengthText(w.windowMs)) else null
        }
        val lengths = listOf(
            TimeshiftLength.AUTO to (autoLabel to null),
            TimeshiftLength.MIN_15 to (lengthText(15 * MINUTE_MS) to detail(15 * MINUTE_MS)),
            TimeshiftLength.MIN_30 to (lengthText(30 * MINUTE_MS) to detail(30 * MINUTE_MS)),
            TimeshiftLength.H_1 to (lengthText(60 * MINUTE_MS) to detail(60 * MINUTE_MS)),
            TimeshiftLength.H_2 to (lengthText(120 * MINUTE_MS) to detail(120 * MINUTE_MS)),
            TimeshiftLength.PAUSE_ONLY to (t(SettingsText.TS_PAUSE_ONLY) to null),
            TimeshiftLength.OFF to (t(SettingsText.OFF) to null),
        ).map { (m, ld) -> ChoiceOption(m.name, ld.first, detail = ld.second) }
        val lengthMs = TIMESHIFT_LENGTH_MS[p.timeshift] ?: autoMs
        val shown = sized(lengthMs, storage, limits)
        val sizeGb = String.format(Locale.ROOT, "%.1f", shown.bytes / GIB)
        val desc = (if (internal) t(SettingsText.PB_TIMESHIFT_DESC, lengthText(shown.windowMs), t(SettingsText.TS_GB, sizeGb))
        else t(SettingsText.PB_TIMESHIFT_DESC_AT, lengthText(shown.windowMs), t(SettingsText.TS_GB, sizeGb), place.sentence)).let {
            if (p.timeshift == TimeshiftLength.PAUSE_ONLY) it + " " + t(SettingsText.PB_TIMESHIFT_PAUSE_ONLY_DESC) else it
        }
        val holds = listOf(
            TimeshiftHold.AUTO to t(SettingsText.AUTO), TimeshiftHold.MIN_10 to lengthText(10 * MINUTE_MS),
            TimeshiftHold.MIN_30 to lengthText(30 * MINUTE_MS), TimeshiftHold.H_1 to lengthText(60 * MINUTE_MS),
            TimeshiftHold.UNTIL_FULL to t(SettingsText.HOLD_UNTIL_FULL),
        ).map { (m, label) -> ChoiceOption(m.name, label) }
        val protect = listOf(StorageProtection.AUTO to SettingsText.AUTO, StorageProtection.OFF to SettingsText.OFF).map { (m, k) -> ChoiceOption(m.name, t(k)) }
        val protectDesc = t(SettingsText.PB_TIMESHIFT_PROTECT_DESC).let { if (internal) it else it + " " + t(SettingsText.PB_TIMESHIFT_PROTECT_ELSEWHERE, place.sentence) }
        return listOf(
            choice(PauseRewindRows.TIMESHIFT, t(SettingsText.PB_TIMESHIFT_LENGTH), lengths, p.timeshift.name, desc),
            choice(PauseRewindRows.TIMESHIFT_HOLD, t(SettingsText.PB_TIMESHIFT_HOLD), holds, p.timeshiftHold.name, t(SettingsText.PB_TIMESHIFT_HOLD_DESC)),
            choice(PauseRewindRows.TIMESHIFT_PROTECT, t(SettingsText.PB_TIMESHIFT_PROTECT), protect, p.storageProtection.name, protectDesc),
        )
    }

    private class Sized(val windowMs: Long, val bytes: Double, val limited: Boolean)

    /**
     * §8.2 for [lengthMs] at the assumed rate: the ring's size and the window it holds. Unknown storage: the length and
     * `length × rate × 1.15`. Known: `TimeshiftPolicy.capacityBytes`; the window is `min(length, capacity ÷ rate)`, and
     * "limited" when that is shorter than the length.
     */
    private fun sized(lengthMs: Long, storage: TimeshiftStorageFacts?, limits: StorageLimits? = null): Sized {
        val rate = TimeshiftConstants.ASSUMED_BYTES_PER_SEC
        val wanted = lengthMs / 1000.0 * rate * TimeshiftConstants.CAPACITY_HEADROOM
        if (limits != null) {
            // (WP-S4) A USB drive or a network folder: the kind's cap and reserve; unknown free space = no limit from space.
            val capacity = POLICY.capacityBytes(lengthMs, rate, storage?.availableBytes ?: UNKNOWN_FREE, limits, storage?.currentFileBytes ?: 0L)
            val window = POLICY.effectiveWindowMs(lengthMs, capacity, rate) / MINUTE_MS * MINUTE_MS
            return Sized(window, minOf(wanted, capacity.toDouble()), limited = window < lengthMs)
        }
        if (storage == null) return Sized(lengthMs, wanted, limited = false)
        val capacity = POLICY.capacityBytes(lengthMs, rate, storage.availableBytes, storage.totalBytes, storage.currentFileBytes)
        val window = POLICY.effectiveWindowMs(lengthMs, capacity, rate)
        // Whole minutes, as the setting's lengths (a few seconds short of the length is not "limited").
        val windowMin = window / MINUTE_MS * MINUTE_MS
        return Sized(windowMin, minOf(wanted, capacity.toDouble()), limited = windowMin < lengthMs)
    }

    /** "15 min", "1 hour", "2 hours". */
    private fun lengthText(ms: Long): String {
        val min = (ms / MINUTE_MS).toInt()
        return when {
            min < 60 -> t(SettingsText.TS_MIN, min)
            min % 60 != 0 -> t(SettingsText.TS_HOURS_MIN, min / 60, min % 60) // (WP-S4) "1 h 11 min": a USB drive's cap
            min == 60 -> t(SettingsText.TS_HOUR)
            else -> t(SettingsText.TS_HOURS, min / 60)
        }
    }

    // ---------------------------------------------------------------- Interface (§3.3.6)

    private fun interfaceRows(): List<SettingsRow> {
        val g = store.flow(GuideDomain).value
        val out = ArrayList<SettingsRow>()
        out += choice(
            InterfaceRows.CLOCK, t(SettingsText.IF_CLOCK),
            listOf(ClockMode.SYSTEM to SettingsText.CLOCK_SYSTEM, ClockMode.H12 to SettingsText.CLOCK_12, ClockMode.H24 to SettingsText.CLOCK_24)
                .map { (m, k) -> ChoiceOption(m.name, t(k)) },
            g.clock.name,
        )
        val still = GuidePrefsMapping.miniStill(g, env.deviceLowEnd)
        val miniOptions = listOf(ChoiceOption(VIDEO, t(SettingsText.MINI_VIDEO)), ChoiceOption(STILL, t(SettingsText.MINI_STILL)))
        out += choice(InterfaceRows.MINI_PLAYER, t(SettingsText.IF_MINI), miniOptions, if (still) STILL else VIDEO).let { r ->
            if (g.miniStill == null) r.copy(value = t(SettingsText.AUTOMATIC, r.value ?: "")) else r
        }
        out += choice(
            InterfaceRows.PERFORMANCE, t(SettingsText.IF_PERFORMANCE),
            listOf(PerformanceMode.AUTO to SettingsText.PERF_AUTO, PerformanceMode.HIGH to SettingsText.PERF_HIGH, PerformanceMode.LOW to SettingsText.PERF_LOW)
                .map { (m, k) -> ChoiceOption(m.name, t(k)) },
            g.performance.name,
        )
        out += toggle(InterfaceRows.LOGOS, t(SettingsText.IF_LOGOS), g.showLogos)
        val ambient = GuidePrefsMapping.ambient(g, env.deviceLowEnd)
        out += toggle(InterfaceRows.AMBIENT, t(SettingsText.IF_AMBIENT), ambient).let { r ->
            if (g.ambient == null) r.copy(value = t(SettingsText.AUTOMATIC, t(if (ambient) SettingsText.ON else SettingsText.OFF))) else r
        }
        out += toggle(InterfaceRows.NUMBERS, t(SettingsText.IF_NUMBERS), g.showNumbers)
        out += toggle(InterfaceRows.REMINDER_SWITCH, t(SettingsText.IF_REMINDER_SWITCH), g.reminderAutoSwitch)
        out += choice(
            InterfaceRows.REMINDER_LEAD, t(SettingsText.IF_REMINDER_LEAD),
            withCurrent(REMINDER_LEAD_SEC.map { ChoiceOption(it.toString(), t(SettingsText.LEAD_MIN, it / 60)) }, g.reminderLeadSec.toString()) {
                t(SettingsText.LEAD_MIN, (it.toIntOrNull() ?: 60) / 60)
            },
            g.reminderLeadSec.toString(),
        )
        return out
    }

    // ---------------------------------------------------------------- Remote control (§3.3.7)

    private fun remoteRows(): List<SettingsRow> {
        val pl = store.flow(PlayerDomain).value
        val g = store.flow(GuideDomain).value
        val n = env.keyOverrideCount()
        val out = ArrayList<SettingsRow>()
        out += link(RemoteRows.MAPPING, SettingsRoute.ButtonMapping, t(SettingsText.RM_MAPPING)).copy(
            value = when (n) { 0 -> t(SettingsText.RM_DEFAULTS); 1 -> t(SettingsText.RM_ONE_CHANGE); else -> t(SettingsText.RM_CHANGES, n) },
        )
        out += choice(
            RemoteRows.UPDOWN, t(SettingsText.RM_UPDOWN),
            listOf(UpDownInPlayer.QUICK_BAR_AND_INFO to SettingsText.UD_QUICK, UpDownInPlayer.ZAP to SettingsText.UD_ZAP, UpDownInPlayer.CHANNEL_LIST to SettingsText.UD_LIST)
                .map { (m, k) -> ChoiceOption(m.name, t(k)) },
            pl.upDown.name,
        )
        val zap = pl.upDown == UpDownInPlayer.ZAP
        out += toggle(RemoteRows.INVERT, t(SettingsText.RM_INVERT), pl.invertChannelDirection).copy(
            enabled = zap, disabledReason = if (zap) null else t(SettingsText.RM_INVERT_REASON),
        )
        out += choice(
            RemoteRows.BACK, t(SettingsText.RM_BACK),
            listOf(BackInPlayer.GUIDE to SettingsText.BACK_GUIDE, BackInPlayer.CHANNEL_LIST to SettingsText.BACK_LIST).map { (m, k) -> ChoiceOption(m.name, t(k)) },
            pl.back.name,
        )
        out += choice(
            RemoteRows.EXIT, t(SettingsText.RM_EXIT),
            listOf(ExitConfirmation.BACK_TWICE to SettingsText.EXIT_TWICE, ExitConfirmation.ASK to SettingsText.EXIT_ASK, ExitConfirmation.NONE to SettingsText.EXIT_NEVER)
                .map { (m, k) -> ChoiceOption(m.name, t(k)) },
            g.exitConfirmation.name,
        )
        return out
    }

    // ---------------------------------------------------------------- Network (§3.3.11)

    private fun networkRows(): List<SettingsRow> {
        val n = store.flow(NetworkDomain).value
        val out = ArrayList<SettingsRow>()
        out += choice(
            NetworkRows.DNS, t(SettingsText.NET_DNS),
            listOf(
                DnsChoice.SYSTEM to SettingsText.DNS_SYSTEM, DnsChoice.CLOUDFLARE to SettingsText.DNS_CLOUDFLARE, DnsChoice.GOOGLE to SettingsText.DNS_GOOGLE,
                DnsChoice.QUAD9 to SettingsText.DNS_QUAD9, DnsChoice.CUSTOM to SettingsText.DNS_CUSTOM,
            ).map { (m, k) -> ChoiceOption(m.name, t(k)) },
            n.dns.name, t(SettingsText.NET_DNS_DESC),
        )
        if (n.dns == DnsChoice.CUSTOM) {
            val url = n.customDohUrl.orEmpty()
            out += row(
                NetworkRows.CUSTOM_DOH, t(SettingsText.NET_CUSTOM_DOH), RowKind.Text(url, TextHint.URL, chips = DOH_CHIPS),
                value = url.ifEmpty { t(SettingsText.NOT_SET) },
            )
        }
        out += choice(
            NetworkRows.IPV4, t(SettingsText.NET_IPV4),
            listOf(
                ChoiceOption(PreferIpv4.AUTO.name, t(SettingsText.IPV4_AUTO), detail = t(SettingsText.IPV4_AUTO_DETAIL)),
                ChoiceOption(PreferIpv4.ON.name, t(SettingsText.ON)), ChoiceOption(PreferIpv4.OFF.name, t(SettingsText.OFF)),
            ),
            n.preferIpv4.name,
        )
        out += choice(
            NetworkRows.CONNECT_TIMEOUT, t(SettingsText.NET_CONNECT_TIMEOUT),
            withCurrent(CONNECT_TIMEOUTS.map { ChoiceOption(it.toString(), t(SettingsText.SECONDS, it.toString())) }, n.connectTimeoutSec.toString()) { t(SettingsText.SECONDS, it) },
            n.connectTimeoutSec.toString(), t(SettingsText.NET_TIMEOUT_DESC),
        )
        out += choice(
            NetworkRows.READ_TIMEOUT, t(SettingsText.NET_READ_TIMEOUT),
            withCurrent(READ_TIMEOUTS.map { ChoiceOption(it.toString(), t(SettingsText.SECONDS, it.toString())) }, n.readTimeoutSec.toString()) { t(SettingsText.SECONDS, it) },
            n.readTimeoutSec.toString(), t(SettingsText.NET_TIMEOUT_DESC),
        )
        return out
    }

    // ---------------------------------------------------------------- intents

    override suspend fun onIntent(route: SettingsRoute, intent: RowIntent, ctx: PageContext): Boolean = apply(intent)

    /** Writes [intent]; true when it is one of WP-A's section rows (or the advanced toggle). */
    fun apply(intent: RowIntent): Boolean {
        val id = intent.row
        if (id == SettingsMachineImpl.ADVANCED_ROW) {
            val on = (intent as? RowIntent.Toggle)?.on ?: return true
            store.update(GuideDomain) { it.copy(advancedSettings = on) }
            return true
        }
        val spec = SettingsTree.spec(id) ?: return false
        if (spec.section !in SECTIONS || spec.owner != "A") return false
        val choose = (intent as? RowIntent.Choose)?.optionId
        val on = (intent as? RowIntent.Toggle)?.on
        when (id) {
            GuideRows.INTERVAL -> choose?.let { c ->
                store.update(DataDomain) { d ->
                    if (c == DAILY) d.copy(epgDailyAtHour = d.epgDailyAtHour ?: DEFAULT_DAILY_HOUR)
                    else d.copy(epgRefreshIntervalH = c.toIntOrNull() ?: d.epgRefreshIntervalH, epgDailyAtHour = null)
                }
            }
            GuideRows.DAILY_HOUR -> (intent as? RowIntent.SetNumber)?.let { n -> store.update(DataDomain) { it.copy(epgDailyAtHour = n.value.coerceIn(0, 23)) } }
            GuideRows.MATCHING -> choose?.let { c -> enumOf<MatchLevel>(c)?.let { l -> store.update(DataDomain) { it.copy(matchLevel = l) } } }
            GuideRows.SHIFT -> choose?.toIntOrNull()?.let { m -> store.update(DataDomain) { it.copy(globalShiftMin = m) } }
            GuideRows.PAST_DAYS -> choose?.toIntOrNull()?.let { v -> store.update(DataDomain) { it.copy(epgPastDays = v) } }
            GuideRows.FUTURE_DAYS -> choose?.toIntOrNull()?.let { v -> store.update(DataDomain) { it.copy(epgFutureDays = v) } }
            GuideRows.DESCRIPTIONS -> on?.let { v -> store.update(DataDomain) { it.copy(storeDescriptions = v) } }
            GuideRows.POSTERS -> on?.let { v -> store.update(GuideDomain) { it.copy(posters = v) } }

            PlaybackRows.BUFFER -> choose?.let { c -> enumOf<BufferMode>(c)?.let { m -> playback { it.copy(bufferMode = m) } } }
            PlaybackRows.AFR -> choose?.let { c -> enumOf<AfrMode>(c)?.let { m -> playback { it.copy(afrMode = m) } } }
            PlaybackRows.AUDIO_OUTPUT -> choose?.let { c -> enumOf<AudioOutputMode>(c)?.let { m -> playback { it.copy(audioOutput = m) } } }
            PlaybackRows.ASPECT -> choose?.let { c -> enumOf<AspectMode>(c)?.let { m -> playback { it.copy(defaultAspect = m) } } }
            PlaybackRows.ON_LAUNCH -> choose?.let { c -> enumOf<OnLaunch>(c)?.let { m -> player { it.copy(onLaunch = m) } } }
            PauseRewindRows.TIMESHIFT -> choose?.let { c ->
                enumOf<TimeshiftLength>(c)?.let { m ->
                    playback { it.copy(timeshift = m) }
                    if (m == TimeshiftLength.OFF) env.timeshiftOff() // §12: Off deletes the file at once
                }
            }
            PauseRewindRows.TIMESHIFT_HOLD -> choose?.let { c -> enumOf<TimeshiftHold>(c)?.let { m -> playback { it.copy(timeshiftHold = m) } } }
            PauseRewindRows.TIMESHIFT_PROTECT -> choose?.let { c -> enumOf<StorageProtection>(c)?.let { m -> playback { it.copy(storageProtection = m) } } }
            PlaybackRows.AUDIO_LANGUAGE -> choose?.let { c ->
                playback { p ->
                    val rest = p.preferredAudioLanguages.drop(1).filter { it != c }
                    p.copy(preferredAudioLanguages = if (c == DEVICE_LANGUAGE) emptyList() else listOf(c) + rest)
                }
            }
            PlaybackRows.SUBTITLES -> choose?.let { c -> enumOf<SubtitleMode>(c)?.let { m -> playback { it.copy(subtitles = m) } } }
            PlaybackRows.DECODER -> choose?.let { c -> enumOf<VideoDecoderMode>(c)?.let { m -> playback { it.copy(videoDecoderMode = m) } } }
            PlaybackRows.FFMPEG -> on?.let { v -> playback { it.copy(softwareAudioFallback = v) } }
            PlaybackRows.RESTART_NOW -> if (intent is RowIntent.Run) env.restartNow()
            PlaybackRows.TUNNELING -> on?.let { v -> playback { it.copy(tunneling = v) } }
            PlaybackRows.RECONNECT -> choose?.toIntOrNull()?.let { v -> playback { it.copy(reconnectAttempts = v) } }
            PlaybackRows.FAILOVER -> on?.let { v -> playback { it.copy(failoverToBackup = v) } }
            PlaybackRows.AFTER_STANDBY -> choose?.let { c -> playback { it.copy(resumeAfterStandby = c == RESUME) } }
            PlaybackRows.HDR_PREVIEW -> choose?.let { c -> enumOf<HdrPreviewMode>(c)?.let { m -> playback { it.copy(hdrInPreview = m) } } }
            PlaybackRows.INFO_BAR -> choose?.toLongOrNull()?.let { v -> player { it.copy(infoBarMs = v) } }
            PlaybackRows.NUMBER_WAIT -> choose?.toLongOrNull()?.let { v -> player { it.copy(numberEntryWaitMs = v) } }
            PlaybackRows.EXTERNAL_PLAYER -> choose?.let { c -> playback { it.copy(externalPlayer = c.takeIf { p -> p != NO_PLAYER }) } }

            InterfaceRows.CLOCK -> choose?.let { c -> enumOf<ClockMode>(c)?.let { m -> guide { it.copy(clock = m) } } }
            InterfaceRows.MINI_PLAYER -> choose?.let { c -> guide { it.copy(miniStill = c == STILL) } }
            InterfaceRows.PERFORMANCE -> choose?.let { c -> enumOf<PerformanceMode>(c)?.let { m -> guide { it.copy(performance = m) } } }
            InterfaceRows.LOGOS -> on?.let { v -> guide { it.copy(showLogos = v) } }
            InterfaceRows.AMBIENT -> on?.let { v -> guide { it.copy(ambient = v) } }
            InterfaceRows.NUMBERS -> on?.let { v -> guide { it.copy(showNumbers = v) } }
            InterfaceRows.REMINDER_SWITCH -> on?.let { v -> guide { it.copy(reminderAutoSwitch = v) } }
            InterfaceRows.REMINDER_LEAD -> choose?.toIntOrNull()?.let { v -> guide { it.copy(reminderLeadSec = v) } }

            RemoteRows.UPDOWN -> choose?.let { c -> enumOf<UpDownInPlayer>(c)?.let { m -> player { it.copy(upDown = m) } } }
            RemoteRows.INVERT -> on?.let { v -> player { it.copy(invertChannelDirection = v) } }
            RemoteRows.BACK -> choose?.let { c -> enumOf<BackInPlayer>(c)?.let { m -> player { it.copy(back = m) } } }
            RemoteRows.EXIT -> choose?.let { c -> enumOf<ExitConfirmation>(c)?.let { m -> guide { it.copy(exitConfirmation = m) } } }

            NetworkRows.DNS -> choose?.let { c -> enumOf<DnsChoice>(c)?.let { m -> network { it.copy(dns = m) } } }
            NetworkRows.CUSTOM_DOH -> (intent as? RowIntent.Edit)?.let { e -> network { it.copy(customDohUrl = e.text.trim().ifEmpty { null }) } }
            NetworkRows.IPV4 -> choose?.let { c -> enumOf<PreferIpv4>(c)?.let { m -> network { it.copy(preferIpv4 = m) } } }
            NetworkRows.CONNECT_TIMEOUT -> choose?.toIntOrNull()?.let { v -> network { it.copy(connectTimeoutSec = v) } }
            NetworkRows.READ_TIMEOUT -> choose?.toIntOrNull()?.let { v -> network { it.copy(readTimeoutSec = v) } }
            else -> Unit // links (sources, status, fix, mapping) push routes in the machine
        }
        return true
    }

    private fun guide(f: (GuidePrefs) -> GuidePrefs) = store.update(GuideDomain, f)
    private fun player(f: (PlayerSettings) -> PlayerSettings) = store.update(PlayerDomain, f)
    private fun playback(f: (PlaybackPrefs) -> PlaybackPrefs) = store.update(PlaybackDomain, f)
    private fun network(f: (NetworkSettings) -> NetworkSettings) = store.update(NetworkDomain, f)

    // ---------------------------------------------------------------- row helpers

    private fun t(key: SettingsText, vararg args: Any) = texts.get(key, *args)

    private fun seconds(ms: Long): String {
        val s = if (ms % 1000 == 0L) (ms / 1000).toString() else String.format(Locale.ROOT, "%.1f", ms / 1000.0)
        return t(SettingsText.SECONDS, s)
    }

    private fun languageName(code: String): String =
        Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }.ifEmpty { code }

    /** Effect text for [id]'s timing (the description states it whenever it is not LIVE, §2.1). */
    private fun effectText(id: RowId): String? = when (SettingsTree.spec(id)?.effect) {
        EffectTiming.NEXT_TUNE -> t(SettingsText.EFFECT_NEXT_TUNE)
        EffectTiming.REPREPARE -> t(SettingsText.EFFECT_REPREPARE)
        EffectTiming.NEXT_REFRESH -> t(SettingsText.EFFECT_NEXT_REFRESH)
        EffectTiming.NEXT_EPG_IMPORT -> t(SettingsText.EFFECT_NEXT_EPG_IMPORT)
        EffectTiming.NEXT_LAUNCH -> t(SettingsText.EFFECT_NEXT_LAUNCH)
        EffectTiming.RESTART -> t(SettingsText.EFFECT_RESTART)
        EffectTiming.LIVE, EffectTiming.NONE, null -> null
    }

    private fun row(id: RowId, label: String, kind: RowKind, value: String? = null, description: String? = null): SettingsRow {
        // guide.fix_unmatched is WP-B's spec (MatchingRows); until it is registered the row is a simple LIVE link.
        val spec = SettingsTree.spec(id)
            ?: if (id == GuideRows.FIX_UNMATCHED) app.thebox.core.settings.tree.RowSpec(id, SectionId.GUIDE, Visibility.SIMPLE, EffectTiming.LIVE, "B")
            else error("row ${id.key} has no spec")
        val effect = effectText(id)
        val desc = when {
            description != null && effect != null -> description + t(SettingsText.SEP) + effect
            else -> description ?: effect
        }
        return SettingsRow(id = id, label = label, value = value, description = desc, kind = kind, visibility = spec.visibility, effect = spec.effect)
    }

    private fun choice(id: RowId, label: String, options: List<ChoiceOption>, selectedId: String, description: String? = null): SettingsRow {
        val sel = options.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
        return row(id, label, RowKind.Choice(options, sel), value = options.getOrNull(sel)?.label, description = description)
    }

    private fun toggle(id: RowId, label: String, on: Boolean, description: String? = null): SettingsRow =
        row(id, label, RowKind.Toggle(on), value = null, description = description)

    private fun link(id: RowId, route: SettingsRoute, label: String, description: String? = null): SettingsRow =
        row(id, label, RowKind.Link(route), description = description)

    /** Keeps a stored value that is not one of [options] selectable (a restored or older value is shown as is). */
    private fun withCurrent(options: List<ChoiceOption>, current: String, label: (String) -> String): List<ChoiceOption> =
        if (options.any { it.id == current }) options else options + ChoiceOption(current, label(current))

    companion object {
        val SECTIONS = setOf(SectionId.GUIDE, SectionId.PLAYBACK, SectionId.INTERFACE, SectionId.REMOTE, SectionId.NETWORK)

        const val DAILY = "daily"
        /** "Daily at a set time" starts at 03:00 until the user picks an hour. */
        const val DEFAULT_DAILY_HOUR = 3
        val INTERVALS = listOf(6, 12, 24)
        /** −12 h … +12 h in 0.5 h (§3.3.2). */
        const val SHIFT_STEPS = 24
        val PAST_DAYS = listOf(0, 1, 2, 3, 7)
        val FUTURE_DAYS = listOf(3, 7, 14)
        val RECONNECTS = listOf(3, 5, 10, -1)
        val INFO_BAR_MS = listOf(3_000L, 5_000L, 8_000L)
        val NUMBER_WAIT_MS = listOf(1_000L, 1_500L, 2_500L)
        val REMINDER_LEAD_SEC = listOf(60, 120, 300)
        val CONNECT_TIMEOUTS = listOf(5, 10, 20)
        val READ_TIMEOUTS = listOf(10, 20, 30, 40, 60)
        val DOH_CHIPS = listOf("https://", "/dns-query", ".com")
        /** Frame-rate "Seamless only" needs API 30 (N5). */
        const val AFR_SEAMLESS_MIN_SDK = 30
        const val DEVICE_LANGUAGE = ""
        const val NO_PLAYER = ""
        const val RESUME = "resume"
        const val GUIDE = "guide"
        const val VIDEO = "video"
        const val STILL = "still"
        private const val MINUTE_MS = 60_000L
        private const val GIB = 1024.0 * 1024 * 1024
        /** (WP-S4) A network folder's free space is unknown until the keeper reports it: no limit from space. */
        private const val UNKNOWN_FREE = Long.MAX_VALUE / 4
        /** (M6 commit 0) Fixed lengths of the "Pause and rewind" values; AUTO, PAUSE_ONLY and OFF are not here. */
        private val POLICY = app.thebox.core.playback.timeshift.TimeshiftPolicyImpl()
        private val TIMESHIFT_LENGTH_MS = mapOf(
            TimeshiftLength.MIN_15 to 15 * MINUTE_MS, TimeshiftLength.MIN_30 to 30 * MINUTE_MS,
            TimeshiftLength.H_1 to 60 * MINUTE_MS, TimeshiftLength.H_2 to 120 * MINUTE_MS,
        )

        /** ISO 639-1 codes offered for the preferred audio language (shown by the platform's display name). */
        val LANGUAGES = listOf(
            "en", "fr", "de", "es", "it", "pt", "nl", "sv", "nb", "da", "fi", "pl", "cs", "sk", "hu", "ro", "bg", "el", "tr",
            "ru", "uk", "sr", "hr", "sl", "sq", "ar", "he", "fa", "hi", "ur", "bn", "ta", "zh", "ja", "ko", "th", "vi", "id", "ms",
        )

        private val ASPECTS = listOf(
            AspectMode.FIT to SettingsText.ASPECT_FIT, AspectMode.FILL to SettingsText.ASPECT_FILL, AspectMode.ZOOM to SettingsText.ASPECT_ZOOM,
            AspectMode.RATIO_16_9 to SettingsText.ASPECT_16_9, AspectMode.RATIO_4_3 to SettingsText.ASPECT_4_3,
        )
        private val DECODERS = listOf(
            VideoDecoderMode.AUTO to SettingsText.DEC_AUTO, VideoDecoderMode.HARDWARE_ONLY to SettingsText.DEC_HW,
            VideoDecoderMode.SOFTWARE_PREFERRED to SettingsText.DEC_SW,
        )

        /** "+1.5", "−12" (the minus sign is U+2212, as the guide's shift labels). */
        fun shiftHours(min: Int): String {
            val abs = kotlin.math.abs(min)
            val h = if (abs % 60 == 0) (abs / 60).toString() else String.format(Locale.ROOT, "%.1f", abs / 60.0)
            return (if (min < 0) "−" else "+") + h
        }

        /** Validators stricter than the hint's (§3.3.11: the custom DoH address is https:// only). */
        fun rowRules(texts: SettingsTexts): Map<RowId, (String) -> String?> = mapOf(
            // C-L8: exactly what BoxDnsPolicy will use (a URL it would ignore must not read as "Custom").
            NetworkRows.CUSTOM_DOH to { s: String -> if (s.isBlank() || app.thebox.core.net.BoxDnsPolicy.isValidCustomUrl(s.trim())) null else texts[SettingsText.ERR_HTTPS] },
            // (WP-S4, storage locations §7.2) "Computer name or address" takes the forms ManualAddressParser accepts.
            // C-N4 (closure fix pass): a ".." folder or a control character has its own reason and next step.
            StoragePages.ROW_ENTER to { s: String ->
                when (StoragePages.hostProblem(s)) {
                    null -> null
                    app.thebox.core.storage.ManualAddressParser.Reason.BAD_PATH -> texts[SettingsText.ERR_HOST_PATH]
                    else -> texts[SettingsText.ERR_HOST]
                }
            },
        )

        inline fun <reified E : Enum<E>> enumOf(name: String): E? = enumValues<E>().firstOrNull { it.name == name }
    }
}
