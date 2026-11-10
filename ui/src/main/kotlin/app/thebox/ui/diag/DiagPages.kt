package app.thebox.ui.diag

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import app.thebox.core.common.Outcome
import app.thebox.core.common.SourceError
import app.thebox.core.diag.DeviceFacts
import app.thebox.core.diag.DiagSources
import app.thebox.core.diag.Diagnostics
import app.thebox.core.diag.ExportTarget
import app.thebox.core.diag.NetworkTestEvent
import app.thebox.core.diag.NetworkTestRunner
import app.thebox.core.diag.NetworkTests
import app.thebox.core.diag.StepResult
import app.thebox.core.diag.SyncRow
import app.thebox.core.diag.SyncRowState
import app.thebox.core.diag.TestStep
import app.thebox.core.diag.Verdict
import app.thebox.core.diag.VerdictKey
import app.thebox.core.net.DnsChoice
import app.thebox.core.net.NetworkSettings
import app.thebox.core.net.PreferIpv4
import app.thebox.core.parental.ParentalSession
import app.thebox.core.playback.PlaybackEventType
import app.thebox.core.settings.ChoiceOption
import app.thebox.core.settings.ConfirmSpec
import app.thebox.core.settings.Domains
import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.PageModel
import app.thebox.core.settings.PhonePurpose
import app.thebox.core.settings.RowId
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsDomain
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.SettingsStore
import app.thebox.core.settings.Tone
import app.thebox.core.settings.Visibility
import app.thebox.core.settings.tree.AboutRows
import app.thebox.core.update.UpdateCheckScheduler
import app.thebox.core.update.UpdateDomain
import app.thebox.core.update.UpdateFailure
import app.thebox.core.update.UpdateState
import app.thebox.core.update.Updater
import app.thebox.core.update.isBusy
import app.thebox.player.api.PlaybackController
import app.thebox.ui.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** What an intent on a WP-E page asks the settings shell to do next. */
sealed interface DiagOutcome {
    /** Show [text] in the page's notice line (`SettingsEvent.Notice`). */
    data class Notice(val text: String) : DiagOutcome
    /**
     * Push [route] (the phone page for a log download, Sync status → Edit). [needsPin] (U-S6, code review 2026-10-16):
     * the route is gated (a playlist's login with "Lock settings" on), so the shell asks for the PIN first (S2: the sync
     * chip's Sync status is PIN-free, "Edit in its menu asks").
     */
    data class Open(val route: SettingsRoute, val needsPin: Boolean = false) : DiagOutcome
}

/**
 * The page builders of WP-E (docs/design/m5-setup-settings.md §3.3.10, §3.11, §3.12): the About section page, the
 * Diagnostics page, the connection test, Sync status and Licences, as [PageModel] flows the settings shell (WP-A's
 * `SettingsEffectsImpl`) collects for [handles] routes and hands to the machine as `SettingsEvent.Page`; intents on
 * their rows go to [onIntent]. Builders run on the caller's dispatcher (Default, §5.2); every text is from
 * `strings_diag.xml`; no step detail or log line carries a URL or a login.
 */
