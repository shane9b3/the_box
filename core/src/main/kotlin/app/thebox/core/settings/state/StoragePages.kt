// WP-S4 (docs/design/m6-storage-locations.md §7.2-§7.8, §9, §17): the settings pages of "Save to" and the network-folder
// walk. Pure Kotlin in `:core`, against the contracts `StorageLocations`, `NetworkFolders`, `VolumeCatalog` and `PhoneLink`.
package app.thebox.core.settings.state

import app.thebox.core.common.BoxLog
import app.thebox.core.setup.lan.PhoneContext
import app.thebox.core.setup.lan.PhoneContextProvider
import app.thebox.core.setup.lan.PhoneLink
import app.thebox.core.setup.lan.PhoneSubmission
import app.thebox.core.settings.Badge
import app.thebox.core.settings.ChoiceOption
import app.thebox.core.settings.ConfirmSpec
import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.PageModel
import app.thebox.core.settings.PhonePurpose
import app.thebox.core.settings.RowId
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SettingsMachine
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.SettingsStore
import app.thebox.core.settings.TextHint
import app.thebox.core.settings.TimeshiftLength
import app.thebox.core.settings.Tone
import app.thebox.core.settings.Visibility
import app.thebox.core.settings.tree.PauseRewindRows
import app.thebox.core.storage.BrowseError
import app.thebox.core.storage.BrowseErrorKind
import app.thebox.core.storage.BrowseHandle
import app.thebox.core.storage.BrowseResult
import app.thebox.core.storage.FindState
import app.thebox.core.storage.FolderListing
import app.thebox.core.storage.FoundComputer
import app.thebox.core.storage.LocationState
import app.thebox.core.storage.LocationStatus
import app.thebox.core.storage.LocationTestResult
import app.thebox.core.storage.ManualAddressParser
import app.thebox.core.storage.NetworkFolders
import app.thebox.core.storage.ShareListing
import app.thebox.core.storage.SignInMode
import app.thebox.core.storage.SmbAddress
import app.thebox.core.storage.StorageConstants
import app.thebox.core.storage.StorageFailure
import app.thebox.core.storage.StorageKind
import app.thebox.core.storage.StorageLocations
import app.thebox.core.storage.StoragePurpose
import app.thebox.core.storage.TestPhase
import app.thebox.core.storage.TestProgress
import app.thebox.core.storage.TestVerdict
import app.thebox.core.storage.VolumeCatalog
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * (§9.2) One walk through "Add a network folder": a computer, a sign-in, a share, a folder, a Test. **Routes carry only
 * the [token]** (Must): the password is a [CharArray] kept here, zeroed when the session ends (§7.3); [toString] prints
 * the token only. [path] is the current folder inside [share] (`/`-separated, "" = the share root): entering a folder
 * changes it and pushes nothing (U-B3).
 */
class BrowseSession internal constructor(val token: Long, /** Non-null: a stored location's session (sign-in again, Test). */ val locationId: String? = null) {
    @Volatile var host: String? = null
    @Volatile var port: Int = StorageConstants.SMB_DEFAULT_PORT
    @Volatile var computerName: String? = null
    @Volatile var lastAddress: String? = null
    @Volatile var apple: Boolean = false
    @Volatile var share: String? = null
    @Volatile var path: String = ""
    @Volatile var user: String? = null
    @Volatile var domain: String? = null
    @Volatile var guest: Boolean = false
    @Volatile var handle: BrowseHandle? = null
    @Volatile var connecting: Boolean = false
    @Volatile var error: BrowseError? = null
    @Volatile var shares: ShareListing? = null
    @Volatile var loadingShares: Boolean = false
    @Volatile var folderList: FolderListing? = null
    @Volatile var folderKey: String? = null
    @Volatile var note: String? = null
    @Volatile var test: TestUi? = null
    @Volatile var fromPhone: Boolean = false
    @Volatile var received: String? = null
    @Volatile var focusUse: Boolean = false
    @Volatile var wrongPassword: Boolean = false
    @Volatile var attached: Boolean = false
    internal var connectJob: Job? = null
    internal var listJob: Job? = null
    internal var sharesJob: Job? = null
    internal var testJob: Job? = null
    internal var phoneJob: Job? = null
    internal val routes: MutableSet<SettingsRoute> = ConcurrentHashMap.newKeySet()
    private var password: CharArray? = null
    private val lock = Any()

    fun setPassword(chars: CharArray?) = synchronized(lock) { password?.fill('\u0000'); password = chars }
    fun hasPassword(): Boolean = synchronized(lock) { password?.isNotEmpty() == true }
    /** A copy for one `open`/`saveSmb` call (those zero what they are given). */
    fun passwordCopy(): CharArray? = synchronized(lock) { password?.copyOf() }
    fun zero() = synchronized(lock) { password?.fill('\u0000'); password = null }

    /** What this walk would save: the host/share/path and the sign-in (never the password). */
    fun address(share: String? = this.share, path: String = this.path, guest: Boolean = this.guest): SmbAddress =
        SmbAddress(host.orEmpty(), port, share.orEmpty(), path, if (guest) null else user, if (guest) null else domain, guest, lastAddress)

    override fun toString(): String = "BrowseSession(token=$token)"
}

/** A Test's state on its page (§7.8): running, stopped, or finished. */
data class TestUi(
    val running: Boolean = false, val progress: TestProgress = TestProgress(TestPhase.CONNECTING),
    val stopped: Boolean = false, val result: LocationTestResult? = null,
)

/**
 * The pages of "Pause and rewind", "Save to", a stored location and the network-folder walk (§9.2). One source for all of
 * them: it owns the [BrowseSession]s, answers the phone page's context ([PhoneContextProvider]) and takes the phone's
 * sign-in ([PhoneSubmission.NetworkFolder]). Builders run on `Dispatchers.Default`; navigation after a suspend goes
 * through the shell link ([attachNavigation]) on the main thread ([StoragePageEnv.runOnMain]).
 *
 * Every option of a menu is also a row (M5 §3.15 (d)): on a location Item, Test · Change sign-in · Remove are the
 * LocationDetail rows; on a found computer, "Sign in first" is the Sign in row of the page it opens.
 */