@Singleton
class DiagPages @Inject constructor(
    @ApplicationContext private val context: Context,
    private val updater: Updater,
    private val diagnostics: Diagnostics,
    private val tests: NetworkTests,
    private val sources: DiagSources,
    private val controller: PlaybackController,
    private val store: SettingsStore,
    private val scheduler: UpdateCheckScheduler,
    private val parental: ParentalSession,
) {
    private val version = AtomicLong(1)
    private val reruns = MutableStateFlow(0)
    private val notices = MutableStateFlow<Map<RowId, String>>(emptyMap())

    fun handles(route: SettingsRoute): Boolean = when (route) {
        is SettingsRoute.Section -> route.id == SectionId.ABOUT
        SettingsRoute.Diagnostics, is SettingsRoute.NetworkTest, SettingsRoute.SyncStatus, SettingsRoute.Licences -> true
        else -> false
    }

    /** The page for [route] as it changes (version bumped on every emission). Empty for routes this class does not build. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun page(route: SettingsRoute): Flow<PageModel> = when (route) {
        is SettingsRoute.Section -> if (route.id == SectionId.ABOUT) aboutPage(route) else flowOf()
        SettingsRoute.Diagnostics -> diagnosticsPage()
        is SettingsRoute.NetworkTest -> reruns.flatMapLatest { networkTestPage(route) }
        SettingsRoute.SyncStatus -> sources.syncRows().map { syncPage(it) }
        SettingsRoute.Licences -> flow { emit(licencesPage()) }
        else -> flowOf()
    }

    // ---------------------------------------------------------------- About (§3.3.10)

    private fun aboutPage(route: SettingsRoute): Flow<PageModel> =
        combine(updater.state, store.flow(UpdateDomain), parental.state, notices) { u, prefs, p, n ->
            val rows = listOf(
                SettingsRow(AboutRows.HEADER, s(R.string.about_title), kind = RowKind.Info(headerLines())),
                SettingsRow(AboutRows.UPDATE, s(R.string.about_update), value = updateValue(u), description = n[AboutRows.UPDATE] ?: updateDesc(u),
                    kind = RowKind.Action(), enabled = u !is UpdateState.Checking && !u.isBusy, effect = EffectTiming.LIVE),
                SettingsRow(AboutRows.DIAGNOSTICS, s(R.string.about_diagnostics), description = s(R.string.about_diagnostics_desc),
                    kind = RowKind.Link(SettingsRoute.Diagnostics), effect = EffectTiming.NONE),
                exportRow(AboutRows.EXPORT_LOGS, n),
                SettingsRow(AboutRows.LICENCES, s(R.string.about_licences), description = s(R.string.about_licences_desc),
                    kind = RowKind.Link(SettingsRoute.Licences), effect = EffectTiming.NONE),
                SettingsRow(AboutRows.RESET, s(R.string.about_reset), description = n[AboutRows.RESET] ?: s(R.string.about_reset_desc),
                    kind = RowKind.Action(destructive = true, needsPin = p.pinSet, confirm = ConfirmSpec(s(R.string.about_reset_confirm_title), s(R.string.about_reset_confirm_body), s(R.string.about_reset_confirm_ok), s(R.string.about_reset_confirm_cancel))),
                    effect = EffectTiming.RESTART),
                SettingsRow(AboutRows.AUTO_CHECK, s(R.string.about_auto_check), description = s(R.string.about_auto_check_desc),
                    kind = RowKind.Toggle(prefs.checkAutomatically), visibility = Visibility.ADVANCED, effect = EffectTiming.LIVE),
            )
            PageModel(route, s(R.string.about_title), rows = rows, version = version.incrementAndGet())
        }

    private fun exportRow(id: RowId, n: Map<RowId, String>): SettingsRow = SettingsRow(
        id, s(R.string.about_export_logs), value = n[id], description = s(R.string.about_export_desc),
        kind = RowKind.Choice(
            listOf(
                ChoiceOption(OPT_NONE, s(R.string.about_export_choose), enabled = false),
                ChoiceOption(OPT_PHONE, s(R.string.about_export_phone), s(R.string.about_export_phone_detail)),
                ChoiceOption(OPT_DOWNLOADS, s(R.string.about_export_downloads), s(R.string.about_export_downloads_detail)),
            ),
            selected = 0,
        ),
        effect = EffectTiming.NONE,
    )

    fun headerLines(): List<String> {
        val pm = context.packageManager
        val info = runCatching { pm.getPackageInfo(context.packageName, 0) }.getOrNull()
        @Suppress("DEPRECATION")
        val code = info?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() } ?: 0
        val debug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val installer = try {
            if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(context.packageName).installingPackageName
            else @Suppress("DEPRECATION") pm.getInstallerPackageName(context.packageName)
        } catch (e: Exception) {
            null
        }
        return listOf(
            context.getString(R.string.about_header_version, info?.versionName ?: "?", code.toInt()),
            context.getString(R.string.about_header_build, s(if (debug) R.string.about_build_debug else R.string.about_build_release), context.packageName),
            context.getString(R.string.about_header_abi, Build.SUPPORTED_ABIS.firstOrNull() ?: "?"),
            context.getString(R.string.about_header_installer, installer ?: s(R.string.about_installer_unknown)),
        )
    }

    /** What OK on the update row does now: it installs a release that is on offer, otherwise it checks. */
    fun updateDesc(u: UpdateState): String = when (u) {
        is UpdateState.Available -> context.getString(R.string.about_update_desc_available, u.manifest.versionName)
        is UpdateState.ReadyToInstall -> context.getString(R.string.about_update_desc_ready, u.versionName)
        is UpdateState.NotInstalled -> context.getString(R.string.about_update_desc_ready, u.versionName)
        else -> s(R.string.about_update_desc)
    }

    fun updateValue(u: UpdateState): String {
        val current = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
        return when (u) {
            UpdateState.Idle -> context.getString(R.string.about_update_value_idle, current)
            UpdateState.Checking -> s(R.string.about_update_value_checking)
            is UpdateState.UpToDate -> context.getString(R.string.about_update_value_up_to_date, current)
            is UpdateState.Available -> context.getString(R.string.about_update_value_available, u.manifest.versionName)
            is UpdateState.Downloading -> context.getString(R.string.about_update_value_downloading, if (u.total > 0) (u.bytes * 100 / u.total).toInt() else 0)
            is UpdateState.NeedsPermission -> s(R.string.about_update_value_permission)
            is UpdateState.ReadyToInstall -> context.getString(R.string.about_update_value_ready, u.versionName)
            is UpdateState.Verifying -> s(R.string.about_update_value_verifying)
            UpdateState.Installing -> s(R.string.about_update_value_installing)
            is UpdateState.NotInstalled -> context.getString(R.string.about_update_value_not_installed, u.versionName)
            is UpdateState.Failed -> when (u.reason) {
                UpdateFailure.DISABLED -> s(R.string.about_update_value_disabled)
                // Review N4: a download or install failure is not a failed check.
                UpdateFailure.HASH_MISMATCH, UpdateFailure.WRONG_PACKAGE, UpdateFailure.WRONG_CERT, UpdateFailure.NO_SPACE,
                UpdateFailure.INSTALL_BLOCKED, UpdateFailure.INSTALL_FAILED, UpdateFailure.INSTALL_RESTRICTED -> s(R.string.about_update_value_update_failed)
                else -> s(R.string.about_update_value_failed)
            }
        }
    }

    // ---------------------------------------------------------------- Diagnostics (§3.11.1)

    private fun diagnosticsPage(): Flow<PageModel> = flow {
        emit(PageModel(SettingsRoute.Diagnostics, s(R.string.diag_title), rows = emptyList(), version = version.incrementAndGet(), placeholder = s(R.string.diag_loading)))
        val facts = diagnostics.deviceFacts()
        val playlists = sources.playlists()
        emitAll(notices.map { n -> PageModel(SettingsRoute.Diagnostics, s(R.string.diag_title), rows = diagnosticsRows(facts, playlists, n), version = version.incrementAndGet()) })
    }

    private suspend fun <T> kotlinx.coroutines.flow.FlowCollector<T>.emitAll(f: Flow<T>) = f.collect { emit(it) }

    fun diagnosticsRows(f: DeviceFacts, playlists: List<app.thebox.core.diag.PlaylistBrief>, n: Map<RowId, String> = emptyMap()): List<SettingsRow> {
        val none = s(R.string.diag_none)
        val device = listOfNotNull(
            context.getString(R.string.diag_device_model, f.manufacturer, f.model),
            context.getString(R.string.diag_device_os, f.os, f.sdk),
            f.fireOs?.let { context.getString(R.string.diag_device_fireos, it) },
            context.getString(R.string.diag_device_memory, f.memTotalMb, f.memoryClassMb, f.lowRam.toString(), f.performanceMode),
            context.getString(R.string.diag_device_abis, f.abis.joinToString()),
            context.getString(R.string.diag_device_storage, f.freeStorageMb.toInt(), f.network),
        )
        val fallbacks = runCatching { controller.diagnostics.events().count { it.type == PlaybackEventType.AUDIO_FALLBACK } }.getOrDefault(0)
        val sound = listOf(
            if (f.hdmiEncodings.isEmpty()) s(R.string.diag_sound_none) else context.getString(R.string.diag_sound_hdmi, f.hdmiEncodings.joinToString()),
            context.getString(R.string.diag_sound_fallbacks, fallbacks),
        )
        val picture = listOf(
            context.getString(R.string.diag_picture_hdr, f.hdrTypes.joinToString().ifEmpty { none }),
            context.getString(R.string.diag_picture_modes, f.displayModes.take(6).joinToString().ifEmpty { none }),
        )
        val decoders = f.decoders.map { d ->
            context.getString(R.string.diag_decoder_line, d.mime.removePrefix("video/").uppercase(), d.name, s(if (d.hardware) R.string.diag_decoder_hw else R.string.diag_decoder_sw), d.maxResolution ?: "?")
        }.ifEmpty { listOf(s(R.string.diag_decoders_none)) }
        val zaps = runCatching { controller.diagnostics.zaps().takeLast(ZAPS_SHOWN).reversed() }.getOrDefault(emptyList()).map { z ->
            context.getString(R.string.diag_zap_line, z.format.name, ms(z.readyMs), ms(z.firstFrameMs), z.outcome)
        }.ifEmpty { listOf(s(R.string.diag_zaps_none)) }
        val rows = ArrayList<SettingsRow>(16 + playlists.size)
        rows += info("diag.device", R.string.diag_device, device)
        rows += info("diag.sound", R.string.diag_sound, sound)
        rows += info("diag.picture", R.string.diag_picture, picture)
        rows += info("diag.decoders", R.string.diag_decoders, decoders)
        rows += info("diag.ffmpeg", R.string.diag_ffmpeg, listOf(s(if (f.ffmpegAudio) R.string.diag_ffmpeg_yes else R.string.diag_ffmpeg_no)))
        rows += SettingsRow(RowId("diag.security"), s(R.string.diag_security), kind = RowKind.Info(listOf(s(if (f.keystoreBacked) R.string.diag_security_keystore else R.string.diag_security_fallback)), if (f.keystoreBacked) Tone.GOOD else Tone.WARN), effect = EffectTiming.NONE)
        rows += info("diag.zaps", R.string.diag_zaps, zaps)
        for (p in playlists) {
            rows += SettingsRow(RowId("diag.test.${p.id}"), context.getString(R.string.diag_test, p.name), value = p.displayHost, description = s(R.string.diag_test_desc),
                kind = RowKind.Link(SettingsRoute.NetworkTest(p.id)), effect = EffectTiming.NONE)
        }
        rows += SettingsRow(DIAG_SYNC, s(R.string.diag_sync), kind = RowKind.Link(SettingsRoute.SyncStatus), effect = EffectTiming.NONE)
        rows += exportRow(DIAG_EXPORT, n)
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) rows += info("diag.debug", R.string.diag_debug, listOf("thebox-debug://guide, thebox-debug://play"))
        return rows
    }

    private fun info(id: String, label: Int, lines: List<String>) = SettingsRow(RowId(id), s(label), kind = RowKind.Info(lines), effect = EffectTiming.NONE)

    private fun ms(v: Long?): String = v?.let { context.getString(R.string.diag_ms, it.toInt()) } ?: s(R.string.diag_dash)

    // ---------------------------------------------------------------- connection test (§3.11.2)

    private fun networkTestPage(route: SettingsRoute.NetworkTest): Flow<PageModel> =
        tests.run(route.playlistId)
            .scan(TestProgress()) { acc, e ->
                when (e) {
                    is NetworkTestEvent.Step -> acc.copy(steps = acc.steps + e.result)
                    is NetworkTestEvent.Done -> acc.copy(verdict = e.result.verdict, done = true)
                }
            }
            .onCompletionEmpty(route)
            .map { p -> networkTestModel(route, p) }

    data class TestProgress(val steps: List<StepResult> = emptyList(), val verdict: Verdict? = null, val done: Boolean = false, val noTarget: Boolean = false)

    /** A playlist without a server (an M3U file) produces no event: the page says so instead of spinning. */
    private fun Flow<TestProgress>.onCompletionEmpty(route: SettingsRoute.NetworkTest): Flow<TestProgress> = flow {
        var last: TestProgress? = null
        collect { last = it; emit(it) }
        if (last == null || (last!!.steps.isEmpty() && !last!!.done)) emit(TestProgress(noTarget = true, done = true))
    }

    fun networkTestModel(route: SettingsRoute.NetworkTest, p: TestProgress): PageModel {
        if (p.noTarget) return PageModel(route, s(R.string.nettest_title), rows = emptyList(), version = version.incrementAndGet(), placeholder = s(R.string.nettest_no_target))
        val rows = ArrayList<SettingsRow>(10)
        for (step in p.steps) {
            val text = stepText(step)
            rows += SettingsRow(RowId("nettest.${step.step.name.lowercase()}"), stepLabel(step.step),
                value = step.ms?.let { context.getString(R.string.diag_ms, it.toInt()) },
                kind = RowKind.Info(listOf(context.getString(if (step.ok) R.string.nettest_ok else R.string.nettest_fail, text)), if (step.ok) Tone.NORMAL else Tone.BAD),
                effect = EffectTiming.NONE)
        }
        val v = p.verdict
        if (v != null) {
            rows += SettingsRow(NETTEST_VERDICT, s(R.string.nettest_verdict), kind = RowKind.Info(listOf(verdictText(v)), if (v.key == VerdictKey.ALL_GOOD) Tone.GOOD else Tone.WARN), effect = EffectTiming.NONE)
            when (v.key) {
                VerdictKey.DNS_BLOCKED_TRY_SECURE -> rows += SettingsRow(NETTEST_SECURE_DNS, s(R.string.nettest_action_secure_dns), kind = RowKind.Action(), effect = EffectTiming.LIVE)
                VerdictKey.IPV6_BROKEN -> rows += SettingsRow(NETTEST_IPV4, s(R.string.nettest_action_ipv4), kind = RowKind.Action(), effect = EffectTiming.LIVE)
                // C-B2 hand-over (code review 2026-10-16): as the Playlist page's Edit source row, the PIN only with "Lock settings" on.
                VerdictKey.AUTH_PROBLEM -> rows += SettingsRow(NETTEST_EDIT_LOGIN, s(R.string.nettest_action_edit_login),
                    kind = RowKind.Link(SettingsRoute.PlaylistSource(route.playlistId), needsPin = parental.state.value.let { it.pinSet && it.policy.lockSettings }), effect = EffectTiming.NONE)
                else -> Unit
            }
        }
        if (p.done) rows += SettingsRow(NETTEST_AGAIN, s(R.string.nettest_action_again), kind = RowKind.Action(), effect = EffectTiming.NONE)
        return PageModel(route, s(R.string.nettest_title), subtitle = if (p.done) null else s(R.string.nettest_running), rows = rows, version = version.incrementAndGet())
    }

    fun stepLabel(step: TestStep): String = s(
        when (step) {
            TestStep.DNS_SYSTEM -> R.string.nettest_step_dns_system
            TestStep.DNS_SECURE -> R.string.nettest_step_dns_secure
            TestStep.TCP_CONNECT -> R.string.nettest_step_connect
            TestStep.HTTP -> R.string.nettest_step_http
            TestStep.API_LATENCY -> R.string.nettest_step_latency
            TestStep.STREAM_THROUGHPUT -> R.string.nettest_step_stream
        },
    )

    /** User text for a step's machine detail (`NetworkTestRunner` key=value form). */
    fun stepText(r: StepResult): String {
        val d = NetworkTestRunner.parseDetail(r.detail)
        if (d["skipped"] == "1") return s(when (r.step) { TestStep.DNS_SECURE -> R.string.nettest_skipped_secure; TestStep.STREAM_THROUGHPUT -> R.string.nettest_stream_skipped; else -> R.string.nettest_skipped })
        return when (r.step) {
            TestStep.DNS_SYSTEM, TestStep.DNS_SECURE -> {
                val base = when (d["error"]) {
                    "nxdomain" -> s(R.string.nettest_dns_fail)
                    "blocked" -> s(R.string.nettest_dns_blocked)
                    else -> context.getString(R.string.nettest_dns_ok, d["n"]?.toIntOrNull() ?: 0, d["v4"]?.toIntOrNull() ?: 0, d["v6"]?.toIntOrNull() ?: 0)
                }
                when (d["agree"]) {
                    "1" -> base + " · " + s(R.string.nettest_dns_agree)
                    "0" -> if (d["error"] == null) base + " · " + s(R.string.nettest_dns_disagree) else base
                    else -> base
                }
            }
            TestStep.TCP_CONNECT -> context.getString(R.string.nettest_connect, fam(d["v4"]), fam(d["v6"]), d["port"] ?: "?")
            TestStep.HTTP -> when (d["error"]) {
                null -> when (d["active"]) {
                    "1" -> context.getString(R.string.nettest_http_ok, d["status"] ?: "?")
                    "0" -> context.getString(R.string.nettest_http_inactive, d["status"] ?: "?")
                    else -> context.getString(R.string.nettest_http_ok_plain, d["status"] ?: "?")
                }
                "auth" -> s(R.string.nettest_http_auth)
                "tls" -> s(R.string.nettest_http_tls)
                "http" -> context.getString(R.string.nettest_http_error, d["status"] ?: "?")
                "format" -> s(R.string.nettest_http_format)
                else -> s(R.string.nettest_http_net)
            }
            TestStep.API_LATENCY -> if (d["median"] != null) context.getString(R.string.nettest_latency, d["median"]) else s(R.string.nettest_http_net)
            TestStep.STREAM_THROUGHPUT -> context.getString(R.string.nettest_stream, mbit(d["delivered"]), mbit(d["needed"]))
        }
    }

    private fun fam(v: String?): String = when (v) {
        null, "none" -> s(R.string.nettest_connect_none)
        "fail" -> s(R.string.nettest_connect_failed)
        else -> context.getString(R.string.diag_ms, v.toIntOrNull() ?: 0)
    }

    private fun mbit(v: String?): String = v?.toLongOrNull()?.let { String.format(java.util.Locale.ROOT, "%.1f", it / 1_000_000.0) } ?: "?"

    fun verdictText(v: Verdict): String = when (v.key) {
        VerdictKey.ALL_GOOD -> s(R.string.verdict_all_good)
        VerdictKey.DNS_BLOCKED_TRY_SECURE -> s(R.string.verdict_dns_blocked)
        VerdictKey.SERVER_UNREACHABLE -> if (v.args.isEmpty()) s(R.string.verdict_name_not_found) else context.getString(R.string.verdict_unreachable, v.args[0])
        VerdictKey.TLS_PROBLEM -> s(R.string.verdict_tls)
        VerdictKey.AUTH_PROBLEM -> s(R.string.verdict_auth)
        VerdictKey.SLOW_PROVIDER -> context.getString(R.string.verdict_slow_provider, v.args.getOrElse(0) { "?" }, v.args.getOrElse(1) { "?" })
        VerdictKey.SLOW_CONNECTION -> context.getString(R.string.verdict_slow_connection, v.args.getOrElse(0) { "?" }, v.args.getOrElse(1) { "?" })
        VerdictKey.IPV6_BROKEN -> s(R.string.verdict_ipv6)
        VerdictKey.NAME_NOT_FOUND -> s(R.string.verdict_name_not_found)
    }

    // ---------------------------------------------------------------- Sync status (§3.3.2, S15)

    fun syncPage(rows: List<SyncRow>): PageModel {
        val out = ArrayList<SettingsRow>(rows.size + 1)
        out += SettingsRow(SYNC_NOW, s(R.string.sync_update_now), description = s(R.string.sync_update_now_desc), kind = RowKind.Action(), effect = EffectTiming.LIVE)
        for (r in rows) {
            val label = if (r.playlistId == null) s(R.string.sync_guide) else r.label
            val value = when (r.state) {
                SyncRowState.IDLE -> s(R.string.sync_state_idle)
                SyncRowState.QUEUED -> s(R.string.sync_state_queued)
                SyncRowState.RUNNING -> r.progress ?: s(R.string.sync_state_running)
                SyncRowState.DONE -> s(R.string.sync_state_done)
                SyncRowState.FAILED -> context.getString(R.string.sync_state_failed, errorText(r.errorCode))
            }
            val menu = buildList {
                if (r.playlistId != null) add(ChoiceOption(MENU_EDIT, s(R.string.sync_menu_edit)))
                add(ChoiceOption(MENU_RETRY, s(R.string.sync_menu_retry)))
                if (r.failureLogId != null) add(ChoiceOption(MENU_DISMISS, s(R.string.sync_menu_dismiss)))
            }
            out += SettingsRow(RowId("sync.${r.key}"), label, value = value,
                kind = RowKind.Item(route = r.playlistId?.let { SettingsRoute.Playlist(it) }, primary = if (r.playlistId == null) MENU_RETRY else null, menu = menu,
                    badges = if (r.state == SyncRowState.FAILED) setOf(app.thebox.core.settings.Badge.FAILED) else emptySet()),
                effect = EffectTiming.NONE)
        }
        return PageModel(SettingsRoute.SyncStatus, s(R.string.sync_title), rows = out, version = version.incrementAndGet())
    }

    fun errorText(code: String?): String = when {
        code == null -> s(R.string.diag_dash)
        code == "http.403" -> s(R.string.sync_error_http_403)
        code.startsWith("http.") -> context.getString(R.string.sync_error_http, code.removePrefix("http."))
        code.startsWith("net.") -> s(R.string.sync_error_net)
        code.startsWith("auth.") -> s(R.string.sync_error_auth)
        code.startsWith("format.") || code == "parse" -> s(R.string.sync_error_format)
        code == "disk" -> s(R.string.sync_error_disk)
        else -> context.getString(R.string.sync_error_other, code)
    }

    // ---------------------------------------------------------------- Licences (N16)

    fun licencesPage(): PageModel {
        val rows = ArrayList<SettingsRow>(64)
        for (l in LICENCES) {
            val text = runCatching { context.assets.open("licenses/${l.file}").bufferedReader().use { it.readLines() } }.getOrDefault(emptyList())
            rows += SettingsRow(RowId("licence.${l.file}"), s(l.title), value = s(l.licence), kind = RowKind.Info(emptyList()), effect = EffectTiming.NONE)
            text.chunked(LICENCE_LINES_PER_ROW).forEachIndexed { i, chunk ->
                rows += SettingsRow(RowId("licence.${l.file}.$i"), "", kind = RowKind.Info(chunk), effect = EffectTiming.NONE)
            }
        }
        return PageModel(SettingsRoute.Licences, s(R.string.licences_title), rows = rows, version = version.incrementAndGet())
    }

    data class Licence(val file: String, val title: Int, val licence: Int)

    // ---------------------------------------------------------------- intents

    /**
     * Executes [intent] if it is one of WP-E's rows; false otherwise. Long work runs on [scope] (IO inside the
     * sources); [out] receives notices and navigation on the caller's dispatcher of [scope].
     */
    fun onIntent(intent: RowIntent, scope: CoroutineScope, out: (DiagOutcome) -> Unit): Boolean {
        val id = intent.row
        when {
            // A release already on offer (found by a check, or put off with the card's Later / BACK) is installed from
            // here in one press; otherwise the row checks.
            id == AboutRows.UPDATE && intent is RowIntent.Run -> when (updater.state.value) {
                is UpdateState.Available -> updater.download()
                is UpdateState.ReadyToInstall, is UpdateState.NeedsPermission, is UpdateState.NotInstalled -> updater.install()
                else -> updater.check(manual = true)
            }
            id == AboutRows.AUTO_CHECK && intent is RowIntent.Toggle -> {
                store.update(UpdateDomain) { it.copy(checkAutomatically = intent.on) }
                scheduler.scheduleCheck()
            }
            (id == AboutRows.EXPORT_LOGS || id == DIAG_EXPORT) && intent is RowIntent.Choose -> {
                val target = when (intent.optionId) {
                    OPT_PHONE -> ExportTarget.PHONE
                    OPT_DOWNLOADS -> ExportTarget.DOWNLOADS
                    else -> return true
                }
                scope.launch {
                    when (val r = sources.export(target, System.currentTimeMillis())) {
                        is Outcome.Ok -> {
                            val text = if (target == ExportTarget.PHONE) context.getString(R.string.about_export_done_phone, r.value.fileName)
                            else context.getString(R.string.about_export_done, r.value.fileName, r.value.where)
                            notices.value = notices.value + (id to text)
                            out(DiagOutcome.Notice(text))
                            if (target == ExportTarget.PHONE) out(DiagOutcome.Open(SettingsRoute.PhoneSetup(purpose = PhonePurpose.LOGS_DOWNLOAD)))
                        }
                        is Outcome.Err -> {
                            val text = exportError(r.error)
                            notices.value = notices.value + (id to text)
                            out(DiagOutcome.Notice(text))
                        }
                    }
                }
            }
            id == AboutRows.RESET && intent is RowIntent.Run -> {
                if (!sources.resetApp()) {
                    val text = s(R.string.about_reset_failed)
                    notices.value = notices.value + (id to text)
                    out(DiagOutcome.Notice(text))
                }
            }
            id == NETTEST_AGAIN && intent is RowIntent.Run -> reruns.value++
            id == NETTEST_SECURE_DNS && intent is RowIntent.Run -> {
                store.update(NetworkDomain) { it.copy(dns = DnsChoice.CLOUDFLARE) }
                out(DiagOutcome.Notice(s(R.string.nettest_action_secure_dns_done)))
                reruns.value++
            }
            id == NETTEST_IPV4 && intent is RowIntent.Run -> {
                store.update(NetworkDomain) { it.copy(preferIpv4 = PreferIpv4.ON) }
                out(DiagOutcome.Notice(s(R.string.nettest_action_ipv4_done)))
                reruns.value++
            }
            id == SYNC_NOW && intent is RowIntent.Run -> {
                sources.updateGuideNow()
                out(DiagOutcome.Notice(s(R.string.sync_update_now_started)))
            }
            id.key.startsWith("sync.") && (intent is RowIntent.Menu || intent is RowIntent.Run) -> {
                val key = id.key.removePrefix("sync.")
                val option = (intent as? RowIntent.Menu)?.optionId ?: MENU_RETRY
                val pid = key.removePrefix("playlist:").takeIf { key.startsWith("playlist:") }?.toLongOrNull()
                scope.launch {
                    val row = sources.syncRowsSnapshot(key)
                    when (option) {
                        MENU_RETRY -> {
                            if (pid != null) sources.refreshPlaylist(pid) else sources.updateGuideNow()
                            out(DiagOutcome.Notice(s(R.string.sync_retry_started)))
                        }
                        MENU_DISMISS -> row?.failureLogId?.let { sources.acknowledge(it) }
                        // U-S6: Edit opens the login form; with "Lock settings" on it asks for the PIN like the row does.
                        MENU_EDIT -> pid?.let { out(DiagOutcome.Open(SettingsRoute.PlaylistSource(it), needsPin = parental.state.value.policy.lockSettings)) }
                    }
                }
            }
            else -> return false
        }
        return true
    }

    private suspend fun DiagSources.syncRowsSnapshot(key: String): SyncRow? =
        kotlinx.coroutines.withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) { syncRows().first().firstOrNull { it.key == key } }

    fun exportError(e: SourceError): String = when (e) {
        is SourceError.DiskSpace -> s(R.string.about_export_failed_space)
        is SourceError.Unsupported -> if (e.detail.contains("permission")) s(R.string.about_export_failed_permission) else s(R.string.about_export_failed)
        else -> s(R.string.about_export_failed)
    }

    private fun s(id: Int): String = context.getString(id)

    companion object {
        const val OPT_NONE = "none"
        const val OPT_PHONE = "phone"
        const val OPT_DOWNLOADS = "downloads"
        const val MENU_EDIT = "edit"
        const val MENU_RETRY = "retry"
        const val MENU_DISMISS = "dismiss"
        const val ZAPS_SHOWN = 10
        const val SNAPSHOT_TIMEOUT_MS = 2_000L
        const val LICENCE_LINES_PER_ROW = 12
        val DIAG_SYNC = RowId("diag.sync")
        val DIAG_EXPORT = RowId("diag.export_logs")
        val NETTEST_VERDICT = RowId("nettest.verdict")
        val NETTEST_SECURE_DNS = RowId("nettest.secure_dns")
        val NETTEST_IPV4 = RowId("nettest.ipv4")
        val NETTEST_EDIT_LOGIN = RowId("nettest.edit_login")
        val NETTEST_AGAIN = RowId("nettest.again")
        val SYNC_NOW = RowId("sync.update_now")

        val LICENCES = listOf(
            Licence("FFmpeg-LGPL-2.1.txt", R.string.licences_ffmpeg, R.string.licences_lgpl),
            Licence("Apache-2.0.txt", R.string.licences_apache, R.string.licences_apache2),
            Licence("qrcodegen-MIT.txt", R.string.licences_qrcodegen, R.string.licences_mit),
            Licence("RE2J-BSD.txt", R.string.licences_re2j, R.string.licences_bsd),
            Licence("InstrumentSans-OFL.txt", R.string.licences_instrument, R.string.licences_ofl),
            Licence("JetBrainsMono-OFL.txt", R.string.licences_jetbrains, R.string.licences_ofl),
            // (Goal animation, docs/design/m6-goal-animation.md §9) The GOAL word's font.
            Licence("BarlowCondensed-OFL.txt", R.string.licences_barlow, R.string.licences_ofl),
            // (owner ruling 2026-10-18) The TMDB API terms ask for this attribution wherever posters are shown.
            Licence("TMDB-attribution.txt", R.string.licences_tmdb, R.string.licences_tmdb_terms),
        )

        /** The `network` domain: WP-A's object (M5 integration). */
        val NetworkDomain: SettingsDomain<NetworkSettings> get() = app.thebox.core.settings.state.NetworkDomain
    }
}