class StoragePages(
    private val store: SettingsStore,
    private val texts: SettingsTexts,
    private val st: StorageTexts,
    private val wpa: WpaPages,
    private val wpaEnv: WpaPageEnv,
    private val places: TimeshiftPlaceSource,
    private val locations: StorageLocations,
    private val folders: NetworkFolders,
    private val volumes: VolumeCatalog,
    private val phone: PhoneLink,
    private val env: StoragePageEnv,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val parse: (String) -> ManualAddressParser.Result = { ManualAddressParser.parse(it) },
    /** A session whose last route closed is ended after this grace (a replace closes one route before the next builds). */
    private val reapGraceMs: Long = REAP_GRACE_MS,
) : SettingsPageSource, PhoneContextProvider, SettingsNavigationAware {
    private val version = AtomicLong(0)
    private val tokens = AtomicLong(1)
    private val sessions = ConcurrentHashMap<Long, BrowseSession>()
    private val storedSessions = ConcurrentHashMap<String, BrowseSession>()
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    @Volatile private var link: (() -> SettingsShellLink?)? = null
    @Volatile private var find: FindState? = null
    @Volatile private var findJob: Job? = null
    @Volatile private var findFocused: Boolean = false

    override val notices: Flow<String> get() = _notices

    override val changes: Flow<Unit>
        get() = merge(
            store.flow(PlaybackDomain).drop(1).map { },
            locations.status.drop(1).map { },
            volumes.volumes.drop(1).map { },
            _changes,
        )

    override fun attachNavigation(link: () -> SettingsShellLink?) { this.link = link }

    override fun handles(route: SettingsRoute): Boolean = when (route) {
        SettingsRoute.PauseRewind, is SettingsRoute.SaveLocation, is SettingsRoute.LocationDetail, SettingsRoute.FindComputers,
        is SettingsRoute.ComputerShares, is SettingsRoute.ComputerSignIn, is SettingsRoute.LocationSignIn,
        is SettingsRoute.NetworkFolder, is SettingsRoute.LocationTest -> true
        is SettingsRoute.PhoneSetup -> route.purpose == PhonePurpose.NETWORK_FOLDER
        else -> false
    }

    // ================================================================================== pages

    override suspend fun page(route: SettingsRoute, ctx: PageContext): PageModel = build(route, ctx)

    /** Builds [route]'s page synchronously (tests and [page]). Starts the work a page shows (finders, listings, Test). */
    fun build(route: SettingsRoute, ctx: PageContext): PageModel {
        val v = version.incrementAndGet()
        return when (route) {
            SettingsRoute.PauseRewind -> pauseRewind(route, ctx, v)
            is SettingsRoute.SaveLocation -> saveLocation(route, ctx, v)
            is SettingsRoute.LocationDetail -> locationDetail(route, ctx, v)
            SettingsRoute.FindComputers -> findComputers(route, v)
            is SettingsRoute.ComputerShares -> withSession(route, route.browse) { computerShares(route, it, v) }
            is SettingsRoute.ComputerSignIn -> withSession(route, route.browse) { signInPage(route, it, v, st[StorageText.SIGN_IN_TITLE, display(it)]) }
            is SettingsRoute.LocationSignIn -> {
                val s = storedSession(route.locationId)
                if (s == null) gone(route, v) else { attach(s, route); signInPage(route, s, v, st[StorageText.SIGN_IN_TITLE, s.computerName ?: s.host.orEmpty()]) }
            }
            is SettingsRoute.NetworkFolder -> withSession(route, route.browse) { networkFolder(route, it, v) }
            is SettingsRoute.LocationTest -> locationTest(route, v)
            is SettingsRoute.PhoneSetup -> {
                val s = route.browse?.let { sessions[it] }
                if (s != null) { attach(s, route); ensurePhone(s, route) }
                PageModel(route = route, title = st[StorageText.SIGN_IN_PHONE_TITLE], rows = emptyList(), version = v)
            }
            else -> PageModel(route = route, title = "", rows = emptyList(), version = v)
        }
    }

    /** The row helper: every row here is SIMPLE; [effect] is NEXT_TUNE for rows that change where rewind saves. */
    private fun row(
        id: RowId, label: String, kind: RowKind, value: String? = null, description: String? = null, enabled: Boolean = true,
        disabledReason: String? = null, visibility: Visibility = Visibility.SIMPLE, effect: EffectTiming = EffectTiming.LIVE,
    ) = SettingsRow(id, label, value, description, kind, enabled, disabledReason, visibility, effect)

    private fun info(id: RowId, label: String, lines: List<String> = emptyList(), tone: Tone = Tone.NORMAL, value: String? = null) =
        row(id, label, RowKind.Info(lines, tone), value = value)

    /** A §7.7 error as a row: the reason as the label, the next step under it, in the tone. */
    private fun errorRow(id: RowId, e: BrowseError, host: String, address: String?, apple: Boolean, tone: Tone = Tone.BAD, guest: Boolean = false): SettingsRow {
        val (title, step) = errorTexts(e, host, address, apple, guest)
        return info(id, title, step.split('\n'), tone)
    }

    private fun gone(route: SettingsRoute, v: Long) =
        PageModel(route = route, title = "", rows = emptyList(), version = v, placeholder = texts[SettingsText.PAGE_NOT_AVAILABLE])

    private inline fun withSession(route: SettingsRoute, token: Long, build: (BrowseSession) -> PageModel): PageModel {
        val s = sessions[token] ?: return gone(route, version.incrementAndGet())
        attach(s, route)
        return build(s)
    }

    private fun attach(s: BrowseSession, route: SettingsRoute) { s.attached = true; s.routes += route }

    // ---------------------------------------------------------------- Pause and rewind (§9.1)

    private fun pauseRewind(route: SettingsRoute, ctx: PageContext, v: Long): PageModel {
        val p = store.flow(PlaybackDomain).value
        val place = places.current()
        val rows = wpa.timeshiftRows(p, place).toMutableList()
        val off = p.timeshift == TimeshiftLength.OFF
        val desc = when {
            place.offlineName != null && place.problem == LocationState.SIGN_IN_NEEDED -> StorageMarks.warn(st[StorageText.SAVE_TO_DESC_SIGN_IN, place.sentence])
            place.offlineName != null -> StorageMarks.warn(st[StorageText.SAVE_TO_DESC_UNREACHABLE, place.sentence])
            else -> st[StorageText.SAVE_TO_DESC]
        }
        val location = row(
            PauseRewindRows.TIMESHIFT_LOCATION, st[StorageText.SAVE_TO], RowKind.Link(SettingsRoute.SaveLocation()),
            value = place.label.ifBlank { st[StorageText.THIS_DEVICE] }, description = desc, enabled = !off,
            disabledReason = st[StorageText.SAVE_TO_OFF_REASON], effect = EffectTiming.NEXT_TUNE,
        )
        // Rewind length, Save to, then the two advanced rows.
        rows.add(1, location)
        val shown = rows.filter { ctx.advanced || it.visibility == Visibility.SIMPLE }
        return PageModel(route = route, title = texts[SettingsText.PB_TIMESHIFT], subtitle = st[StorageText.PAUSE_REWIND_SUBTITLE], rows = shown, version = v)
    }

    // ---------------------------------------------------------------- Save to (§9.2)

    private fun saveLocation(route: SettingsRoute.SaveLocation, ctx: PageContext, v: Long): PageModel {
        val chosen = locations.chosen(route.purpose)
        val status = locations.status.value
        val mounted = volumes.volumes.value
        val rows = ArrayList<SettingsRow>()
        val device = wpaEnv.timeshiftStorage()
        rows += row(
            ROW_DEVICE, st[StorageText.THIS_DEVICE],
            RowKind.Item(primary = "choose", badges = if (chosen == StorageConstants.INTERNAL_ID) setOf(Badge.IN_USE) else emptySet()),
            description = device?.let { st[StorageText.THIS_DEVICE_DESC, sizeText(it.availableBytes)] } ?: st[StorageText.THIS_DEVICE_DESC_UNKNOWN],
            effect = EffectTiming.NEXT_TUNE,
        )
        for (vol in mounted) {
            val id = StorageConstants.VOLUME_ID_PREFIX + vol.uuid
            rows += row(
                RowId("storage.loc.$id"), st[StorageText.USB_DRIVE_NAMED, vol.label.ifBlank { st[StorageText.USB_DRIVE] }],
                RowKind.Item(primary = "choose:$id", badges = if (chosen == id) setOf(Badge.IN_USE) else emptySet()),
                description = st[StorageText.USB_DRIVE_DESC, sizeText(vol.freeBytes)], effect = EffectTiming.NEXT_TUNE,
            )
        }
        for (s in status) {
            val loc = s.location
            if (loc.kind == StorageKind.VOLUME && mounted.any { StorageConstants.VOLUME_ID_PREFIX + it.uuid == loc.id }) continue
            val (line, tone) = statusLine(s, chosen == loc.id, ctx.nowMs)
            val d = if (tone == Tone.WARN) StorageMarks.warn(line) else line
            rows += row(
                RowId("storage.loc.${loc.id}"), loc.name.ifBlank { st[StorageText.USB_DRIVE] },
                RowKind.Item(route = SettingsRoute.LocationDetail(loc.id), menu = locationMenu(loc.kind), badges = if (chosen == loc.id) setOf(Badge.IN_USE) else emptySet()),
                description = d,
            )
        }
        rows += row(ROW_ADD, st[StorageText.ADD_NETWORK_FOLDER], RowKind.Link(SettingsRoute.FindComputers), description = st[StorageText.ADD_NETWORK_FOLDER_DESC])
        return PageModel(route = route, title = st[StorageText.SAVE_TO], subtitle = st[StorageText.SAVE_SUBTITLE], rows = rows, version = v)
    }

    private fun locationMenu(kind: StorageKind): List<ChoiceOption> =
        if (kind == StorageKind.SMB) listOf(
            ChoiceOption(MENU_TEST, st[StorageText.MENU_TEST]),
            ChoiceOption(MENU_SIGN_IN, st[StorageText.MENU_SIGN_IN]),
            ChoiceOption(MENU_REMOVE, st[StorageText.MENU_REMOVE]),
        ) else listOf(ChoiceOption(MENU_REMOVE, st[StorageText.MENU_REMOVE]))

    /** The status text and tone of a stored location (§9.2 Status, U-N7). */
    private fun statusLine(s: LocationStatus, inUse: Boolean, nowMs: Long): Pair<String, Tone> {
        val name = s.location.name.ifBlank { st[StorageText.USB_DRIVE] }
        return when (s.state) {
            LocationState.UNREACHABLE -> st[StorageText.STATUS_CANT_REACH, name] to Tone.WARN
            LocationState.SIGN_IN_NEEDED -> st[StorageText.STATUS_SIGN_IN_NEEDED, name] to Tone.WARN
            LocationState.NOT_CONNECTED -> st[StorageText.STATUS_NOT_CONNECTED] to Tone.WARN
            LocationState.DEMOTED -> st[StorageText.STATUS_DEMOTED, name] to Tone.WARN
            LocationState.CONNECTING, LocationState.READY, LocationState.UNTESTED -> {
                val t = s.location.lastTest
                when {
                    inUse -> st[StorageText.STATUS_IN_USE] to Tone.GOOD
                    t != null && t.verdict != TestVerdict.FAILED -> st[StorageText.STATUS_TESTED_AGO, verdictLabel(t.verdict), ago(t.atWallMs, nowMs)] to
                        (if (t.verdict == TestVerdict.SLOW || t.verdict == TestVerdict.TOO_SLOW) Tone.WARN else Tone.NORMAL)
                    else -> st[StorageText.STATUS_NOT_TESTED] to Tone.NORMAL
                }
            }
        }
    }

    private fun ago(thenMs: Long, nowMs: Long): String {
        val days = ((nowMs - thenMs).coerceAtLeast(0L) / DAY_MS).toInt()
        return when (days) {
            0 -> st[StorageText.AGO_TODAY]
            1 -> st[StorageText.AGO_YESTERDAY]
            else -> st[StorageText.AGO_DAYS, days]
        }
    }

    // ---------------------------------------------------------------- one location (§9.2, U-B2)

    private fun locationDetail(route: SettingsRoute.LocationDetail, ctx: PageContext, v: Long): PageModel {
        val s = locations.status.value.firstOrNull { it.location.id == route.locationId }
        val loc = s?.location ?: return gone(route, v)
        val chosen = locations.chosen(StoragePurpose.TIMESHIFT) == loc.id
        val smb = loc.smb
        val name = loc.name.ifBlank { st[StorageText.USB_DRIVE] }
        val (statusText, tone) = statusLine(s, chosen, ctx.nowMs)
        val rows = ArrayList<SettingsRow>()
        rows += info(ROW_D_STATUS, st[StorageText.DETAIL_STATUS], listOf(statusText), tone)
        if (smb != null) rows += info(ROW_D_ADDRESS, st[StorageText.DETAIL_ADDRESS], value = unc(smb.host, smb.share, smb.path))
        if (loc.kind == StorageKind.SMB) {
            if (!chosen) rows += row(ROW_D_USE, st[StorageText.DETAIL_USE], RowKind.Action(), description = st[StorageText.DETAIL_USE_DESC], effect = EffectTiming.NEXT_TUNE)
            rows += row(ROW_D_TEST, st[StorageText.DETAIL_TEST], RowKind.Link(SettingsRoute.LocationTest(locationId = loc.id)))
            rows += row(ROW_D_NAME, st[StorageText.DETAIL_NAME], RowKind.Text(loc.name, TextHint.NAME), value = loc.name.ifEmpty { null })
            val needsSignIn = s.state == LocationState.SIGN_IN_NEEDED
            rows += row(
                ROW_D_SIGNIN, st[if (needsSignIn) StorageText.DETAIL_SIGN_IN_AGAIN else StorageText.DETAIL_CHANGE_SIGN_IN],
                RowKind.Link(SettingsRoute.LocationSignIn(loc.id)),
            )
        } else {
            val connected = s.state != LocationState.NOT_CONNECTED
            if (!chosen) rows += row(
                ROW_D_USE, st[StorageText.DETAIL_USE], RowKind.Action(), description = st[StorageText.DETAIL_USE_DESC],
                enabled = connected, disabledReason = st[StorageText.DETAIL_CONNECT_DRIVE], effect = EffectTiming.NEXT_TUNE,
            )
        }
        val shortName = if (smb != null) breadcrumb(name, smb.share, smb.path) else name
        rows += row(
            ROW_D_REMOVE, st[StorageText.DETAIL_REMOVE],
            RowKind.Action(destructive = true, confirm = ConfirmSpec(st[StorageText.DETAIL_REMOVE_TITLE, shortName], st[StorageText.DETAIL_REMOVE_BODY], st[StorageText.DETAIL_REMOVE_CONFIRM], st[StorageText.CANCEL])),
        )
        return PageModel(route = route, title = name, subtitle = smb?.let { unc(it.host, it.share, it.path) }, rows = rows, version = v)
    }

    // ---------------------------------------------------------------- Find computers (§7.1, §7.2)

    private fun findComputers(route: SettingsRoute, v: Long): PageModel {
        ensureFind()
        val f = find
        val rows = ArrayList<SettingsRow>()
        val saved = locations.status.value.mapNotNull { it.location.smb?.host?.lowercase(Locale.ROOT) }.toSet()
        val computers = (f?.computers.orEmpty()).sortedWith(
            compareByDescending<FoundComputer> { it.saved || it.address.lowercase(Locale.ROOT) in saved || it.name?.lowercase(Locale.ROOT) in saved }
                .thenBy { it.name == null }.thenBy { it.name?.lowercase(Locale.ROOT).orEmpty() }.thenBy { addressKey(it.address) },
        )
        val searching = f == null || f.searching
        val findError = f?.error
        val net = f?.scannedNetwork
        when {
            searching -> rows += info(
                ROW_SEARCHING, if (net != null) st[StorageText.FIND_SEARCHING, net] else st[StorageText.FIND_SEARCHING_NONET],
                value = if (computers.isNotEmpty()) st[StorageText.FIND_COUNT, computers.size] else null,
            )
            computers.isNotEmpty() -> rows += info(ROW_STATUS, if (computers.size == 1) st[StorageText.FIND_DONE_ONE] else st[StorageText.FIND_DONE, computers.size])
            findError?.kind == BrowseErrorKind.NO_NETWORK -> rows += errorRow(ROW_STATUS, findError, "", null, false, Tone.WARN)
            else -> rows += info(ROW_STATUS, st[StorageText.FIND_NONE_TITLE], st[StorageText.FIND_NONE_STEP].split('\n'), Tone.WARN)
        }
        if (f?.vpnUp == true) rows += info(ROW_VPN, st[StorageText.FIND_VPN_TITLE], st[StorageText.FIND_VPN_STEP].split('\n'), Tone.WARN)
        for (c in computers) {
            val isSaved = c.saved || c.address.lowercase(Locale.ROOT) in saved || c.name?.lowercase(Locale.ROOT) in saved
            rows += row(
                RowId("storage.find.pc.${c.address}"), c.name ?: c.address,
                RowKind.Item(primary = "pick:${c.address}", badges = if (isSaved) setOf(Badge.SAVED) else emptySet(), menu = listOf(ChoiceOption(MENU_SIGN_IN, st[StorageText.COMPUTER_MENU_SIGN_IN]))),
                value = if (c.name != null) c.address else st[StorageText.COMPUTER_NO_NAME],
            )
        }
        rows += row(ROW_ENTER, st[StorageText.FIND_ENTER], RowKind.Text("", TextHint.HOST, chips = HOST_CHIPS), description = st[StorageText.FIND_ENTER_DESC])
        rows += row(ROW_PHONE_FIND, st[StorageText.FIND_PHONE], RowKind.Action(), description = st[StorageText.FIND_PHONE_DESC])
        rows += row(ROW_AGAIN, st[StorageText.FIND_AGAIN], RowKind.Action())
        // Focus (U-S9): "Enter a name or address" until the first computer arrives, then that computer.
        val focus = if (computers.isNotEmpty()) RowId("storage.find.pc.${computers.first().address}") else ROW_ENTER
        return PageModel(route = route, title = st[StorageText.FIND_TITLE], subtitle = st[StorageText.FIND_SUBTITLE], rows = rows, version = v, focus = focus)
    }

    private fun addressKey(a: String): String = a.split('.').joinToString(".") { it.padStart(3, '0') }

    /** Starts the finders when the page is first built; they stop with the page ([closed]) or on "Search again". */
    private fun ensureFind() {
        if (findJob?.isActive == true) return
        find = null
        findJob = scope.launch {
            try {
                // The flow lives as long as the page does (NetworkFolders.find KDoc: sockets and the multicast lock are held
                // while it is collected); the searching flag is in its states, not in its completion.
                folders.find().collect { state -> find = state; _changes.tryEmit(Unit) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BoxLog.w(TAG, "find failed: ${e.javaClass.simpleName}")
                find = FindState(find?.computers.orEmpty(), searching = false, scannedNetwork = find?.scannedNetwork, vpnUp = find?.vpnUp == true)
                _changes.tryEmit(Unit)
            }
        }
    }

    // ---------------------------------------------------------------- shares (§7.5)

    private fun computerShares(route: SettingsRoute.ComputerShares, s: BrowseSession, v: Long): PageModel {
        ensureShares(s)
        val rows = ArrayList<SettingsRow>()
        val err = s.error
        val shares = s.shares
        when {
            s.connecting || (s.handle != null && shares == null) -> rows += info(ROW_S_STATUS, st[StorageText.CONNECTING, display(s)])
            err != null && s.handle == null -> {
                rows += errorRow(ROW_S_ERROR, err, display(s), s.lastAddress, s.apple)
                rows += row(ROW_S_RETRY, st[StorageText.TRY_AGAIN], RowKind.Action())
            }
            shares != null -> {
                if (shares.error != null) rows += info(ROW_S_STATUS, st[StorageText.SHARES_LIST_FAILED], tone = Tone.WARN)
                else if (shares.shares.isEmpty()) rows += info(ROW_S_STATUS, st[StorageText.SHARES_NONE, display(s)], tone = Tone.WARN)
                for (name in shares.shares) rows += row(RowId("storage.share.$name"), name, RowKind.Item(route = SettingsRoute.NetworkFolder(s.token, name)))
            }
        }
        if (s.handle != null) rows += row(ROW_S_TYPE, st[StorageText.SHARE_TYPE_NAME], RowKind.Text("", TextHint.NAME), description = st[StorageText.SHARE_TYPE_NAME_DESC])
        val signedIn = s.handle?.mode == SignInMode.USER
        rows += row(ROW_S_SIGNIN, st[if (signedIn) StorageText.SIGN_IN_AS_SOMEONE else StorageText.SIGN_IN], RowKind.Link(SettingsRoute.ComputerSignIn(s.token)))
        return PageModel(route = route, title = display(s), subtitle = st[StorageText.SHARES_SUBTITLE], rows = rows, version = v)
    }

    private fun ensureShares(s: BrowseSession) {
        val h = s.handle ?: return
        if (s.shares != null || s.loadingShares) return
        s.loadingShares = true
        s.sharesJob = scope.launch {
            val r = try { folders.shares(h) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                ShareListing(emptyList(), BrowseError(BrowseErrorKind.LIST_FAILED))
            }
            s.shares = r
            s.loadingShares = false
            _changes.tryEmit(Unit)
        }
    }

    // ---------------------------------------------------------------- sign in (§7.3)

    private fun signInPage(route: SettingsRoute, s: BrowseSession, v: Long, title: String): PageModel {
        val rows = ArrayList<SettingsRow>()
        val keyboard = env.hasKeyboard()
        val err = s.error
        if (err != null && !s.connecting) rows += errorRow(ROW_SI_ERROR, err, display(s), s.lastAddress, s.apple)
        if (!keyboard) rows += info(ROW_SI_NOKEYS, st[StorageText.SIGN_IN_NO_KEYBOARD], tone = Tone.WARN)
        rows += row(ROW_SI_PHONE, st[StorageText.SIGN_IN_PHONE], RowKind.Action(), description = st[StorageText.SIGN_IN_PHONE_DESC])
        rows += row(ROW_SI_USER, st[StorageText.SIGN_IN_USER], RowKind.Text(s.user.orEmpty(), TextHint.USERNAME), value = s.user?.ifEmpty { null } ?: st[StorageText.NOT_SET])
        // The editor starts empty: the password never sits in the page model (the masked value is a mask only).
        rows += row(
            ROW_SI_PASSWORD, st[StorageText.SIGN_IN_PASSWORD], RowKind.Text("", TextHint.PASSWORD, masked = true),
            value = if (s.hasPassword()) MASK else st[StorageText.NOT_SET],
        )
        val canGo = !s.user.isNullOrBlank() && !s.connecting
        rows += row(
            ROW_SI_GO, st[StorageText.SIGN_IN_GO], RowKind.Action(), enabled = canGo,
            disabledReason = if (s.connecting) st[StorageText.CONNECTING, display(s)] else st[StorageText.SIGN_IN_GO_NEEDS_USER],
        )
        rows += row(ROW_SI_GUEST, st[StorageText.SIGN_IN_GUEST], RowKind.Action(), enabled = !s.connecting)
        rows += row(
            ROW_SI_DOMAIN, st[StorageText.SIGN_IN_DOMAIN], RowKind.Text(s.domain.orEmpty(), TextHint.NAME),
            value = s.domain?.ifEmpty { null } ?: st[StorageText.NOT_SET], description = st[StorageText.SIGN_IN_DOMAIN_DESC],
        )
        rows += info(ROW_SI_INFO, st[StorageText.SIGN_IN_INFO_TITLE], st[StorageText.SIGN_IN_INFO].split('\n'))
        val focus = when {
            s.wrongPassword -> ROW_SI_PASSWORD
            keyboard -> ROW_SI_USER
            else -> ROW_SI_PHONE
        }
        return PageModel(route = route, title = title, subtitle = st[StorageText.SIGN_IN_SUBTITLE], rows = rows, version = v, focus = focus)
    }

    // ---------------------------------------------------------------- folders (§7.6)

    private fun networkFolder(route: SettingsRoute.NetworkFolder, s: BrowseSession, v: Long): PageModel {
        if (s.share != route.share) { s.share = route.share; s.path = ""; s.folderList = null; s.folderKey = null }
        ensureFolders(s)
        val rows = ArrayList<SettingsRow>()
        val host = display(s)
        val here = unc(host, route.share, s.path)
        s.note?.let { rows += info(ROW_F_NOTE, it, tone = Tone.WARN) }
        val list = s.folderList
        val err = list?.error?.takeIf { it.kind != BrowseErrorKind.NOT_FOUND } ?: s.error
        rows += row(ROW_F_USE, st[StorageText.FOLDER_USE], RowKind.Action(), value = here, enabled = list != null && err == null)
        if (s.path.isNotEmpty()) rows += row(ROW_F_UP, st[StorageText.FOLDER_UP], RowKind.Item(primary = "up"))
        when {
            list == null && err == null -> rows += info(ROW_F_STATUS, st[StorageText.FOLDER_LOADING])
            err != null -> {
                rows += errorRow(ROW_F_ERROR, err, host, s.lastAddress, s.apple)
                rows += row(ROW_S_RETRY, st[StorageText.TRY_AGAIN], RowKind.Action())
            }
            else -> {
                for (f in list!!.folders) rows += row(RowId("storage.folder.$f"), f, RowKind.Item(primary = "enter:$f"))
                if (list.truncated) rows += info(ROW_F_STATUS, st[StorageText.FOLDER_TRUNCATED, StorageConstants.SMB_LIST_MAX_ENTRIES])
                else if (list.folders.isEmpty()) rows += info(ROW_F_STATUS, st[StorageText.FOLDER_NONE_BELOW])
            }
        }
        val focus = if (s.focusUse && list != null) ROW_F_USE else null
        return PageModel(route = route, title = unc(host, route.share, ""), subtitle = if (s.path.isEmpty()) st[StorageText.FOLDER_SUBTITLE_ROOT] else unc(host, route.share, s.path), rows = rows, version = v, focus = focus)
    }

    /** Lists the current folder when the page shows one it has not listed yet. */
    private fun ensureFolders(s: BrowseSession) {
        val h = s.handle ?: return
        val share = s.share ?: return
        val key = "$share\u0000${s.path}"
        if (s.folderKey == key) return
        s.folderKey = key
        s.folderList = null
        val path = s.path
        s.listJob?.cancel()
        s.listJob = scope.launch {
            val r = try { folders.folders(h, share, path) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                FolderListing(share, path, emptyList(), error = BrowseError(BrowseErrorKind.STORAGE, StorageFailure.UNREACHABLE))
            }
            if (s.folderKey != key) return@launch
            val miss = r.error
            if (miss != null && miss.kind == BrowseErrorKind.NOT_FOUND) {
                // §7.2: land on the deepest folder that exists and say what was missing.
                val deepest = miss.deepestPath.orEmpty().trim('/')
                val missing = path.removePrefix(deepest).trim('/').substringBefore('/')
                s.note = st[StorageText.FOLDER_MISSING, missing.ifEmpty { path.substringAfterLast('/') }, unc(display(s), share, deepest)]
                s.path = deepest
                s.folderKey = null
                s.folderList = null
                _changes.tryEmit(Unit)
                ensureFolders(s)
                return@launch
            }
            s.folderList = r
            _changes.tryEmit(Unit)
        }
    }

    // ---------------------------------------------------------------- the Test (§7.8)

    private fun locationTest(route: SettingsRoute.LocationTest, v: Long): PageModel {
        val s = route.browse?.let { sessions[it] } ?: route.locationId?.let { storedSession(it) }
            ?: return gone(route, v)
        attach(s, route)
        ensureTest(s)
        val t = s.test ?: TestUi(running = true)
        val host = display(s)
        val name = breadcrumb(host, s.share.orEmpty(), s.path)
        val stored = s.locationId != null
        val rows = ArrayList<SettingsRow>()
        var focus: RowId? = null
        val result = t.result
        when {
            t.running -> {
                rows += progressRow(t.progress)
                rows += row(ROW_T_STOP, st[StorageText.TEST_STOP], RowKind.Action())
                focus = ROW_T_STOP
            }
            t.stopped -> {
                rows += info(ROW_T_VERDICT, st[StorageText.TEST_STOPPED], tone = Tone.WARN)
                rows += row(ROW_T_AGAIN, st[StorageText.TEST_AGAIN], RowKind.Action())
                rows += row(ROW_T_CHOOSE, chooseLabel(stored, name), RowKind.Action())
                focus = ROW_T_AGAIN
            }
            result != null && result.verdict == TestVerdict.FAILED -> {
                val e = result.error ?: BrowseError(BrowseErrorKind.STORAGE, StorageFailure.UNREACHABLE)
                rows += errorRow(ROW_T_VERDICT, e, host, s.lastAddress, s.apple, guest = s.guest)
                rows += row(ROW_T_CHOOSE, chooseLabel(stored, name), RowKind.Action())
                rows += row(ROW_T_AGAIN, st[StorageText.TEST_AGAIN], RowKind.Action())
                focus = ROW_T_CHOOSE
            }
            result != null -> {
                val good = result.verdict == TestVerdict.GOOD_4K || result.verdict == TestVerdict.GOOD_HD
                rows += verdictRow(result, host)
                result.takeIf { it.wakeMs >= StorageConstants.TEST_WAKE_NOTE_MS }?.let {
                    rows += info(ROW_T_WAKE, st[StorageText.TEST_WAKE_NOTE, secondsText(it.wakeMs)])
                }
                if (env.smallRemotePool()) rows += info(ROW_T_POOL, st[StorageText.TEST_SMALL_POOL_NOTE])
                if (good) {
                    rows += row(ROW_T_USE, st[if (stored) StorageText.TEST_USE_FOR_REWIND else StorageText.TEST_USE_THIS], RowKind.Action(), effect = EffectTiming.NEXT_TUNE)
                    rows += row(ROW_T_CHOOSE, chooseLabel(stored, name), RowKind.Action())
                    focus = ROW_T_USE
                } else {
                    rows += row(ROW_T_CHOOSE, chooseLabel(stored, name), RowKind.Action())
                    rows += row(ROW_T_ANYWAY, st[StorageText.TEST_USE_ANYWAY], RowKind.Action(), effect = EffectTiming.NEXT_TUNE)
                    focus = ROW_T_CHOOSE
                }
                rows += row(ROW_T_AGAIN, st[StorageText.TEST_AGAIN], RowKind.Action())
            }
        }
        return PageModel(route = route, title = st[StorageText.TEST_TITLE, name], rows = rows, version = v, focus = focus)
    }

    private fun chooseLabel(stored: Boolean, name: String): String =
        if (stored) st[StorageText.TEST_BACK_TO, name.substringBefore(" › ")] else st[StorageText.TEST_CHOOSE_ANOTHER]

    /** The progress row (§9.4): label from the phase, "9 of 16 MB" as the value. */
    private fun progressRow(p: TestProgress): SettingsRow {
        val phase = when (p.phase) {
            TestPhase.CONNECTING -> StorageText.TEST_PHASE_CONNECTING
            TestPhase.WAKING -> StorageText.TEST_PHASE_WAKING
            TestPhase.WRITING -> StorageText.TEST_PHASE_WRITING
            TestPhase.READING -> StorageText.TEST_PHASE_READING
            TestPhase.RTT, TestPhase.DONE -> StorageText.TEST_PHASE_RTT
        }
        val value = if (p.totalBytes > 0 && (p.phase == TestPhase.WRITING || p.phase == TestPhase.READING)) st[StorageText.TEST_PROGRESS_MB, mb(p.doneBytes), mb(p.totalBytes)] else null
        return info(ROW_T_PROGRESS, st[phase], value = value)
    }

    private fun mb(bytes: Long): String = (bytes / MB).toString()

    private fun verdictLabel(v: TestVerdict): String = st[when (v) {
        TestVerdict.GOOD_4K -> StorageText.STATUS_GOOD_4K
        TestVerdict.GOOD_HD -> StorageText.STATUS_GOOD_HD
        TestVerdict.SLOW -> StorageText.STATUS_SLOW
        TestVerdict.TOO_SLOW -> StorageText.STATUS_TOO_SLOW
        TestVerdict.FAILED -> StorageText.STATUS_NOT_TESTED
    }]

    /** One Info row: the label, the next step under it (in the tone), the details in the value column (mono). */
    private fun verdictRow(r: LocationTestResult, host: String): SettingsRow {
        val step = when (r.verdict) {
            TestVerdict.GOOD_4K -> st[StorageText.VERDICT_STEP_GOOD_4K, sizeText(r.freeBytes), host]
            TestVerdict.GOOD_HD -> st[StorageText.VERDICT_STEP_GOOD_HD, host]
            TestVerdict.SLOW -> st[StorageText.VERDICT_STEP_SLOW]
            TestVerdict.TOO_SLOW -> st[StorageText.VERDICT_STEP_TOO_SLOW, host]
            TestVerdict.FAILED -> ""
        }
        val rate = minOf(r.writeBps, r.readBps)
        val details = st[StorageText.VERDICT_DETAILS, String.format(Locale.ROOT, "%.1f", rate / 1_000_000.0), r.rttMs, st[if (r.wifi) StorageText.WIFI else StorageText.WIRED]]
        val tone = when (r.verdict) {
            TestVerdict.GOOD_4K, TestVerdict.GOOD_HD -> Tone.GOOD
            else -> Tone.WARN
        }
        return info(ROW_T_VERDICT, verdictLabel(r.verdict), listOf(step), tone, value = details)
    }

    private fun secondsText(ms: Int): String = ((ms + 500) / 1000).toString()

    /** Starts the Test once per page (a fresh one after Test again, [BrowseSession.test] cleared). */
    private fun ensureTest(s: BrowseSession) {
        if (s.test != null) return
        val share: String
        val path: String
        if (s.locationId != null) {
            val smb = locations.status.value.firstOrNull { it.location.id == s.locationId }?.location?.smb ?: return
            fillFromStored(s, smb)
            share = smb.share; path = smb.path
        } else {
            share = s.share ?: return
            path = s.path
        }
        s.test = TestUi(running = true)
        s.testJob = scope.launch {
            val result = try {
                val h = s.handle ?: openStored(s)
                if (h == null) {
                    s.test = TestUi(result = failed(s.error ?: BrowseError(BrowseErrorKind.STORAGE, StorageFailure.UNREACHABLE)))
                    _changes.tryEmit(Unit)
                    return@launch
                }
                folders.test(h, share, path) { p -> if (s.test?.running == true) { s.test = TestUi(running = true, progress = p); _changes.tryEmit(Unit) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BoxLog.w(TAG, "test failed: ${e.javaClass.simpleName}")
                failed(BrowseError(BrowseErrorKind.STORAGE, StorageFailure.UNREACHABLE))
            }
            s.test = TestUi(result = result)
            _changes.tryEmit(Unit)
        }
    }

    private fun failed(e: BrowseError) = LocationTestResult(TestVerdict.FAILED, 0, 0, 0, 0, 0L, true, 0L, error = e)

    private fun fillFromStored(s: BrowseSession, a: SmbAddress) {
        if (s.host == null) {
            s.host = a.host; s.port = a.port; s.share = a.share; s.path = a.path; s.user = a.user; s.domain = a.domain; s.guest = a.guest
            s.lastAddress = a.lastAddress; s.computerName = locations.status.value.firstOrNull { it.location.id == s.locationId }?.location?.name
        }
    }

    /** A stored location's session: its saved sign-in is resolved by `NetworkFolders.open` (the password never comes here). */
    private suspend fun openStored(s: BrowseSession): BrowseHandle? {
        val a = s.address(share = "", path = "")
        return when (val r = folders.open(a, s.passwordCopy())) {
            is BrowseResult.Opened -> { s.handle = r.handle; r.handle }
            is BrowseResult.NeedsSignIn -> { s.error = r.error ?: BrowseError(BrowseErrorKind.AUTH); null }
            is BrowseResult.Failed -> { s.error = r.error; null }
        }
    }

    // ================================================================================== intents

    override suspend fun onIntent(route: SettingsRoute, intent: RowIntent, ctx: PageContext): Boolean {
        if (!handles(route)) return false
        val id = intent.row
        return try {
            when (route) {
                SettingsRoute.PauseRewind -> false // the three length rows are written by WpaPages.apply (owner "A")
                is SettingsRoute.SaveLocation -> saveLocationIntent(route, id, intent)
                is SettingsRoute.LocationDetail -> detailIntent(route, id, intent, ctx)
                SettingsRoute.FindComputers -> findIntent(id, intent)
                is SettingsRoute.ComputerShares -> sessions[route.browse]?.let { sharesIntent(it, id, intent) } ?: false
                is SettingsRoute.ComputerSignIn -> sessions[route.browse]?.let { signInIntent(route, it, id, intent) } ?: false
                is SettingsRoute.LocationSignIn -> storedSession(route.locationId)?.let { signInIntent(route, it, id, intent) } ?: false
                is SettingsRoute.NetworkFolder -> sessions[route.browse]?.let { folderIntent(route, it, id, intent) } ?: false
                is SettingsRoute.LocationTest -> (route.browse?.let { sessions[it] } ?: route.locationId?.let { storedSession(it) })?.let { testIntent(route, it, id, intent) } ?: false
                else -> false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BoxLog.w(TAG, "intent failed: ${id.key} ${e.javaClass.simpleName}")
            true
        }
    }

    private suspend fun saveLocationIntent(route: SettingsRoute.SaveLocation, id: RowId, intent: RowIntent): Boolean {
        val prefix = "storage.loc."
        if (!id.key.startsWith(prefix) || id == ROW_ADD) return false
        val locationId = id.key.removePrefix(prefix)
        when {
            intent is RowIntent.Run -> choose(route.purpose, locationId)
            intent is RowIntent.Menu && intent.optionId == MENU_REMOVE_CONFIRMED -> remove(locationId)
            intent is RowIntent.Menu -> locationMenuIntent(locationId, intent.optionId)
            else -> return false
        }
        return true
    }

    private suspend fun choose(purpose: StoragePurpose, locationId: String) {
        locations.choose(purpose, locationId)
        val name = locations.status.value.firstOrNull { it.location.id == locationId }?.location?.name
            ?: if (locationId == StorageConstants.INTERNAL_ID) st[StorageText.PLACE_THIS_DEVICE] else st[StorageText.PLACE_USB_DRIVE]
        _notices.tryEmit(st[StorageText.NOTICE_WILL_SAVE, name])
        _changes.tryEmit(Unit)
    }

    private fun locationMenuIntent(locationId: String, option: String) {
        val nav = nav() ?: return
        when (option) {
            MENU_TEST -> env.runOnMain { nav.push(SettingsRoute.LocationTest(locationId = locationId)) }
            MENU_SIGN_IN -> env.runOnMain { nav.push(SettingsRoute.LocationSignIn(locationId)) }
            MENU_REMOVE -> {
                val loc = locations.status.value.firstOrNull { it.location.id == locationId }?.location ?: return
                val smb = loc.smb
                val shortName = if (smb != null) breadcrumb(loc.name, smb.share, smb.path) else loc.name
                val spec = ConfirmSpec(st[StorageText.DETAIL_REMOVE_TITLE, shortName], st[StorageText.DETAIL_REMOVE_BODY], st[StorageText.DETAIL_REMOVE_CONFIRM], st[StorageText.CANCEL])
                env.runOnMain { nav.confirm(spec, RowIntent.Menu(RowId("storage.loc.$locationId"), MENU_REMOVE_CONFIRMED)) }
            }
        }
    }

    private suspend fun detailIntent(route: SettingsRoute.LocationDetail, id: RowId, intent: RowIntent, ctx: PageContext): Boolean {
        val loc = locations.status.value.firstOrNull { it.location.id == route.locationId } ?: return false
        val nav = nav()
        when {
            id == ROW_D_USE && intent is RowIntent.Run -> {
                val t = loc.location.lastTest
                val fresh = t != null && (ctx.nowMs - t.atWallMs) < StorageConstants.TEST_FRESH_MS &&
                    (t.verdict == TestVerdict.GOOD_4K || t.verdict == TestVerdict.GOOD_HD)
                if (loc.location.kind != StorageKind.SMB || fresh) choose(StoragePurpose.TIMESHIFT, loc.location.id)
                else nav?.let { n -> env.runOnMain { n.push(SettingsRoute.LocationTest(locationId = loc.location.id)) } }
            }
            id == ROW_D_NAME && intent is RowIntent.Edit -> locations.rename(loc.location.id, intent.text.trim())
            id == ROW_D_REMOVE && intent is RowIntent.Run -> remove(loc.location.id)
            else -> return false
        }
        _changes.tryEmit(Unit)
        return true
    }

    private suspend fun remove(locationId: String) {
        val wasChosen = locations.chosen(StoragePurpose.TIMESHIFT) == locationId
        locations.remove(locationId)
        storedSessions.remove(locationId)?.let { endSession(it) }
        nav()?.let { n -> env.runOnMain { n.popTo(SettingsRoute.SaveLocation()) } }
        if (wasChosen) _notices.tryEmit(st[StorageText.NOTICE_WILL_SAVE, st[StorageText.PLACE_THIS_DEVICE]])
    }

    private suspend fun findIntent(id: RowId, intent: RowIntent): Boolean {
        when {
            id == ROW_ENTER && intent is RowIntent.Edit -> enterAddress(intent.text)
            id == ROW_PHONE_FIND && intent is RowIntent.Run -> {
                val s = newSession()
                s.fromPhone = true
                nav()?.let { n -> env.runOnMain { n.push(SettingsRoute.PhoneSetup(purpose = PhonePurpose.NETWORK_FOLDER, browse = s.token)) } }
            }
            id == ROW_AGAIN && intent is RowIntent.Run -> { findJob?.cancel(); find = null; ensureFind(); _changes.tryEmit(Unit) }
            id.key.startsWith("storage.find.pc.") && (intent is RowIntent.Run || intent is RowIntent.Menu) -> {
                val address = id.key.removePrefix("storage.find.pc.")
                val c = find?.computers?.firstOrNull { it.address == address } ?: return true
                val s = newSession()
                s.host = c.address; s.port = c.port; s.computerName = c.name; s.lastAddress = c.address; s.apple = c.apple
                if (intent is RowIntent.Menu) nav()?.let { n -> env.runOnMain { n.push(SettingsRoute.ComputerSignIn(s.token)) } }
                else { nav()?.let { n -> env.runOnMain { n.push(SettingsRoute.ComputerShares(s.token)) } }; startConnect(s) }
            }
            else -> return false
        }
        return true
    }

    /** "Enter a name or address" (§7.2): host only → shares; host + share → that folder, "Use this folder" focused. */
    private fun enterAddress(text: String) {
        val p = parse(text.trim()) as? ManualAddressParser.Result.Parsed ?: return
        val s = newSession()
        s.host = p.host; s.port = p.port; s.share = p.share; s.path = p.path
        s.focusUse = p.share != null && p.path.isNotEmpty()
        nav()?.let { n -> env.runOnMain { n.push(SettingsRoute.ComputerShares(s.token)) } }
        startConnect(s)
    }

    private fun sharesIntent(s: BrowseSession, id: RowId, intent: RowIntent): Boolean {
        when {
            id == ROW_S_RETRY && intent is RowIntent.Run -> startConnect(s)
            id == ROW_S_TYPE && intent is RowIntent.Edit -> typedShare(s, intent.text.trim())
            else -> return false
        }
        return true
    }

    private fun typedShare(s: BrowseSession, name: String) {
        if (name.isEmpty()) return
        val h = s.handle ?: return
        scope.launch {
            val r = try { folders.folders(h, name, "") } catch (e: CancellationException) { throw e } catch (e: Exception) {
                FolderListing(name, "", emptyList(), error = BrowseError(BrowseErrorKind.STORAGE, StorageFailure.UNREACHABLE))
            }
            val err = r.error
            if (err != null) {
                s.error = if (err.kind == BrowseErrorKind.NOT_FOUND) BrowseError(BrowseErrorKind.NOT_FOUND) else err
                s.note = st[StorageText.SHARE_NOT_FOUND, name, display(s)]
                _changes.tryEmit(Unit)
            } else {
                s.error = null; s.note = null
                s.share = name; s.path = ""; s.folderList = r; s.folderKey = "$name\u0000"
                nav()?.let { n -> env.runOnMain { n.push(SettingsRoute.NetworkFolder(s.token, name)) } }
            }
        }
    }

    private fun signInIntent(route: SettingsRoute, s: BrowseSession, id: RowId, intent: RowIntent): Boolean {
        when {
            id == ROW_SI_USER && intent is RowIntent.Edit -> s.user = intent.text.trim()
            id == ROW_SI_PASSWORD && intent is RowIntent.Edit -> { s.setPassword(intent.text.toCharArray()); s.wrongPassword = false }
            id == ROW_SI_DOMAIN && intent is RowIntent.Edit -> s.domain = intent.text.trim().ifEmpty { null }
            id == ROW_SI_GO && intent is RowIntent.Run -> { s.guest = false; startConnect(s) }
            id == ROW_SI_GUEST && intent is RowIntent.Run -> { s.guest = true; s.setPassword(null); startConnect(s) }
            id == ROW_SI_PHONE && intent is RowIntent.Run -> nav()?.let { n -> env.runOnMain { n.push(SettingsRoute.PhoneSetup(purpose = PhonePurpose.NETWORK_FOLDER, browse = s.token)) } }
            else -> return false
        }
        _changes.tryEmit(Unit)
        return true
    }

    private fun folderIntent(route: SettingsRoute.NetworkFolder, s: BrowseSession, id: RowId, intent: RowIntent): Boolean {
        when {
            id == ROW_F_USE && intent is RowIntent.Run -> {
                s.test = null; s.testJob?.cancel()
                nav()?.let { n -> env.runOnMain { n.push(SettingsRoute.LocationTest(browse = s.token)) } }
            }
            id == ROW_F_UP && intent is RowIntent.Run -> up(s)
            id.key.startsWith("storage.folder.") && intent is RowIntent.Run -> enter(s, id.key.removePrefix("storage.folder."))
            id == ROW_S_RETRY && intent is RowIntent.Run -> { s.error = null; s.folderKey = null; s.folderList = null }
            else -> return false
        }
        s.note = if (id == ROW_F_USE) s.note else null
        _changes.tryEmit(Unit)
        return true
    }

    private fun enter(s: BrowseSession, name: String) {
        s.path = if (s.path.isEmpty()) name else s.path + "/" + name
        s.folderKey = null; s.folderList = null; s.focusUse = false
    }

    private fun up(s: BrowseSession) {
        s.path = s.path.substringBeforeLast('/', "")
        s.folderKey = null; s.folderList = null; s.focusUse = false
    }

    private suspend fun testIntent(route: SettingsRoute.LocationTest, s: BrowseSession, id: RowId, intent: RowIntent): Boolean {
        if (intent !is RowIntent.Run) return false
        when (id) {
            ROW_T_STOP -> stopTest(s)
            ROW_T_AGAIN -> { s.testJob?.cancel(); s.test = null; ensureTest(s) }
            ROW_T_USE, ROW_T_ANYWAY -> useResult(s)
            ROW_T_CHOOSE -> {
                s.testJob?.cancel()
                val back: SettingsRoute = if (s.locationId != null) SettingsRoute.LocationDetail(s.locationId) else SettingsRoute.NetworkFolder(s.token, s.share.orEmpty())
                nav()?.let { n -> env.runOnMain { n.popTo(back) } }
            }
            else -> return false
        }
        _changes.tryEmit(Unit)
        return true
    }

    private fun stopTest(s: BrowseSession) {
        s.testJob?.cancel()
        s.test = TestUi(stopped = true)
    }

    /** "Use this folder" (GOOD) or "Use anyway": save, choose, back to Save to (§9.3). */
    private suspend fun useResult(s: BrowseSession) {
        val result = s.test?.result ?: return
        val loc: app.thebox.core.storage.StorageLocation
        if (s.locationId != null) {
            locations.choose(StoragePurpose.TIMESHIFT, s.locationId)
            loc = locations.status.value.firstOrNull { it.location.id == s.locationId }?.location ?: return
        } else {
            val a = s.address()
            val same = locations.status.value.firstOrNull { l ->
                l.location.smb?.let { x -> x.host.equals(a.host, true) && x.port == a.port && x.share.equals(a.share, true) && x.path.trim('/').equals(a.path.trim('/'), true) } == true
            }?.location
            loc = if (same != null) {
                locations.updateSignIn(same.id, a.user, a.domain, s.passwordCopy(), a.guest)
                same
            } else {
                locations.saveSmb(a, defaultName(s), s.passwordCopy(), result)
            }
            locations.choose(StoragePurpose.TIMESHIFT, loc.id)
        }
        val smb = loc.smb
        _notices.tryEmit(st[StorageText.NOTICE_WILL_SAVE, if (smb != null) breadcrumb(loc.name, smb.share, smb.path) else loc.name])
        if (s.locationId == null) endSession(s)
        nav()?.let { n -> env.runOnMain { n.popTo(SettingsRoute.SaveLocation()) } }
    }

    private fun defaultName(s: BrowseSession): String = (s.computerName ?: s.host.orEmpty()).take(StorageConstants.LABEL_MAX_CHARS)

    // ================================================================================== connect, sessions, phone

    private fun newSession(): BrowseSession =
        BrowseSession(tokens.getAndIncrement()).also { sessions[it.token] = it }

    private fun storedSession(locationId: String): BrowseSession? {
        storedSessions[locationId]?.let { return it }
        val loc = locations.status.value.firstOrNull { it.location.id == locationId }?.location ?: return null
        val smb = loc.smb ?: return null
        val s = BrowseSession(tokens.getAndIncrement(), locationId)
        fillFromStored(s, smb)
        s.computerName = loc.name
        storedSessions[locationId] = s
        sessions[s.token] = s
        return s
    }

    /** Signs in with the session's fields (§7.3). Anonymous-then-guest is the folders' job when no user is set. */
    private fun startConnect(s: BrowseSession) {
        s.connectJob?.cancel()
        s.connecting = true; s.error = null; s.wrongPassword = false
        _changes.tryEmit(Unit)
        s.connectJob = scope.launch {
            val r = try {
                folders.open(s.address(share = "", path = ""), s.passwordCopy())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BrowseResult.Failed(BrowseError(BrowseErrorKind.STORAGE, StorageFailure.UNREACHABLE))
            }
            onOpened(s, r)
        }
    }

    private suspend fun onOpened(s: BrowseSession, r: BrowseResult) {
        s.connecting = false
        when (r) {
            is BrowseResult.Opened -> {
                s.handle?.let { old -> if (old.id != r.handle.id) folders.close(old) }
                s.handle = r.handle; s.error = null; s.shares = null; s.loadingShares = false
                if (r.handle.mode == SignInMode.GUEST) { s.guest = true } else if (r.handle.mode == SignInMode.ANONYMOUS) { s.guest = false; s.user = null }
                reportPhone(s, st[StorageText.PHONE_SIGNED_IN, display(s)], done = true)
                afterSignedIn(s)
            }
            is BrowseResult.NeedsSignIn -> {
                s.error = r.error
                s.wrongPassword = r.error?.kind == BrowseErrorKind.AUTH
                if (s.wrongPassword) s.setPassword(null)
                reportPhone(s, phoneProblem(r.error, s), done = false)
                val here = currentRoute()
                if (s.locationId == null && here == SettingsRoute.ComputerShares(s.token)) nav()?.let { n -> env.runOnMain { n.replace(SettingsRoute.ComputerSignIn(s.token)) } }
                _changes.tryEmit(Unit)
            }
            is BrowseResult.Failed -> {
                s.error = r.error
                s.wrongPassword = r.error.kind == BrowseErrorKind.AUTH
                if (s.wrongPassword) s.setPassword(null)
                reportPhone(s, phoneProblem(r.error, s), done = false)
                _changes.tryEmit(Unit)
            }
        }
    }

    /** Where a sign-in that worked leads (§9.3): shares, the typed folder, or back to the stored location. */
    private suspend fun afterSignedIn(s: BrowseSession) {
        val nav = nav()
        val here = currentRoute()
        if (s.locationId != null) {
            locations.updateSignIn(s.locationId, s.user, s.domain, s.passwordCopy(), s.guest)
            s.zero()
            _notices.tryEmit(st[StorageText.NOTICE_SIGNED_IN, s.computerName ?: s.host.orEmpty()])
            nav?.let { n -> env.runOnMain { n.popTo(SettingsRoute.LocationDetail(s.locationId)) } }
            _changes.tryEmit(Unit)
            return
        }
        val share = s.share
        val target: SettingsRoute = if (share != null) SettingsRoute.NetworkFolder(s.token, share) else SettingsRoute.ComputerShares(s.token)
        if (share != null) s.folderKey = null
        if (here != target) nav?.let { n ->
            val stack = (n as? SettingsMachine)?.ui?.value?.stack.orEmpty()
            env.runOnMain {
                when {
                    // A sign-in pushed from the share list: pop back to it. One that replaced it (or the QR page): replace.
                    target in stack -> n.popTo(target)
                    else -> n.replace(target)
                }
            }
        }
        _changes.tryEmit(Unit)
    }

    private fun currentRoute(): SettingsRoute? = (link?.invoke() as? SettingsMachine)?.ui?.value?.route

    private fun nav(): SettingsShellLink? = link?.invoke()

    private fun display(s: BrowseSession): String = s.computerName ?: s.host.orEmpty()

    /** Ends a walk: cancels its jobs, closes the connection, zeroes the password (§7.3). */
    private fun endSession(s: BrowseSession) {
        sessions.remove(s.token)
        s.locationId?.let { storedSessions.remove(it) }
        s.connectJob?.cancel(); s.sharesJob?.cancel(); s.listJob?.cancel(); s.testJob?.cancel(); s.phoneJob?.cancel()
        s.handle?.let { h -> s.handle = null; try { folders.close(h) } catch (e: Exception) { BoxLog.w(TAG, "close failed: ${e.javaClass.simpleName}") } }
        s.zero()
    }

    private fun leaveBrowse(token: Long): Boolean {
        sessions[token]?.let { endSession(it) }
        nav()?.let { n -> env.runOnMain { n.popTo(SettingsRoute.SaveLocation()) } }
        return true
    }

    override fun onBack(route: SettingsRoute): Boolean = when (route) {
        // BACK anywhere in the browse leaves it for Save to: the session is cancelled and the password zeroed (U-B3).
        is SettingsRoute.ComputerShares -> leaveBrowse(route.browse)
        is SettingsRoute.ComputerSignIn -> leaveBrowse(route.browse)
        is SettingsRoute.NetworkFolder -> leaveBrowse(route.browse)
        is SettingsRoute.LocationTest -> {
            val s = route.browse?.let { sessions[it] } ?: route.locationId?.let { storedSessions[it] }
            when {
                s?.test?.running == true -> { stopTest(s); _changes.tryEmit(Unit); true } // BACK = Stop test; nothing is saved
                route.browse != null -> leaveBrowse(route.browse)
                else -> false
            }
        }
        else -> false
    }

    override fun closed(route: SettingsRoute) {
        when (route) {
            SettingsRoute.FindComputers -> { findJob?.cancel(); findJob = null; find = null }
            is SettingsRoute.PhoneSetup -> route.browse?.let { sessions[it] }?.let { it.phoneJob?.cancel(); it.phoneJob = null; it.routes -= route; reapLater(it) }
            is SettingsRoute.LocationTest -> {
                val s = route.browse?.let { sessions[it] } ?: route.locationId?.let { storedSessions[it] }
                if (s != null) { s.testJob?.cancel(); s.test = null; s.routes -= route; reapLater(s) }
            }
            is SettingsRoute.ComputerShares -> sessions[route.browse]?.let { it.routes -= route; reapLater(it) }
            is SettingsRoute.ComputerSignIn -> sessions[route.browse]?.let { it.routes -= route; reapLater(it) }
            is SettingsRoute.NetworkFolder -> sessions[route.browse]?.let { it.routes -= route; reapLater(it) }
            is SettingsRoute.LocationSignIn -> storedSessions[route.locationId]?.let { it.routes -= route; reapLater(it) }
            else -> Unit
        }
        // The parent page was built before this one changed anything (the chosen place, a removal): rebuild it.
        _changes.tryEmit(Unit)
    }

    private fun reapLater(s: BrowseSession) {
        if (!s.attached || s.routes.isNotEmpty()) return
        scope.launch {
            if (reapGraceMs > 0) delay(reapGraceMs)
            if (s.routes.isEmpty()) endSession(s)
        }
    }

    /** Ends every open walk now (tests, and a process that is going away). */
    fun endAll() { sessions.values.toList().forEach { endSession(it) } }

    fun openSessionCount(): Int = sessions.size

    // ---------------------------------------------------------------- the phone's sign-in (§7.4)

    override fun context(browse: Long): PhoneContext? =
        sessions[browse]?.let { s -> PhoneContext(s.computerName ?: s.host, s.share, s.shares?.shares.orEmpty()) }

    /** Collects the phone's submission while its page is open: `PhoneApi` refuses them with 503 when nobody collects. */
    private fun ensurePhone(s: BrowseSession, route: SettingsRoute.PhoneSetup) {
        if (s.phoneJob?.isActive == true) return
        s.phoneJob = scope.launch {
            phone.submissions.filterIsInstance<PhoneSubmission.NetworkFolder>().collect { sub -> onPhone(s, sub) }
        }
    }

    private fun onPhone(s: BrowseSession, sub: PhoneSubmission.NetworkFolder) {
        s.fromPhone = true
        if (s.locationId == null) {
            // The phone page's computer field may have been changed ("Change"): the host it sends wins.
            val parsed = parse(sub.host.trim()) as? ManualAddressParser.Result.Parsed
            if (parsed != null) {
                if (!parsed.host.equals(s.host, true)) { s.computerName = null; s.lastAddress = null }
                s.host = parsed.host; s.port = parsed.port
                if (parsed.share != null) { s.share = parsed.share; s.path = parsed.path }
            }
            val share = sub.share?.trim()?.trim('/', '\\')?.takeIf { it.isNotEmpty() }
            if (share != null) { s.share = share; s.path = sub.path.orEmpty().trim().trim('/', '\\').replace('\\', '/') }
            s.focusUse = s.share != null && s.path.isNotEmpty()
        }
        s.user = sub.user?.trim()?.ifEmpty { null }
        s.domain = sub.domain?.trim()?.ifEmpty { null }
        s.guest = sub.guest
        s.setPassword(sub.password?.copyOf())
        sub.password?.fill('\u0000')
        s.error = null
        // Exactly one reportStatus per submission (WP-S6): done = signed in, false = a failed try (counts toward PAIR_MAX_WRONG).
        startConnect(s)
    }

    private fun reportPhone(s: BrowseSession, text: String, done: Boolean) {
        if (!s.fromPhone) return
        try { phone.reportStatus(text, done) } catch (e: Exception) { BoxLog.w(TAG, "phone status failed: ${e.javaClass.simpleName}") }
    }

    private fun phoneProblem(e: BrowseError?, s: BrowseSession): String = when (e?.kind) {
        BrowseErrorKind.AUTH, null -> st[StorageText.PHONE_REFUSED, display(s)]
        BrowseErrorKind.GUEST_REFUSED -> st[StorageText.PHONE_NO_GUESTS, display(s)]
        BrowseErrorKind.STORAGE -> if (e.failure == StorageFailure.ACCESS) st[StorageText.PHONE_NO_ACCESS, display(s)]
            else st[StorageText.PHONE_UNREACHABLE, display(s), s.lastAddress ?: s.host.orEmpty()]
        else -> st[StorageText.PHONE_UNREACHABLE, display(s), s.lastAddress ?: s.host.orEmpty()]
    }

    /** "sh***": the TV's mirror of what it received never carries the whole user name (§7.4). */
    private fun mask(user: String?): String = when {
        user.isNullOrEmpty() -> ""
        user.length <= 2 -> user.take(1) + "***"
        else -> user.take(2) + "***"
    }

    // ================================================================================== texts

    private fun breadcrumb(name: String, share: String, path: String): String =
        (listOf(name, share) + path.split('/').filter { it.isNotEmpty() }).filter { it.isNotEmpty() }.joinToString(" › ")

    private fun unc(host: String, share: String, path: String): String {
        val sb = StringBuilder("\\\\").append(host)
        if (share.isNotEmpty()) sb.append('\\').append(share)
        for (p in path.split('/')) if (p.isNotEmpty()) sb.append('\\').append(p)
        return sb.toString()
    }

    private fun sizeText(bytes: Long): String {
        val tb = bytes / (1024.0 * 1024 * 1024 * 1024)
        return if (tb >= 1.0) st[StorageText.SIZE_TB, String.format(Locale.ROOT, "%.1f", tb)]
        else st[StorageText.SIZE_GB, String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024 * 1024))]
    }

    /** §7.7: the reason (headline) and its next step; a VPN or Mac hint is appended where the cause says so. */
    internal fun errorTexts(e: BrowseError, host: String, address: String?, apple: Boolean, guest: Boolean = false): Pair<String, String> {
        val shown = address?.takeIf { it.isNotEmpty() && !host.equals(it, true) }
        return when (e.kind) {
            BrowseErrorKind.STORAGE -> when (e.failure) {
                StorageFailure.ACCESS -> st[StorageText.ERR_ACCESS, host] to st[StorageText.ERR_ACCESS_STEP, host]
                StorageFailure.FULL -> st[StorageText.ERR_FULL, host, sizeText(e.freeBytes ?: 0L), sizeText(e.needBytes ?: 0L)] to st[StorageText.ERR_FULL_STEP]
                else -> {
                    val step = st[StorageText.ERR_UNREACHABLE_STEP]
                    val head = if (shown != null) st[StorageText.ERR_UNREACHABLE, host, shown] else st[StorageText.ERR_UNREACHABLE_NOADDR, host]
                    head to (if (e.vpnUp) step + "\n" + st[StorageText.ERR_VPN_APPEND] else step)
                }
            }
            BrowseErrorKind.AUTH -> st[StorageText.ERR_AUTH, host] to (st[StorageText.ERR_AUTH_STEP] + if (apple) "\n" + st[StorageText.ERR_AUTH_MAC_APPEND] else "")
            BrowseErrorKind.GUEST_REFUSED -> st[StorageText.ERR_GUEST, host] to st[StorageText.ERR_GUEST_STEP]
            BrowseErrorKind.SMB1 -> st[StorageText.ERR_SMB1, host] to st[StorageText.ERR_SMB1_STEP]
            BrowseErrorKind.NO_NETWORK -> st[StorageText.ERR_NO_NETWORK] to st[StorageText.ERR_NO_NETWORK_STEP]
            BrowseErrorKind.WRITE_REFUSED ->
                if (guest) st[StorageText.ERR_WRITE_GUEST, host] to st[StorageText.ERR_WRITE_GUEST_STEP, host]
                else st[StorageText.ERR_WRITE_USER] to st[StorageText.ERR_WRITE_USER_STEP, host]
            BrowseErrorKind.DATA_MISMATCH -> st[StorageText.ERR_DATA, host] to st[StorageText.ERR_DATA_STEP]
            BrowseErrorKind.NOT_FOUND, BrowseErrorKind.LIST_FAILED -> st[StorageText.ERR_GENERIC, host] to st[StorageText.ERR_GENERIC_STEP]
        }
    }

    companion object {
        private const val TAG = "StoragePages"
        const val REAP_GRACE_MS = 1_500L
        private const val DAY_MS = 86_400_000L
        private const val MB = 1_000_000L
        /** The masked password's stand-in in the value column (never the password). */
        private const val MASK = "••••••••"
        private const val MENU_TEST = "test_folder"
        private const val MENU_SIGN_IN = "signin"
        private const val MENU_REMOVE = "remove"
        private const val MENU_REMOVE_CONFIRMED = "remove!"
        val HOST_CHIPS = listOf("\\\\", "\\", ".local", "smb://")

        val ROW_DEVICE = RowId("storage.loc.internal")
        val ROW_ADD = RowId("storage.loc.add")
        val ROW_D_STATUS = RowId("storage.detail.status")
        val ROW_D_ADDRESS = RowId("storage.detail.address")
        val ROW_D_USE = RowId("storage.detail.use")
        val ROW_D_TEST = RowId("storage.detail.test_folder")
        val ROW_D_NAME = RowId("storage.detail.name")
        val ROW_D_SIGNIN = RowId("storage.detail.signin")
        val ROW_D_REMOVE = RowId("storage.detail.remove")
        val ROW_STATUS = RowId("storage.find.status")
        /** The searching state of [ROW_STATUS]: its own id, so the UI draws the three dots on it (§9.4). */
        val ROW_SEARCHING = RowId("storage.find.searching")
        val ROW_VPN = RowId("storage.find.vpn")
        val ROW_ENTER = RowId("storage.find.enter")
        val ROW_PHONE_FIND = RowId("storage.find.phone")
        val ROW_AGAIN = RowId("storage.find.again")
        val ROW_S_STATUS = RowId("storage.shares.status")
        val ROW_S_ERROR = RowId("storage.shares.error")
        val ROW_S_RETRY = RowId("storage.shares.retry")
        val ROW_S_TYPE = RowId("storage.shares.type")
        val ROW_S_SIGNIN = RowId("storage.shares.signin")
        val ROW_SI_ERROR = RowId("storage.signin.error")
        val ROW_SI_NOKEYS = RowId("storage.signin.nokeys")
        val ROW_SI_PHONE = RowId("storage.signin.phone")
        val ROW_SI_USER = RowId("storage.signin.user")
        val ROW_SI_PASSWORD = RowId("storage.signin.password")
        val ROW_SI_GO = RowId("storage.signin.go")
        val ROW_SI_GUEST = RowId("storage.signin.guest")
        val ROW_SI_DOMAIN = RowId("storage.signin.domain")
        val ROW_SI_INFO = RowId("storage.signin.info")
        val ROW_F_NOTE = RowId("storage.folder.note")
        val ROW_F_USE = RowId("storage.folder.use")
        val ROW_F_UP = RowId("storage.folder.up")
        val ROW_F_STATUS = RowId("storage.folder.status")
        val ROW_F_ERROR = RowId("storage.folder.error")
        val ROW_T_PROGRESS = RowId("storage.test.progress")
        val ROW_T_STOP = RowId("storage.test.stop")
        val ROW_T_VERDICT = RowId("storage.test.verdict")
        val ROW_T_WAKE = RowId("storage.test.wake")
        val ROW_T_POOL = RowId("storage.test.pool")
        val ROW_T_USE = RowId("storage.test.use")
        val ROW_T_ANYWAY = RowId("storage.test.anyway")
        val ROW_T_CHOOSE = RowId("storage.test.choose")
        val ROW_T_AGAIN = RowId("storage.test.again")

        /** The form check of the "Enter a name or address" editor: ManualAddressParser's, or non-blank while it is a stub. */
        fun validHost(text: String): Boolean {
            if (text.isBlank()) return false
            val r = try { ManualAddressParser.parse(text.trim()) } catch (e: Throwable) { return true }
            return r is ManualAddressParser.Result.Parsed
        }

    }
}
