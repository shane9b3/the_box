# M1 "Data in": data-layer design

Status: **proposed, 2026-10-13; reviewed 2026-10-13** (`review/m1-design-review.md`: 6 blockers and 22 should-fix items applied in place, each marked "(reviewed 2026-10-13: …)"). Lead designer for M1. The lead commits the shared contracts (§1.2) and starts the four work packages after reconciling the review's "lead decisions" list.
Inputs: `00-master-plan.md` §3 and §8, `review/architecture-review.md` B3, B5, B7, C4–C7 and §E, `research/android-tv-architecture.md` §3, `research/protocols-m3u-xmltv-xtream.md` §1–§4 (the spec), `review/product-review.md` §2.3 (S5, S12, S13, S15), §4.2–4.3 and §4.12, `review/m0-review.md` L2, L10–L13.

This document is the contract between four implementers. Where it says **must**, a reviewer rejects code that does otherwise. Where it gives a number (batch size, threshold, budget), the number is the default and lives in one named constant.

## Lead sign-off (2026-10-13)

Approved after the design review (`docs/review/m1-design-review.md`). Lead decisions on the review's open items:
- **B1:** accept one extra `player_api.php` login per refresh for M3U playlists whose URLs vote as Xtream; the panel zone is worth one request.
- **Non-Xtream M3U:** per-URL tokens and minority credential pairs remain inside `channel.url` (they *are* the URL). Redaction covers logs and exports; the DB file itself is app-private.
- **Schema additions approved:** `purged_key` tombstones, standalone `channel_fts`, `epg_source.ordinal` with its unique index.
- **`programme_by_text`:** built after the load by default; switch to before-load only if perf test I-5 exceeds the 64 MB native budget.
- **Open question (b):** M3U `{Y}{m}{d}` tokens default to UTC (product spec) with the per-playlist zone setting as the escape hatch; Xtream-detected M3U lists use the panel zone via B1.

## 0. Key decisions at a glance

1. **All parsers and the whole `epg.db` engine are pure JVM code in `:core`.** They are written against `okio`, `org.xmlpull` (kxml2 on the JVM, the platform parser on Android) and the KMP `androidx.sqlite` driver API. `:core` tests therefore run the **same bundled SQLite build** (`sqlite-bundled` JVM variant, FTS5 included) that ships on the device. `:data` only wires drivers, files, OkHttp, Room, WorkManager, Hilt and Tink.
2. **One import shape for every source.** Each source is downloaded to a cache file first (conditional GET), then parsed from the file. M3U and Xtream lists are parsed twice: pass 1 validates and counts (shrink guard, HLS-manifest and HTML detection, credential templating), pass 2 merges. A broken download never touches the database.
3. **Playlist merge** happens in batches of 2,000 rows, each batch in its own transaction, keyed by `(playlist_id, stable_key)`. Missing channels are only marked missing in the final transaction, and only if pass 1 passed. User data never lives on rows that a refresh owns (`channel_prefs` and `group_prefs` are separate tables).
4. **`epg.db` builds generation-named files** with journal off. Programmes are inserted in arrival order into `programme WITHOUT ROWID`, and a per-channel **repair pass** then clips overlaps, infers missing stops and records `max_len_min`. No staging table and no external sort. The pointer file is written with temp + rename. Readers are ref-counted and read-only. A *patch* mode (rollback journal, in place) handles targeted re-imports after a new mapping, and an *in-place* mode handles low disk space.
5. **EPG times are stored raw (unshifted).** The EPG shift (per source, playlist, channel) is applied when reading, so a shift change needs no re-import, and catch-up always uses raw times (protocols §2.6, Kodi behaviour).
6. **The whitelist is decided by a callback**: the importer hands each source's `<channel>` list to `EpgWhitelistResolver` (the matcher, WP-D), which returns the ids to keep. Every `<channel>` row is stored. Programmes are stored only for whitelisted ids.
7. **Matcher defaults** follow the product spec: exact id, then case-insensitive id, then iptv-org base id, then normalised `tvg-name`/name. Fuzzy matching only *suggests* by default (≥ 0.75). Auto-applying at ≥ 0.92 is an opt-in level. A digit-set guard and token blocking keep it correct and fast.
8. **Xtream JSON** goes through kotlinx-serialization **tree-per-item** decoding (`decodeToSequence` of `JsonElement`) plus lenient accessors. There are no annotation-driven DTO serializers.
9. **Credentials never sit in clear in `library.db`.** Source URLs, usernames and passwords live in a Tink AEAD blob per playlist. Stream URLs with embedded Xtream credentials are stored as templates (`{{u}}`/`{{p}}`).
10. **Tests:** `:core` uses JUnit 5 on the JVM (parsers, keys, diff, matcher, catch-up, and the whole `epg.db` including perf). `:data` uses JUnit 4 + Robolectric (Room DAOs and merge through `AndroidSQLiteDriver`) and MockWebServer for the Xtream client. A thin instrumented suite on the API 30 emulator covers the bundled driver + Room, Keystore/Tink, WorkManager, native memory during the EPG build, and platform-parser parity; one test runs on the API 34 AVD for the API 31+ foreground-service rules. (reviewed 2026-10-13: `SQLITE_TMPDIR` dropped. The bundled build is compiled with `TEMP_STORE=3`, so temp storage is always in memory.)

---

## 1. Packages, files and ownership

### 1.1 Rules of engagement

- **Commit 0 (lead, before any WP starts):** everything in §1.2 (shared contracts, a few complete utilities, the M0 model edits) plus all build changes in §6 (catalog, `core/build.gradle.kts`, `data/build.gradle.kts`, `app/build.gradle.kts`, the `perfTest` task). After commit 0, `./gradlew :core:test :data:testDebugUnitTest :app:assembleDebug` must be green.
- **Stub classes:** commit 0 also creates each implementation class named in §1.2 as a stub (`TODO("WP-x")`) at its final path. The owning WP replaces the body. Nobody else edits that file. WP-D binds the stubs in Hilt on day one, so WP-D compiles before A, B and C land.
- **No WP edits a file owned by another WP, a contract file, or a build file.** If a contract is wrong, raise it with the lead, who amends commit 0 for everyone (additive changes only, with a default value for every new field).
- **Packages are disjoint.** Each WP owns whole packages (listed below), so merge conflicts can only happen in files the lead owns.
- **Tests:** each WP owns `src/test/.../<its packages>` and its fixture directory under `src/test/resources/<dir>/`.
- **Merge order:** A, B and C in any order, then D's integration tests (§5.6) are switched on. Every WP rebases on `main` before review.

| WP | Owns (packages) | Owns (other files) |
|---|---|---|
| A | `:core` `app.thebox.core.m3u`, `app.thebox.core.identity`, `app.thebox.core.refresh` (except the contract file) | `core/src/test/resources/m3u/`, `core/src/test/resources/refresh/` |
| B | `:core` `app.thebox.core.xmltv`, `app.thebox.core.epg.store` | `core/src/test/resources/xmltv/`, `core/src/testFixtures/kotlin/app/thebox/core/epg/testutil/` (reviewed 2026-10-13: test fixtures, so `:data` can use them) |
| C | `:core` `app.thebox.core.xtream` (except the contract file), `app.thebox.core.catchup` (except the contract file); `:data` `app.thebox.data.xtream` | `core/src/test/resources/xtream/`, `core/src/test/resources/catchup/` |
| D | `:core` `app.thebox.core.match` (except the contract file); `:data` `app.thebox.data.db`, `.repo`, `.net`, `.security`, `.work`, `.di`, `.epg`; `:app` `TheBoxApplication.kt` and `AndroidManifest.xml` | `data/schemas/`, `data/src/test/resources/`, `data/src/androidTest/` |
| Lead | every file listed in §1.2, all `build.gradle.kts` files, `gradle/libs.versions.toml` | |

### 1.2 Shared contracts (commit 0, lead)

All of this is pasted as written. Paths are relative to `core/src/main/kotlin/app/thebox/core/` unless they start with `data/`.

#### 1.2.1 Model edits (M0 follow-ups L10, L11)

`model/CatchupMode.kt` (replaces the file; the existing `ModelTest` still passes):

```kotlin
package app.thebox.core.model

/** How archive (catch-up) URLs are built for a channel. Spec: protocols reference §4. */
enum class CatchupMode {
    /** Xtream Codes `timeshift` endpoint. */
    XC,
    /** `catchup-source` is a full URL template. */
    DEFAULT,
    /** `catchup-source` is appended to the live URL. */
    APPEND,
    /** SIPTV: `?utc={utc}&lutc={lutc}` appended to the live URL. */
    SHIFT,
    /** Flussonic; container taken from the live URL (`…/mpegts` = TS, `*.m3u8` = HLS). `flussonic`, `fs`. */
    FLUSSONIC,
    /** Flussonic forced to HLS (`flussonic-hls`). */
    FLUSSONIC_HLS,
    /** Flussonic forced to MPEG-TS (`flussonic-ts`). */
    FLUSSONIC_TS,
    /** `catchup-source` with `{catchup-id}` per programme (v1.1 consumer; builder supports it in M1). */
    VOD,
    /** No archive. */
    OFF,
    ;

    val isFlussonic: Boolean get() = this == FLUSSONIC || this == FLUSSONIC_HLS || this == FLUSSONIC_TS

    companion object {
        /** Maps an M3U `catchup=` / `catchup-type=` value. Unknown or empty → [OFF]. */
        fun fromM3uAttribute(value: String?): CatchupMode = when (value?.trim()?.lowercase()) {
            "xc", "xtream", "xtream-codes", "xtreamcodes" -> XC
            "default" -> DEFAULT
            "append" -> APPEND
            "shift", "timeshift" -> SHIFT
            "flussonic", "fs" -> FLUSSONIC
            "flussonic-hls" -> FLUSSONIC_HLS
            "flussonic-ts" -> FLUSSONIC_TS
            "vod" -> VOD
            else -> OFF
        }
    }
}
```

`model/Channel.kt`: add three fields with defaults (`kind`, `xtreamStreamId`, `adult`) and `Catchup.daysSource`:

```kotlin
package app.thebox.core.model

import app.thebox.core.source.EntryKind

/** A channel inside a playlist, as read from library.db. Identity is `(playlistId, stableKey)`, never a row id. */
data class Channel(
    val playlistId: Long,
    val stableKey: StableKey,
    val name: String,
    /** URL template (see §3.2.4 of docs/design/m1-data-layer.md); M2's StreamUrlResolver expands it. */
    val streamUrl: String,
    val number: Int? = null,
    val group: String? = null,
    val logoUrl: String? = null,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val catchup: Catchup = Catchup.NONE,
    /** Per-channel EPG shift in hours (`tvg-shift`), applied on top of the source shift. */
    val epgShiftHours: Double = 0.0,
    val kind: EntryKind = EntryKind.LIVE,
    val xtreamStreamId: Long? = null,
    val adult: Boolean = false,
)

/** Resolved catch-up capability of one channel (output of `CatchupResolver`). */
data class Catchup(
    val mode: CatchupMode,
    /** Archive depth in days, always ≥ 1 when [mode] != OFF (resolver guarantees it). */
    val days: Int = 0,
    val source: String? = null,
    /** Per-channel `catchup-correction` in hours; overrides [TimeBase.correctionHours] when set. */
    val correctionHours: Double? = null,
    /** Which link of the archive-days chain produced [days] (diagnostics, channel info). */
    val daysSource: ArchiveDaysSource? = null,
) {
    val isAvailable: Boolean get() = mode != CatchupMode.OFF && days > 0

    companion object {
        val NONE = Catchup(CatchupMode.OFF)
    }
}

/** Links of the archive-days fallback chain, §3.8.1. Append-only (persisted by name). */
enum class ArchiveDaysSource {
    PLAYLIST_OVERRIDE, CATCHUP_DAYS, TIMESHIFT, TVG_REC, CATCHUP_TIME, XTREAM_DURATION, PLAYLIST_HEADER, DEFAULT,
}
```

`model/StableKey.kt`: add to the companion (prefixes stay as in M0), plus one member function (reviewed 2026-10-13: `withOccurrence` is a member, not a companion-scoped extension, so callers need no special import):

```kotlin
    // member of value class StableKey
    /** Occurrence suffix for duplicate keys inside one playlist (2nd occurrence → `~2`). */
    fun withOccurrence(n: Int): StableKey = if (n <= 1) this else StableKey("$value~$n")

    // in the companion
        /** Xtream VOD (`/movie/`) and series-episode (`/series/`) ids live in other id spaces. */
        fun xtreamMovie(streamId: Long): StableKey = StableKey("xcm:$streamId")
        fun xtreamEpisode(episodeId: Long): StableKey = StableKey("xce:$episodeId")
```

`model/TimeBase.kt`: add one field with a default (reviewed 2026-10-13, blocker B1: separates the zone for `{Y}…` template tokens from the panel zone used by `xc`, so that "M3U tokens default to UTC" can no longer override the panel zone for Xtream timeshift):

```kotlin
    /**
     * Zone for `{Y}{m}{d}{H}{M}{S}` / `{utc:FMT}` / `${start-iso}` template tokens when [tokenZone] is
     * null. TokenZoneSetting.Auto sets UTC for M3U playlists and null for Xtream (→ [effectiveTokenZone]).
     * Never used for the `xc` timeshift segment, which always uses [effectiveTokenZone].
     */
    val templateZone: ZoneId? = null,
```

and the derived property:

```kotlin
    /** Zone for template tokens: [tokenZone] → [templateZone] → [effectiveTokenZone]; null → caller uses UTC with a diagnostic. */
    val effectiveTemplateZone: ZoneId? get() = tokenZone ?: templateZone ?: effectiveTokenZone
```

#### 1.2.2 Common: errors, outcome, cancellation, gate

`common/Outcome.kt`:

```kotlin
package app.thebox.core.common

/** Result type of suspend APIs in :data. Blocking :core APIs throw [SourceException] instead. */
sealed interface Outcome<out T> {
    data class Ok<out T>(val value: T) : Outcome<T>
    data class Err(val error: SourceError) : Outcome<Nothing>
}

inline fun <T, R> Outcome<T>.map(f: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Ok -> Outcome.Ok(f(value))
    is Outcome.Err -> this
}

fun <T> Outcome<T>.getOrNull(): T? = (this as? Outcome.Ok)?.value

/** Thrown by blocking :core code (parsers, EPG store). :data converts it to [Outcome.Err]. */
class SourceException(val error: SourceError, cause: Throwable? = null) : Exception(error.code, cause)

/**
 * Every failure the data layer can report. The UI (M5) maps each case to "reason + next step";
 * [code] is stable and is what refresh_log and diagnostics store.
 */
sealed interface SourceError {
    val code: String

    data class Network(val kind: NetworkKind, val detail: String? = null) : SourceError {
        override val code: String get() = "net." + kind.name.lowercase()
    }
    data class Http(val status: Int) : SourceError {
        override val code: String get() = "http.$status"
    }
    data class Auth(val reason: AuthReason) : SourceError {
        override val code: String get() = "auth." + reason.name.lowercase()
    }
    data class NotASource(val kind: NotASourceKind) : SourceError {
        override val code: String get() = "format." + kind.name.lowercase()
    }
    /** Pass 1 saw far fewer live entries than last time; nothing was applied (§3.3.5). */
    data class SuspiciousShrink(val previousLive: Int, val nowLive: Int) : SourceError {
        override val code: String get() = "shrink"
    }
    data class Parse(val detail: String) : SourceError {
        override val code: String get() = "parse"
    }
    data class DiskSpace(val neededBytes: Long, val freeBytes: Long) : SourceError {
        override val code: String get() = "disk"
    }
    data class Credentials(val reason: CredentialFailure) : SourceError {
        override val code: String get() = "cred." + reason.name.lowercase()
    }
    data class Unsupported(val detail: String) : SourceError {
        override val code: String get() = "unsupported"
    }
    data object Cancelled : SourceError {
        override val code: String get() = "cancelled"
    }
    data class Internal(val detail: String) : SourceError {
        override val code: String get() = "internal"
    }
}

enum class NetworkKind { DNS, CONNECT, TIMEOUT, TLS, RESET, OTHER }
enum class AuthReason { BAD_CREDENTIALS, EXPIRED, BANNED, DISABLED, UNKNOWN }
enum class NotASourceKind { HTML_PAGE, HLS_MANIFEST, EMPTY, BINARY, WRONG_FORMAT }
/** (reviewed 2026-10-13: UNAVAILABLE = transient Keystore/Tink failure; nothing is deleted, retry later.) */
enum class CredentialFailure { MISSING, KEY_INVALIDATED, CORRUPT, UNAVAILABLE }
```

`common/Cancel.kt`:

```kotlin
package app.thebox.core.common

import kotlin.coroutines.cancellation.CancellationException

/** Polled by blocking parsers/importers at least every 1,000 entries or programmes. */
fun interface CancelCheck {
    fun isCancelled(): Boolean

    fun throwIfCancelled() {
        if (isCancelled()) throw CancellationException("cancelled")
    }

    companion object {
        val Never = CancelCheck { false }
    }
}

/**
 * Blocks the importer between batches while a zap is in progress (architecture review B3.4).
 * M1 ships [Open]; M2's PlaybackController provides the real gate (a 3 s hold per zap).
 */
fun interface ImportGate {
    fun awaitClear()

    companion object {
        val Open = ImportGate { }
    }
}
```

#### 1.2.3 Complete utilities (lead writes them in full; they are small and everyone needs them on day one)

`common/Hash64.kt`:

```kotlin
package app.thebox.core.common

/**
 * FNV-1a 64-bit over UTF-16 code units (low byte, then high byte), with a separator byte after
 * every field so ("ab","c") != ("a","bc"). Stable across app versions: it is persisted
 * (channel.content_hash, epg_text.hash, reminder.title_hash). Never change the algorithm without a
 * migration that recomputes stored hashes.
 */
object Hash64 {
    const val OFFSET: Long = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    const val PRIME: Long = 0x100000001b3L

    fun of(s: String?): Long = str(OFFSET, s)

    fun str(h: Long, s: String?): Long {
        var x = h
        if (s == null) return (x xor 0xFEL) * PRIME
        for (i in s.indices) {
            val c = s[i].code
            x = (x xor (c and 0xFF).toLong()) * PRIME
            x = (x xor (c ushr 8).toLong()) * PRIME
        }
        return (x xor 0xFFL) * PRIME
    }

    fun long(h: Long, v: Long): Long {
        var x = h
        for (i in 0 until 8) x = (x xor ((v ushr (i * 8)) and 0xFFL)) * PRIME
        return x
    }

    fun int(h: Long, v: Int?): Long = if (v == null) str(h, null) else long(h, v.toLong())
    fun bool(h: Long, v: Boolean): Long = long(h, if (v) 1L else 0L)
    fun double(h: Long, v: Double?): Long = if (v == null) str(h, null) else long(h, v.toRawBits())

    /** Order-independent map hash (keys sorted). */
    fun map(h: Long, m: Map<String, String>): Long {
        var x = long(h, m.size.toLong())
        for (k in m.keys.sorted()) x = str(str(x, k), m[k])
        return x
    }
}
```

`common/Redact.kt`:

```kotlin
package app.thebox.core.common

import java.util.concurrent.CopyOnWriteArraySet

/** Mandatory log redaction (CLAUDE.md): user/pass/tokens/query strings. */
object Redact {
    private val secrets = CopyOnWriteArraySet<String>()

    /** Register every decrypted username/password/token so it is masked anywhere in a log line. */
    fun registerSecret(secret: String?) {
        if (secret != null && secret.length >= 3) secrets += secret
    }

    private val USERINFO = Regex("""(?i)\b([a-z][a-z0-9+.\-]*://)[^/\s@]+@""")
    private val XC_PATH = Regex("""(?i)/(live|movie|series|timeshift)/[^/\s?#]+/[^/\s?#]+/""")
    private val QUERY = Regex("""(://[^\s?#"'<>]*)\?[^\s#"'<>]*""")
    private val HEADER = Regex("""(?i)\b(authorization|cookie|x-token|token)\s*[:=]\s*[^\s,;&]+""")

    fun text(s: String): String {
        var r = s
        for (secret in secrets) if (r.contains(secret)) r = r.replace(secret, "***")
        r = USERINFO.replace(r) { it.groupValues[1] + "***@" }
        r = XC_PATH.replace(r) { "/" + it.groupValues[1] + "/***/***/" }
        r = QUERY.replace(r) { it.groupValues[1] + "?***" }
        r = HEADER.replace(r) { it.groupValues[1] + "=***" }
        return r
    }
}
```

`common/BoxLog.kt`:

```kotlin
package app.thebox.core.common

/** Logging facade for :core and :data. :app installs an android.util.Log sink (M5 adds the ring buffer). */
object BoxLog {
    fun interface Sink {
        fun log(level: Int, tag: String, message: String, error: Throwable?)
    }

    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6

    @Volatile var sink: Sink = Sink { _, _, _, _ -> }
    @Volatile var minLevel: Int = INFO

    inline fun d(tag: String, msg: () -> String) { if (minLevel <= DEBUG) emit(DEBUG, tag, msg(), null) }
    inline fun i(tag: String, msg: () -> String) { if (minLevel <= INFO) emit(INFO, tag, msg(), null) }
    fun w(tag: String, msg: String, t: Throwable? = null) = emit(WARN, tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = emit(ERROR, tag, msg, t)

    /** Every message is redacted. Never log per row (per channel/programme); log per batch or per source. */
    fun emit(level: Int, tag: String, msg: String, t: Throwable?) = sink.log(level, tag, Redact.text(msg), t)
}
```

`io/Compression.kt`:

```kotlin
package app.thebox.core.io

import app.thebox.core.common.NotASourceKind
import app.thebox.core.common.SourceError
import app.thebox.core.common.SourceException
import okio.BufferedSource
import okio.ByteString.Companion.decodeHex
import okio.buffer
import okio.source
import org.tukaani.xz.XZInputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/** Magic-byte detection, never by extension or Content-Encoding (protocols §1.6, §2.2). */
object Compression {
    enum class Kind { NONE, GZIP, XZ, ZIP }

    const val BUFFER = 64 * 1024
    private val GZIP_MAGIC = "1f8b".decodeHex()
    private val XZ_MAGIC = "fd377a585a00".decodeHex()
    private val ZIP_MAGIC = "504b0304".decodeHex()

    fun sniff(source: BufferedSource): Kind = when {
        source.rangeEquals(0, XZ_MAGIC) -> Kind.XZ
        source.rangeEquals(0, GZIP_MAGIC) -> Kind.GZIP
        source.rangeEquals(0, ZIP_MAGIC) -> Kind.ZIP
        else -> Kind.NONE
    }

    /**
     * Unwraps up to [maxLayers] layers (some hosts gzip twice). XZ memory is capped: a dictionary
     * bigger than [xzMemoryLimitKiB] fails with Unsupported instead of blowing the importer heap.
     *
     * (reviewed 2026-10-13: the xz block header, and so the MemoryLimitException, comes on the first
     * read, not in the constructor. The review probe showed a raw MemoryLimitException escaping from
     * the next sniff(). The XZ stream is therefore wrapped so the exception is mapped on every read.)
     */
    fun decompress(source: BufferedSource, xzMemoryLimitKiB: Int = 16 * 1024, maxLayers: Int = 3): BufferedSource {
        var s = source
        repeat(maxLayers) {
            s = when (sniff(s)) {
                Kind.NONE -> return s
                Kind.GZIP -> GZIPInputStream(s.inputStream(), BUFFER).source().buffer()
                Kind.XZ -> XzLimitMapping(XZInputStream(s.inputStream(), xzMemoryLimitKiB), xzMemoryLimitKiB).source().buffer()
                Kind.ZIP -> firstEntry(ZipInputStream(s.inputStream()))
            }
        }
        return s
    }

    /** Maps xz's MemoryLimitException (an IOException) to SourceException(Unsupported) wherever it surfaces. */
    private class XzLimitMapping(private val inner: java.io.InputStream, private val limitKiB: Int) : java.io.FilterInputStream(inner) {
        private fun <T> mapped(block: () -> T): T = try { block() } catch (e: org.tukaani.xz.MemoryLimitException) {
            throw SourceException(SourceError.Unsupported("xz dictionary larger than $limitKiB KiB"), e)
        }
        override fun read(): Int = mapped { inner.read() }
        override fun read(b: ByteArray, off: Int, len: Int): Int = mapped { inner.read(b, off, len) }
    }

    private fun firstEntry(zip: ZipInputStream): BufferedSource {
        while (true) {
            val e = zip.nextEntry ?: throw SourceException(SourceError.NotASource(NotASourceKind.EMPTY))
            if (!e.isDirectory && !e.name.startsWith("__MACOSX/")) return zip.source().buffer()
        }
    }
}
```

`text/NameNormalizer.kt` (shared by StableKey derivation, WP-A, and the matcher, WP-D; architecture review B3.5 requires that they share one function):

```kotlin
package app.thebox.core.text

import java.text.Normalizer
import java.util.Locale

/**
 * Two levels of channel-name normalisation:
 *  - [forKey]: identity. Strips case, diacritics, quality tokens and punctuation. Keeps bracket
 *    content ("HBO (East)" != "HBO (West)") and country prefixes.
 *  - [forMatch]: EPG matching. Additionally drops bracket groups and a leading ISO country prefix.
 * Both are pure and allocation-light; callers cache results per row, never per frame.
 */
object NameNormalizer {
    private val MARKS = Regex("""\p{M}+""")
    private val QUALITY = Regex(
        """(?<![\p{L}\p{N}])(?:uhd|fhd|qhd|hd|sd|4k|8k|hevc|h\.?26[45]|x26[45]|avc|2160p|1080[pi]|720p|576[pi]|50 ?fps|60 ?fps|hdr10\+?|hdr|hlg|backup|vip|raw)(?![\p{L}\p{N}])""",
    )
    private val BRACKETS = Regex("""\([^)]*\)|\[[^\]]*]|\{[^}]*}""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}+]+""")
    private val PREFIX = Regex("""^\s*\|?\s*([a-z]{2})\s*[:|\-–]\s*(?=\S)""")
    private val TVG_COUNTRY = Regex("""\.([a-z]{2})(?:@|$)""")
    private val ISO2: Set<String> = Locale.getISOCountries().mapTo(HashSet()) { it.lowercase(Locale.ROOT) } + "uk"

    fun fold(s: String): String =
        MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFKD), "").lowercase(Locale.ROOT)

    fun forKey(name: String): String {
        val folded = fold(name)
        val out = collapse(NON_WORD.replace(QUALITY.replace(folded, " "), " "))
        if (out.isNotEmpty()) return out
        return collapse(NON_WORD.replace(folded, " ")).ifEmpty { folded.trim() }
    }

    fun forMatch(name: String): String {
        var s = fold(name)
        PREFIX.find(s)?.let { m -> if (m.groupValues[1] in ISO2) s = s.substring(m.range.last + 1) }
        s = QUALITY.replace(BRACKETS.replace(s, " "), " ")
        return collapse(NON_WORD.replace(s, " ")).ifEmpty { forKey(name) }
    }

    /**
     * Group identity (reviewed 2026-10-13, blocker B5): case, diacritics and punctuation only. Quality
     * tokens and brackets are KEPT, because "Sports HD"/"Sports SD" and "UK HD"/"UK SD" are different
     * provider groups (forKey folded both pairs together in the review probe). Persisted via group_key: frozen after M1.
     */
    fun forGroup(name: String): String {
        val folded = fold(name)
        return collapse(NON_WORD.replace(folded, " ")).ifEmpty { folded.trim() }
    }

    fun compact(normalized: String): String = normalized.replace(" ", "")

    fun tokens(normalized: String): List<String> = if (normalized.isEmpty()) emptyList() else normalized.split(' ')

    /** Lower-case ISO-3166 alpha-2 hint ("gb" folded to "uk") from a name prefix or an iptv-org tvg-id. */
    fun countryHint(name: String?, tvgId: String?): String? {
        val fromName = name?.let { PREFIX.find(fold(it))?.groupValues?.get(1) }?.takeIf { it in ISO2 }
        val fromId = tvgId?.let { TVG_COUNTRY.find(it.lowercase(Locale.ROOT))?.groupValues?.get(1) }?.takeIf { it in ISO2 }
        return (fromName ?: fromId)?.let { if (it == "gb") "uk" else it }
    }

    private fun collapse(s: String): String = s.trim().replace(Regex(" {2,}"), " ")
}
```

`source/XtreamStreamUrl.kt` (needed by WP-A for keys and templating and by WP-C for `xc` catch-up on M3U channels):

```kotlin
package app.thebox.core.source

import java.net.URLDecoder

/** An Xtream-shaped stream URL found in an M3U (protocols §1.6, §3.3; Kodi's xc regex). */
data class XtreamStreamUrl(
    val base: String,          // scheme://host[:port]
    val kind: EntryKind,       // LIVE, MOVIE or SERIES
    val username: String,      // percent-decoded
    val password: String,
    val streamId: Long,
    val extension: String?,    // "ts", "m3u8", "mp4", … or null
    val shortForm: Boolean,    // http://h/u/p/123 without /live/
) {
    companion object {
        private val LONG = Regex("""^(https?://[^/?#]+)/(live|movie|series)/([^/?#]+)/([^/?#]+)/(\d+)(?:\.([A-Za-z0-9]{2,4}))?(?:[?#].*)?$""")
        private val SHORT = Regex("""^(https?://[^/?#]+)/([^/?#]+)/([^/?#]+)/(\d+)(?:\.(ts|m3u8))?(?:[?#].*)?$""")

        /**
         * [allowShort] must only be true when the playlist as a whole is Xtream-shaped (WP-A pass 1,
         * §3.2.4): the short form also matches ordinary `http://h/hls/chan/123` URLs.
         * (reviewed 2026-10-13, blocker B6: the LONG form also matches non-Xtream CDN paths such as
         * `http://cdn/live/bbc/hd/1.m3u8` (user "bbc", pass "hd"). A parse result is only *evidence*.
         * Callers that persist anything from it (stable keys, templating, catch-up mode) must also
         * require the playlist vote, §3.2.1/§3.2.4/§3.8.1.)
         */
        fun parse(url: String, allowShort: Boolean): XtreamStreamUrl? {
            LONG.matchEntire(url)?.let { m ->
                val kind = when (m.groupValues[2].lowercase()) {
                    "movie" -> EntryKind.MOVIE
                    "series" -> EntryKind.SERIES
                    else -> EntryKind.LIVE
                }
                return XtreamStreamUrl(
                    m.groupValues[1], kind, dec(m.groupValues[3]), dec(m.groupValues[4]),
                    m.groupValues[5].toLongOrNull() ?: return null, m.groupValues[6].ifEmpty { null }, false,
                )
            }
            if (!allowShort) return null
            val m = SHORT.matchEntire(url) ?: return null
            return XtreamStreamUrl(
                m.groupValues[1], EntryKind.LIVE, dec(m.groupValues[2]), dec(m.groupValues[3]),
                m.groupValues[4].toLongOrNull() ?: return null, m.groupValues[5].ifEmpty { null }, true,
            )
        }

        private fun dec(s: String): String = try { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") } catch (_: IllegalArgumentException) { s }
    }
}
```

#### 1.2.4 Source types (`source/SourceTypes.kt`)

```kotlin
package app.thebox.core.source

import kotlinx.serialization.Serializable

enum class PlaylistType { M3U_URL, M3U_FILE, XTREAM }

/** Persisted as ordinal in channel.kind: append-only. */
enum class EntryKind { LIVE, RADIO, MOVIE, SERIES }

/** Catch-up attributes exactly as the source gave them; resolution is CatchupResolver's job (§3.8.1). */
@Serializable
data class RawCatchup(
    val type: String? = null,             // catchup= / catchup-type=
    val source: String? = null,           // catchup-source=
    val days: Int? = null,                // catchup-days=
    val correctionHours: Double? = null,  // catchup-correction=
    val timeshiftDays: Int? = null,       // timeshift=
    val tvgRecDays: Int? = null,          // tvg-rec=
    val catchupTimeSec: Long? = null,     // catchup-time=
    val xtreamTvArchive: Boolean? = null, // Xtream tv_archive
    val xtreamArchiveDays: Int? = null,   // Xtream tv_archive_duration
) {
    companion object {
        val NONE = RawCatchup()
    }
}

/** One playlist entry from M3U (WP-A) or the Xtream API (WP-C). Never materialised as a list. */
data class ParsedChannel(
    val kind: EntryKind,
    val name: String,
    /** Absolute, pipe headers removed, credentials NOT yet templated (the differ templates). */
    val url: String,
    /** 0-based position among emitted entries (all kinds). */
    val position: Int,
    /** Primary group (first of a `;`-separated group-title, or the Xtream category name). */
    val groupTitle: String? = null,
    val extraGroups: List<String> = emptyList(),
    val xtreamCategoryId: String? = null,
    /** Junk-filtered (§3.4 rule M15), trimmed, case preserved. */
    val tvgId: String? = null,
    val tvgName: String? = null,
    val logoUrl: String? = null,
    val number: Int? = null,
    /** `tvg-shift` converted to minutes (§3.4 rule M17). */
    val epgShiftMinutes: Int? = null,
    /** Canonical header names (User-Agent, Referer, Origin, Cookie, …) merged by precedence (§3.4 rule M10). */
    val headers: Map<String, String> = emptyMap(),
    val catchup: RawCatchup = RawCatchup.NONE,
    /** Xtream API stream_id, or detected from a long-form Xtream URL. */
    val xtreamStreamId: Long? = null,
    val containerExtension: String? = null,
    val adult: Boolean = false,
    /** EXTINF duration when > 0 (VOD hint). */
    val durationSec: Long? = null,
    val kodiProps: Map<String, String> = emptyMap(),
    val vlcOpts: Map<String, String> = emptyMap(),
    /** Every other attribute, lower-case keys (protocols §1.3: never drop data). */
    val attributes: Map<String, String> = emptyMap(),
)

/**
 * `#EXTM3U` header attributes (protocols §1.2). Stored as playlist.header_json.
 * (reviewed 2026-10-13, blocker B3: a get.php header carries `url-tvg=…/xmltv.php?username=U&password=P`.
 * Before storing, WP-D replaces [epgUrls] with `Redact.text(url)` forms for display. The real URLs go
 * only into each epg_source's credential blob, and [attributes] values pass through the same templating.)
 */
@Serializable
data class M3uHeader(
    val epgUrls: List<String> = emptyList(),
    val tvgShiftHours: Double? = null,
    val catchup: RawCatchup = RawCatchup.NONE,
    val urlLogo: String? = null,
    val refreshSec: Long? = null,
    val maxConnections: Int? = null,
    val attributes: Map<String, String> = emptyMap(),
)

data class ParseWarning(val line: Long, val code: WarningCode, val detail: String? = null)

enum class WarningCode {
    LINE_TOO_LONG, EXTINF_WITHOUT_URL, URL_WITHOUT_EXTINF, BAD_ATTRIBUTE, CHARSET_FALLBACK,
    UNSUPPORTED_SCHEME, DUPLICATE_KEY, RELATIVE_URL_UNRESOLVED, XML_RECOVERED, BAD_TIME, UNKNOWN_CHANNEL,
}
```

#### 1.2.5 M3U and refresh contracts (`m3u/M3uContracts.kt`, `refresh/RefreshContracts.kt`)

```kotlin
package app.thebox.core.m3u

import app.thebox.core.common.CancelCheck
import app.thebox.core.source.M3uHeader
import app.thebox.core.source.ParsedChannel
import app.thebox.core.source.ParseWarning
import okio.BufferedSource

interface M3uPlaylistParser {
    /**
     * Streams an already-decompressed [source] into [sink]. Never buffers the whole input.
     * Throws SourceException(NotASource(HTML_PAGE | HLS_MANIFEST | EMPTY | BINARY)).
     * [M3uSink.onHeader] is called exactly once, before the first entry (empty header if absent).
     */
    fun parse(source: BufferedSource, options: M3uParseOptions, sink: M3uSink, cancel: CancelCheck = CancelCheck.Never): M3uParseSummary
}

data class M3uParseOptions(
    /** Playlist URL, for relative entry URLs. Null for local files (relative URLs are then skipped with a warning). */
    val baseUrl: String? = null,
    /** `Content-Type; charset=` from the download, if any. */
    val charsetHint: String? = null,
    /** User override (playlist.charset_override); wins over detection. */
    val forcedCharset: String? = null,
    val extgrpSticky: Boolean = false,
    val maxLineBytes: Int = 64 * 1024,
)

interface M3uSink {
    fun onHeader(header: M3uHeader) {}
    fun onEntry(entry: ParsedChannel)
    fun onWarning(warning: ParseWarning) {}
}

data class M3uParseSummary(
    val entries: Int, val live: Int, val radio: Int, val movies: Int, val series: Int,
    val skippedLines: Int, val warnings: Int, val charset: String, val hadHeader: Boolean,
)

/** Stub in commit 0 at m3u/M3uParserImpl.kt; WP-A owns the body. */
// class M3uParserImpl : M3uPlaylistParser
```

```kotlin
package app.thebox.core.refresh

import app.thebox.core.common.CancelCheck
import app.thebox.core.model.StableKey
import app.thebox.core.source.EntryKind
import app.thebox.core.source.ParsedChannel
import app.thebox.core.source.PlaylistType
import kotlinx.serialization.Serializable

/** The only identity user data may reference. */
data class ChannelIdentity(val playlistId: Long, val stableKey: StableKey)

/** XTREAM covers the "xc:", "xcm:" and "xce:" prefixes (live, movie, episode). */
enum class KeyRule(val prefix: String) { XTREAM("xc"), TVG("tvg:"), URL("url:"), NAME_GROUP("ng:") }

data class DerivedKey(val key: StableKey, val rule: KeyRule)

/**
 * [credentials] (from pass 1) lets UrlKey drop credential path segments; [xtreamShaped] enables Xtream
 * URL detection for keys (reviewed 2026-10-13, blocker B6: long AND short form, and only when the parsed pair equals [credentials]).
 */
data class KeyContext(val playlistType: PlaylistType, val xtreamShaped: Boolean, val credentials: DetectedCredentials? = null)

/** Stub in commit 0 at identity/StableKeyDeriverImpl.kt; WP-A owns the body. */
interface StableKeyDeriver {
    /** Without occurrence suffix; the differ adds `~n` for duplicates. */
    fun derive(entry: ParsedChannel, ctx: KeyContext): DerivedKey
}

/** Credentials found by pass 1 in an Xtream-shaped M3U; the sink stores them in the playlist secret. */
data class DetectedCredentials(val base: String, val username: String, val password: String)

data class KeyedChannel(
    val stableKey: StableKey,
    val rule: KeyRule,
    /** "xcat:<id>" | "g:<NameNormalizer.forGroup(group)>" | "g:" when no group. (reviewed 2026-10-13, B5: forGroup, not forKey.) */
    val groupKey: String,
    /** Stored channel.url: credentials replaced by {{u}}/{{p}} when detected (§3.2.4). */
    val urlTemplate: String,
    val contentHash: Long,
    val urlHash: Long,
    val nameHash: Long,
    val sortIndex: Int,
    val channel: ParsedChannel,
)

/** One existing row, streamed from library.db by WP-D; the differ keeps only primitives (§3.3.2). */
class ExistingChannel(
    val id: Long,
    val stableKey: String,
    val contentHash: Long,
    val urlHash: Long,
    val nameHash: Long,
    val sortIndex: Int,
    val missingSince: Long?,
    val missingCount: Int,
)

fun interface ExistingChannels {
    fun forEach(block: (ExistingChannel) -> Unit)
}

/** Re-openable producer: the differ calls [produce] twice (pass 1 validate, pass 2 merge). */
fun interface EntryProducer {
    fun produce(consumer: (ParsedChannel) -> Unit)
}

data class GroupRow(val groupKey: String, val name: String, val kind: EntryKind, val sortIndex: Int, val adult: Boolean)

class ChannelUpdate(val id: Long, val channel: KeyedChannel, val restored: Boolean)

/** Content unchanged; only position and/or missing state changed. */
class ChannelTouch(val id: Long, val sortIndex: Int, val restored: Boolean)

class MergeBatch(val inserts: List<KeyedChannel>, val updates: List<ChannelUpdate>, val touches: List<ChannelTouch>)

class MergeFinish(
    val nowMs: Long,
    /** Seen last time, not seen now: set missing_since = now, missing_count = 1. */
    val newlyMissingIds: LongArray,
    /** Already missing and still missing: missing_count += 1. */
    val stillMissingIds: LongArray,
    /** Grace expired (§3.3.4): delete the channel rows (user data stays). */
    val purgeIds: LongArray,
    /** Groups not seen in this refresh get missing_since; seen ones are cleared. */
    val groupKeysSeen: Set<String>,
    /**
     * (reviewed 2026-10-13, S7) Single-channel key changes detected by the differ: a newly-missing slot
     * and an inserted entry with the same urlHash, unique on both sides. Typical cause: the provider added
     * or fixed a tvg-id. The sink applies them with remapUserData inside the finish transaction.
     */
    val autoRemaps: List<KeyRemap> = emptyList(),
    /** Purged keys: the sink writes purged_key tombstones (§2.1.2) for the 180-day orphan rule. */
    val purgedKeys: List<String> = emptyList(),
)

data class FinishStats(val purged: Int, val favouritesKept: Int, val favouritesOrphaned: Int, val removedSample: List<String>)

/**
 * Implemented by WP-D (RoomMergeSink). Each call is one transaction except [begin]+first batch may share one.
 * (reviewed 2026-10-13, S22: blocking by design. RoomMergeSink bridges with
 * `runBlocking { db.useWriterConnection { it.immediateTransaction { … } } }` on the importer thread,
 * never on Main. useWriterConnection refreshes the InvalidationTracker, so Flows see the batch.)
 */
interface MergeSink {
    fun begin(groups: List<GroupRow>, detected: DetectedCredentials?)
    fun apply(batch: MergeBatch)
    fun finish(finish: MergeFinish): FinishStats
    fun abort(error: Throwable)
}

data class MergeContext(
    val playlistId: Long,
    val playlistType: PlaylistType,
    val refreshSeq: Long,
    val nowMs: Long,
    /** Live+radio count after the previous successful refresh (shrink guard). */
    val previousLiveCount: Int,
    val forceAcceptShrink: Boolean = false,
    val graceMs: Long = 7L * 24 * 3600 * 1000,
    val purgeAfterMissingRefreshes: Int = 2,
    val batchSize: Int = 2_000,
)

/** Stub in commit 0 at refresh/PlaylistDifferImpl.kt; WP-A owns the body. */
interface PlaylistDiffer {
    /** Throws SourceException(SuspiciousShrink | NotASource(EMPTY)) after pass 1, before touching [sink]. */
    fun merge(entries: EntryProducer, existing: ExistingChannels, sink: MergeSink, ctx: MergeContext, cancel: CancelCheck = CancelCheck.Never): RefreshReport
}

data class RemapCandidate(val stableKey: String, val name: String, val groupName: String?)
data class KeyRemap(val oldKey: String, val newKey: String, val sameGroup: Boolean)

/** Stub in commit 0 at refresh/RemapPlannerImpl.kt; WP-A owns the body. */
interface RemapPlanner {
    fun plan(missing: List<RemapCandidate>, added: List<RemapCandidate>): List<KeyRemap>
}

@Serializable
enum class RefreshOutcome { SUCCESS, NOT_MODIFIED, FAILED, CANCELLED }

/** Persisted as refresh_log.report_json; shown on S12 ("+12 new · −3 removed · 2 URLs changed · favourites kept"). */
@Serializable
data class RefreshReport(
    val playlistId: Long,
    val refreshSeq: Long,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val outcome: RefreshOutcome,
    val errorCode: String? = null,
    val entries: Int = 0,
    val live: Int = 0,
    val radio: Int = 0,
    val movies: Int = 0,
    val series: Int = 0,
    val added: Int = 0,
    val updated: Int = 0,
    val urlChanged: Int = 0,
    val renamed: Int = 0,
    val moved: Int = 0,
    val unchanged: Int = 0,
    val restored: Int = 0,
    val newlyMissing: Int = 0,
    val purged: Int = 0,
    val groupsTotal: Int = 0,
    val groupsAdded: Int = 0,
    val groupsRemoved: Int = 0,
    val duplicateKeys: Int = 0,
    /** KeyRule.name → count. */
    val keyRules: Map<String, Int> = emptyMap(),
    val favouritesKept: Int = 0,
    val favouritesOrphaned: Int = 0,
    val massRemapSuggested: Boolean = false,
    /** (reviewed 2026-10-13, S7) User data moved by MergeFinish.autoRemaps. */
    val keysMigrated: Int = 0,
    val credentialsTemplated: Boolean = false,
    val charset: String? = null,
    val addedSample: List<String> = emptyList(),
    val removedSample: List<String> = emptyList(),
    /** "<WarningCode>@<line>", at most 50. */
    val warnings: List<String> = emptyList(),
)
```

#### 1.2.6 EPG contracts (`epg/EpgContracts.kt`)

```kotlin
package app.thebox.core.epg

import app.thebox.core.common.CancelCheck
import app.thebox.core.common.ImportGate
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.io.File
import java.time.ZoneId

/** Stable reference to an EPG channel: library.db epg_source.id + XMLTV channel id. Never an epg.db row id. */
data class EpgChannelRef(val sourceId: Long, val xmltvId: String)

data class EpgChannelInfo(
    val sourceId: Long,
    val xmltvId: String,
    /** All display-names, preferred language first. */
    val displayNames: List<String>,
    val iconUrl: String?,
    /** True when programmes for this channel were whitelisted in the current generation. */
    val imported: Boolean,
    val programmeCount: Int,
)

fun interface EpgWhitelist {
    fun accepts(xmltvId: String): Boolean

    companion object {
        val ALL = EpgWhitelist { true }
        val NONE = EpgWhitelist { false }
    }
}

/**
 * Called by the importer once per source, on the importer thread, after the source's leading
 * `<channel>` elements were stored and before the first `<programme>` is accepted. [channels] is
 * backed by the building database and is valid only during the call. Implemented by WP-D (matcher).
 */
fun interface EpgWhitelistResolver {
    fun whitelistFor(sourceId: Long, channels: Sequence<EpgChannelInfo>): EpgWhitelist
}

data class EpgSourceFile(
    val sourceId: Long,
    /** Raw downloaded bytes (any compression; the store sniffs). */
    val file: File,
    /** Zone for times without an offset; null = UTC (protocols §2.6). */
    val assumeZone: ZoneId? = null,
)

data class EpgBuildRequest(
    /** Priority order: first source with data wins per channel (resolved by WP-D after the build). */
    val sources: List<EpgSourceFile>,
    val nowMs: Long,
    val pastDays: Int,
    val futureDays: Int,
    val storeDescriptions: Boolean = true,
    val maxDescriptionChars: Int = 2048,
    val preferredLanguages: List<String> = emptyList(),
    /** Test/diagnostic switch: skip the disk check and use the in-place path. */
    val forceInPlace: Boolean = false,
)

data class EpgPatchRequest(
    val sources: List<EpgSourceFile>,
    /** Channels whose programmes must be (re)imported into the current generation. */
    val refs: List<EpgChannelRef>,
    val nowMs: Long,
    val pastDays: Int,
    val futureDays: Int,
    val storeDescriptions: Boolean = true,
    val maxDescriptionChars: Int = 2048,
    val preferredLanguages: List<String> = emptyList(),
)

data class EpgProgress(val sourceId: Long, val stage: Stage, val bytesRead: Long, val bytesTotal: Long?, val programmesKept: Int) {
    enum class Stage { PARSING, REPAIRING, INDEXING, PUBLISHING }
}

/** (reviewed 2026-10-13, B2: @Serializable. WP-D stores it in refresh_log.report_json.) */
@Serializable
data class EpgSourceStat(
    val sourceId: Long,
    val channels: Int,
    val programmesSeen: Int,
    val programmesKept: Int,
    val skippedWhitelist: Int,
    val skippedWindow: Int,
    val clipped: Int,
    val split: Int,
    val badTimes: Int,
    val bytes: Long,
    val truncated: Boolean,
    val errorCode: String?,
)

@Serializable
data class EpgImportReport(
    val mode: Mode,
    val generation: Long,
    val sources: List<EpgSourceStat>,
    val programmes: Int,
    val texts: Int,
    val dbBytes: Long,
    val durationMs: Long,
    val fts: String,
    /** Set when a source failed and the build was abandoned (the old generation stays current). */
    val failedSourceId: Long? = null,
) {
    enum class Mode { REBUILD, IN_PLACE, PATCH }
}

data class EpgGenerationInfo(
    val generation: Long,
    val patchSeq: Int,
    val builtAtMs: Long,
    val programmes: Int,
    val channels: Int,
    val dbBytes: Long,
    val windowFromMin: Int,
    val windowToMin: Int,
    val fts: String,
)

fun interface FreeSpace {
    fun bytesFree(dir: File): Long
}

/** Stub in commit 0 at epg/store/EpgStoreImpl.kt; WP-B owns the body. */
interface EpgStore {
    /** Current generation; emits on publish and after every patch (patchSeq++). */
    val info: StateFlow<EpgGenerationInfo?>

    /** Ref-counted reader on the current generation, or null if none. Caller must close(). */
    fun openReader(): EpgReader?

    /** Full rebuild into a new generation (or in-place on low disk). Blocking; single writer (WP-D serialises). */
    fun build(
        request: EpgBuildRequest,
        whitelist: EpgWhitelistResolver,
        gate: ImportGate = ImportGate.Open,
        cancel: CancelCheck = CancelCheck.Never,
        progress: (EpgProgress) -> Unit = {},
    ): EpgImportReport

    /** Targeted import into the current generation (new mappings). Blocking. */
    fun patch(
        request: EpgPatchRequest,
        gate: ImportGate = ImportGate.Open,
        cancel: CancelCheck = CancelCheck.Never,
        progress: (EpgProgress) -> Unit = {},
    ): EpgImportReport

    /** Startup recovery (§2.5.4). Idempotent. Call once, off the main thread, before openReader(). */
    fun recover()

    /** Deletes every generation (Settings → "Rebuild guide data"). */
    fun clear()
}

/** Bit flags stored in programme.flags. Append-only. */
object ProgrammeFlags {
    const val NEW = 1
    const val PREMIERE = 1 shl 1
    const val LAST_CHANCE = 1 shl 2
    const val PREVIOUSLY_SHOWN = 1 shl 3
    const val LIVE = 1 shl 4
    /** This row is a later segment of a programme split at 6 h. */
    const val CONTINUATION = 1 shl 5
    /** This row is followed by a continuation segment. */
    const val CONTINUED = 1 shl 6
    const val HAS_CATCHUP_ID = 1 shl 7
    const val SUBTITLES = 1 shl 8
    const val HD = 1 shl 9
    const val UHD = 1 shl 10
    /** End was clipped to the next programme's start. */
    const val CLIPPED = 1 shl 11
    /** Stop was missing and inferred. */
    const val STOP_INFERRED = 1 shl 12
}

/** Reusable struct-of-arrays buffer for the guide window (zero per-row allocation besides cache-miss titles). */
class EpgWindow(initialCapacity: Int = 1024) {
    var size: Int = 0
    /** Index into the `channels` array passed to [EpgReader.window]. Rows are sorted by (channelIndex, startMin). */
    var channelIndex = IntArray(initialCapacity)
    /** Display (shifted) epoch minutes. */
    var startMin = IntArray(initialCapacity)
    var endMin = IntArray(initialCapacity)
    /** Raw (unshifted) start: the catch-up and reminder key. */
    var rawStartMin = IntArray(initialCapacity)
    var textId = IntArray(initialCapacity)
    var flags = IntArray(initialCapacity)
    var titles = arrayOfNulls<String>(initialCapacity)

    fun clear() { size = 0 }

    fun ensureCapacity(n: Int) {
        if (n <= channelIndex.size) return
        val c = maxOf(n, channelIndex.size * 2)
        channelIndex = channelIndex.copyOf(c); startMin = startMin.copyOf(c); endMin = endMin.copyOf(c)
        rawStartMin = rawStartMin.copyOf(c); textId = textId.copyOf(c); flags = flags.copyOf(c); titles = titles.copyOf(c)
    }
}

data class ProgrammeDetail(
    /** Generation-local id: valid only for the reader that produced it. Never persist. */
    val channel: Int,
    val ref: EpgChannelRef,
    val rawStartMin: Int,
    val rawEndMin: Int,
    val startMin: Int,
    val endMin: Int,
    val title: String,
    val subtitle: String?,
    val description: String?,
    val categories: List<String>,
    val episodeLabel: String?,
    val season: Int?,
    val episode: Int?,
    val iconUrl: String?,
    val rating: String?,
    val starRating: String?,
    val year: Int?,
    val flags: Int,
    val catchupId: String?,
    /** Hash64.of(title): with (stableKey, rawStartMin) the reminder/recording key. */
    val titleHash: Long,
)

data class EpgSearchHit(val channel: Int, val ref: EpgChannelRef, val rawStartMin: Int, val rawEndMin: Int, val title: String, val subtitle: String?)

/**
 * Read API on one generation. Not thread-safe per call site: the guide uses one reader from one
 * thread; other features open their own reader. Every Int "channel" id is generation-local.
 */
interface EpgReader : AutoCloseable {
    val info: EpgGenerationInfo

    /** epg_channel ids for [refs] (-1 = not in this generation). Case-insensitive fallback on xmltv_id. */
    fun resolve(refs: List<EpgChannelRef>, caseInsensitive: Boolean = true): IntArray

    /**
     * Fills [out] with programmes overlapping [fromMin, toMin) in DISPLAY minutes for each channel,
     * where display = raw + shiftMin[i]. [channels] may contain -1 (skipped).
     */
    fun window(channels: IntArray, shiftMin: IntArray, fromMin: Int, toMin: Int, out: EpgWindow)

    fun detail(channel: Int, rawStartMin: Int, shiftMin: Int): ProgrammeDetail?

    /** Programme airing at display minute [atMin], or null (gap). */
    fun at(channel: Int, atMin: Int, shiftMin: Int): ProgrammeDetail?

    /** FTS over title+subtitle; returns airings ending after [nowMin] (raw), best match first. */
    fun search(query: String, nowMin: Int, limit: Int = 50): List<EpgSearchHit>

    /**
     * All channels (optionally of one source), pushed to [block] on the calling thread.
     * (reviewed 2026-10-13, S13: callback instead of a lazy Sequence. An abandoned Sequence would keep
     * the statement and the connection lock held. The statement is reset in `finally`.)
     */
    fun forEachChannel(sourceId: Long? = null, block: (EpgChannelInfo) -> Unit)

    /** Picker search over EPG channel ids and display names (S13). */
    fun findChannels(text: String, limit: Int = 50): List<EpgChannelInfo>

    fun refOf(channel: Int): EpgChannelRef?
}
```

#### 1.2.7 Matcher contracts (`match/MatchContracts.kt`)

```kotlin
package app.thebox.core.match

import app.thebox.core.epg.EpgChannelRef
import app.thebox.core.refresh.ChannelIdentity

data class MatchSubject(
    val identity: ChannelIdentity,
    val name: String,
    val tvgId: String?,
    val tvgName: String?,
    val countryHint: String?,
)

/** Persisted by name in epg_match.method: append-only. */
enum class MatchMethod { OVERRIDE, ID_EXACT, ID_CASE_INSENSITIVE, ID_BASE, TVG_NAME, DISPLAY_NAME, FUZZY }

enum class MatchStatus { AUTO, SUGGESTED }

data class MatchCandidate(val ref: EpgChannelRef, val displayName: String, val score: Float, val method: MatchMethod)

data class MatchResult(
    val subject: ChannelIdentity,
    val sourceId: Long,
    /** The applied match (status AUTO), or the best suggestion (status SUGGESTED), or null. */
    val best: MatchCandidate?,
    val status: MatchStatus?,
    /** Up to MatchSettings.maxSuggestions alternatives, best first (excluding [best]). */
    val suggestions: List<MatchCandidate>,
)

/** Product §4.3 "Automatic matching". */
enum class MatchLevel { EXACT_ONLY, EXACT_AND_NAMES, SUGGEST_FUZZY, AUTO_FUZZY }

data class MatchSettings(
    val level: MatchLevel = MatchLevel.SUGGEST_FUZZY,
    val caseInsensitiveIds: Boolean = true,
    val autoThreshold: Float = 0.92f,
    val suggestThreshold: Float = 0.75f,
    val ambiguityMargin: Float = 0.02f,
    val maxSuggestions: Int = 3,
)
```

#### 1.2.8 Xtream and catch-up contracts (`xtream/XtreamContracts.kt`, `catchup/CatchupContracts.kt`)

```kotlin
package app.thebox.core.xtream

import app.thebox.core.source.ParsedChannel
import kotlinx.serialization.Serializable
import java.io.InputStream

@Serializable
enum class AccountStatus { ACTIVE, BANNED, DISABLED, EXPIRED, UNKNOWN }

@Serializable
data class XtreamServerInfo(
    val url: String? = null,
    val port: Int? = null,
    val httpsPort: Int? = null,
    val protocol: String? = null,
    /** Raw server_info.timezone (may be non-IANA, e.g. "UTC+3"). */
    val timezone: String? = null,
    val timestampNow: Long? = null,
    /** Raw "YYYY-MM-DD HH:MM:SS", panel-local. */
    val timeNow: String? = null,
    val xui: Boolean = false,
    val version: String? = null,
)

/** Account card (S12). Contains no credentials. Stored as xtream_account.info_json. */
@Serializable
data class XtreamAccountInfo(
    val auth: Boolean,
    val status: AccountStatus,
    val statusRaw: String? = null,
    /** null = unlimited (`exp_date` null, "0" or ""). */
    val expiresAtSec: Long? = null,
    val isTrial: Boolean = false,
    val activeConnections: Int? = null,
    val maxConnections: Int? = null,
    val allowedOutputFormats: List<String> = emptyList(),
    val createdAtSec: Long? = null,
    val message: String? = null,
    val server: XtreamServerInfo = XtreamServerInfo(),
    /** Device epoch ms when the response arrived (clock-skew reference). */
    val measuredAtDeviceMs: Long,
)

data class XtreamCategory(val id: String, val name: String, val parentId: String?)

/** Everything the catch-up builder needs to address an Xtream stream (credentials already decrypted). */
data class XtreamStreamRef(val base: String, val username: String, val password: String, val streamId: Long, val extension: String)

data class XtreamEpgListing(
    val title: String,
    val description: String?,
    val startUtcSec: Long,
    val stopUtcSec: Long,
    val nowPlaying: Boolean,
    val hasArchive: Boolean?,
)

/** Stub in commit 0 at xtream/XtreamCatalogDecoderImpl.kt; WP-C owns the body. */
interface XtreamCatalogDecoder {
    /** Throws SourceException(Auth | NotASource). */
    fun account(input: InputStream, measuredAtDeviceMs: Long): XtreamAccountInfo
    fun categories(input: InputStream): List<XtreamCategory>
    /**
     * Lazily decodes get_live_streams into ParsedChannel (kind LIVE or RADIO); url is the template
     * "{{base}}/live/{{u}}/{{p}}/<id>.{{ext}}". The sequence must be consumed while [input] is open.
     */
    fun liveChannels(input: InputStream, categories: List<XtreamCategory>): Sequence<ParsedChannel>
    fun epgListings(input: InputStream, timeBase: app.thebox.core.model.TimeBase): List<XtreamEpgListing>
}

/** (reviewed 2026-10-13, B2) Parsed "upgrade to Xtream" input (output of XtreamLoginUrl.parse). */
data class XtreamLoginInput(val base: String, val username: String, val password: String, val output: String?)
```

(reviewed 2026-10-13, B2) Two WP-C classes that WP-D calls are **stub files in commit 0** with these exact public signatures. The bodies are `TODO("WP-C")`, and C owns the files afterwards:

```kotlin
// core/.../xtream/XtreamLoginUrl.kt: "paste a get.php URL → Xtream", and the login on Xtream-shaped M3U playlists (§3.10)
object XtreamLoginUrl {
    /** get.php / player_api.php / xmltv.php / stream URL → login input, or null. */
    fun parse(url: String): XtreamLoginInput?
}

// core/.../xtream/XtreamUrls.kt: §3.7.5 encoding rules
class XtreamUrls(base: String, username: String, password: String) {
    fun playerApi(action: String?, params: Map<String, String> = emptyMap()): String
    fun getPhp(type: String = "m3u_plus", output: String = "ts"): String
    fun xmltv(): String
    fun live(streamId: Long, ext: String): String
    fun movie(streamId: Long, ext: String): String
    fun episode(episodeId: Long, ext: String): String
}
```

```kotlin
package app.thebox.core.catchup

import app.thebox.core.model.Catchup
import app.thebox.core.model.CatchupMode
import app.thebox.core.model.TimeBase
import app.thebox.core.source.PlaylistType
import app.thebox.core.source.RawCatchup
import app.thebox.core.xtream.XtreamStreamRef
import java.time.Instant

sealed interface CatchupModeSetting {
    data object Auto : CatchupModeSetting
    data object Off : CatchupModeSetting
    data class Forced(val mode: CatchupMode) : CatchupModeSetting
}

/**
 * Zone for `{Y}{m}{d}{H}{M}{S}`, `{utc:FMT}` and the Xtream timeshift segment (product §4.2).
 * (reviewed 2026-10-13, blocker B1) Only an explicit choice (Utc/Device/Server/Fixed) sets TimeBase.tokenZone,
 * and only then does it override the panel zone for `xc`.
 */
sealed interface TokenZoneSetting {
    /** `xc`: always the panel zone (any playlist type). Template tokens: Xtream → panel zone, M3U → UTC (TimeBase.templateZone). */
    data object Auto : TokenZoneSetting
    data object Utc : TokenZoneSetting
    data object Device : TokenZoneSetting
    data object Server : TokenZoneSetting
    data class Fixed(val zoneId: String) : TokenZoneSetting
}

data class PlaylistCatchupDefaults(
    val type: PlaylistType,
    val modeSetting: CatchupModeSetting = CatchupModeSetting.Auto,
    val daysOverride: Int? = null,
    /** M3U header-level catchup attributes (empty for Xtream). */
    val header: RawCatchup = RawCatchup.NONE,
    val defaultDays: Int = 3,
    /** (reviewed 2026-10-13, B6) Pass-1 vote result for M3U playlists; gates Xtream-URL detection in §3.8.1 (f). */
    val xtreamShaped: Boolean = false,
)

/** (reviewed 2026-10-13, B2) Per-playlist inputs of TimeBaseFactory (playlist columns, §2.1.2). */
data class TimeBaseSettings(
    val tokenZone: TokenZoneSetting = TokenZoneSetting.Auto,
    /** playlist.catchup_correction_h (user value). */
    val correctionHours: Double? = null,
    /** M3U header `catchup-correction`. */
    val headerCorrectionHours: Double? = null,
    val paddingBeforeSec: Int = 60,
    val paddingAfterSec: Int = 300,
)

/** Stub in commit 0 at catchup/TimeBaseFactoryImpl.kt; WP-C owns the body (§3.8.3). WP-D calls it. */
interface TimeBaseFactory {
    /** Xtream playlists, and Xtream-shaped M3U playlists after a login with the detected credentials. */
    fun fromAccount(type: PlaylistType, info: app.thebox.core.xtream.XtreamAccountInfo, settings: TimeBaseSettings): TimeBase
    /** M3U playlists with no account info (no panel zone known). */
    fun forM3u(settings: TimeBaseSettings): TimeBase
}

/** Stub in commit 0 at catchup/CatchupResolverImpl.kt; WP-C owns the body. */
interface CatchupResolver {
    /** Mode chain §3.8.1 and archive-days chain §3.8.2. [urlTemplate] is the stored channel.url. */
    fun resolve(raw: RawCatchup, urlTemplate: String, xtreamStreamId: Long?, defaults: PlaylistCatchupDefaults): Catchup
}

data class CatchupTarget(
    /** Live URL with credentials expanded (M2 StreamUrlResolver output, before redirects). */
    val liveUrl: String,
    val headers: Map<String, String>,
    val catchup: Catchup,
    /** Present for Xtream API channels and for Xtream-shaped M3U URLs. */
    val xtream: XtreamStreamRef?,
)

data class ArchiveWindow(
    /** Raw (unshifted) programme times. */
    val programmeStart: Instant,
    /** null = open-ended ("watch from here", pause-live resume). */
    val programmeStop: Instant?,
    val catchupId: String? = null,
    /** Device now; the builder adds TimeBase.clockSkew. */
    val deviceNow: Instant,
)

data class ArchiveRequest(
    val url: String,
    val headers: Map<String, String>,
    val mode: CatchupMode,
    /** One of CatchupVariants; persisted as playlist.catchup_probe_winner. */
    val variant: String,
    /** Wall-clock UTC the first media byte corresponds to (ArchivePlayback timeline origin). */
    val requestStart: Instant,
    val requestEnd: Instant?,
    /** Epoch seconds the player can seek natively inside this request; null = re-request to seek. */
    val nativeSeekRange: LongRange?,
)

sealed interface CatchupBuild {
    data class Ok(val request: ArchiveRequest) : CatchupBuild
    data class Unsupported(val reason: String) : CatchupBuild
}

object CatchupVariants {
    const val XC_PATH = "xc-path"
    const val XC_PHP = "xc-php"
    const val XC_PATH_SECONDS = "xc-path-sec"
    const val FLUSSONIC_RANGE = "flussonic-range"
    const val FLUSSONIC_ABS = "flussonic-abs"
    const val FLUSSONIC_KODI = "flussonic-kodi"
    const val SHIFT = "shift"
    const val APPEND = "append"
    const val DEFAULT = "default"
    const val VOD = "vod"
}

/** Stub in commit 0 at catchup/CatchupUrlBuilderImpl.kt; WP-C owns the body. */
interface CatchupUrlBuilder {
    fun build(target: CatchupTarget, timeBase: TimeBase, window: ArchiveWindow, variant: String? = null): CatchupBuild

    /** Probe order for this target (first = default). M4 tries them in order and stores the winner. */
    fun variants(target: CatchupTarget): List<String>
}
```

#### 1.2.9 Data-layer contracts (`data/src/main/kotlin/app/thebox/data/contracts/DataContracts.kt`)

```kotlin
package app.thebox.data.contracts

import app.thebox.core.common.Outcome
import app.thebox.core.model.TimeBase
import app.thebox.core.xtream.XtreamAccountInfo
import app.thebox.core.xtream.XtreamEpgListing
import kotlinx.serialization.Serializable
import java.io.File

data class XtreamConnection(
    val playlistId: Long,
    /** Login host first, then backup servers (protocols §3.4). */
    val baseUrls: List<String>,
    val username: String,
    val password: String,
    val userAgent: String?,
    val headers: Map<String, String> = emptyMap(),
    val preferredBaseIndex: Int = 0,
)

enum class XtreamAction(val wire: String?) {
    LOGIN(null),
    LIVE_CATEGORIES("get_live_categories"), LIVE_STREAMS("get_live_streams"),
    VOD_CATEGORIES("get_vod_categories"), VOD_STREAMS("get_vod_streams"), VOD_INFO("get_vod_info"),
    SERIES_CATEGORIES("get_series_categories"), SERIES("get_series"), SERIES_INFO("get_series_info"),
    SHORT_EPG("get_short_epg"), SIMPLE_DATA_TABLE("get_simple_data_table"),
}

data class XtreamLogin(val account: XtreamAccountInfo, val baseIndexUsed: Int)

/** Stub in commit 0 at data/xtream/XtreamClient.kt (@Inject constructor); WP-C owns the body. */
interface XtreamApi {
    suspend fun login(conn: XtreamConnection): Outcome<XtreamLogin>
    /** Streams the response body for [action] to [dest] (never into a String). */
    suspend fun download(conn: XtreamConnection, action: XtreamAction, params: Map<String, String>, dest: File): Outcome<FetchResult>
    /** (reviewed 2026-10-13, B2) [timeBase] parses panel-local `start`/`end` when `*_timestamp` is absent (§3.7.4). */
    suspend fun shortEpg(conn: XtreamConnection, streamId: Long, timeBase: TimeBase, limit: Int = 4): Outcome<List<XtreamEpgListing>>
    suspend fun simpleDataTable(conn: XtreamConnection, streamId: Long, timeBase: TimeBase): Outcome<List<XtreamEpgListing>>
}

enum class TimeoutProfile { API, LARGE_FILE }

data class FetchRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val etag: String? = null,
    val lastModified: String? = null,
    val profile: TimeoutProfile = TimeoutProfile.LARGE_FILE,
    val maxBytes: Long = 2L * 1024 * 1024 * 1024,
)

data class FetchResult(
    val notModified: Boolean,
    val bytes: Long,
    val etag: String?,
    val lastModified: String?,
    val contentType: String?,
    val charset: String?,
)

/** WP-D (data/net/OkHttpSourceFetcher.kt). Writes to dest.part, renames to dest on success. */
interface SourceFetcher {
    suspend fun fetch(request: FetchRequest, dest: File): Outcome<FetchResult>
}

/** Everything secret about one playlist or EPG source. Encrypted as one Tink AEAD blob. */
@Serializable
data class PlaylistSecret(
    /** M3U/XMLTV URL, or Xtream base URLs (login host first, then backups). */
    val urls: List<String>,
    val username: String? = null,
    val password: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

/** WP-D (data/security/TinkCredentialStore.kt). */
interface CredentialStore {
    fun newRef(): String
    suspend fun put(ref: String, secret: PlaylistSecret): Outcome<Unit>
    /**
     * Err(Credentials(KEY_INVALIDATED)) after a Keystore reset: UI asks to re-enter the password.
     * Err(Credentials(UNAVAILABLE)) on a transient Keystore failure: nothing deleted, retry later (reviewed 2026-10-13, S16).
     */
    suspend fun get(ref: String): Outcome<PlaylistSecret>
    suspend fun delete(ref: String)
}
```

Stubs in commit 0 (bodies are `TODO("WP-x")`):

| Stub file | Implements | Owner |
|---|---|---|
| `core/.../m3u/M3uParserImpl.kt` | `M3uPlaylistParser` | A |
| `core/.../identity/StableKeyDeriverImpl.kt` | `StableKeyDeriver` | A |
| `core/.../refresh/PlaylistDifferImpl.kt` (`class PlaylistDifferImpl(deriver: StableKeyDeriver)`) | `PlaylistDiffer` | A |
| `core/.../refresh/RemapPlannerImpl.kt` | `RemapPlanner` | A |
| `core/.../epg/store/EpgStoreImpl.kt` (`class EpgStoreImpl(dir: File, driver: SQLiteDriver, freeSpace: FreeSpace, xmlParser: () -> XmlPullParser, clock: () -> Long, forceFts4: Boolean = false)`; `forceFts4` is a test hook). When `driver is BundledSQLiteDriver`, readers open with `SQLITE_OPEN_READONLY`; with any other driver (Robolectric's `AndroidSQLiteDriver`) they rely on `PRAGMA query_only=1` | `EpgStore` | B |
| `core/.../xtream/XtreamCatalogDecoderImpl.kt` | `XtreamCatalogDecoder` | C |
| `core/.../catchup/CatchupResolverImpl.kt` | `CatchupResolver` | C |
| `core/.../catchup/CatchupUrlBuilderImpl.kt` | `CatchupUrlBuilder` | C |
| `core/.../catchup/TimeBaseFactoryImpl.kt` (reviewed 2026-10-13, B2) | `TimeBaseFactory` | C |
| `core/.../xtream/XtreamUrls.kt`, `core/.../xtream/XtreamLoginUrl.kt` (reviewed 2026-10-13, B2; signatures above) | — | C |
| `core/src/testFixtures/kotlin/app/thebox/core/epg/testutil/XmltvGenerator.kt`, `EpgTestDb.kt` (reviewed 2026-10-13, B2) | test fixtures | B |
| `data/.../xtream/XtreamClient.kt` (`class XtreamClient @Inject constructor(@ApiClient http: OkHttpClient, decoder: XtreamCatalogDecoder, @IoDispatcher io: CoroutineDispatcher)`) | `XtreamApi` | C |

The qualifiers `@ApiClient`, `@DownloadClient`, `@IoDispatcher` and `@DefaultDispatcher` go in `data/.../di/Qualifiers.kt`. That file is part of commit 0 because C's constructor references it. WP-D owns it afterwards.

### 1.3 WP-A: M3U parser, StableKey, diff-merge, refresh report

Module `:core`. Consumes: `Compression`, `NameNormalizer`, `Hash64`, `XtreamStreamUrl`, `source/*`, `refresh/RefreshContracts`. Exposes: `M3uPlaylistParser`, `StableKeyDeriver`, `PlaylistDiffer`, `RemapPlanner` (all in §1.2), consumed by WP-D.

| File (`core/src/main/kotlin/app/thebox/core/…`) | Contents |
|---|---|
| `m3u/M3uParserImpl.kt` | State machine (§3.4): header → pending entry (EXTINF + aux lines) → URL → emit |
| `m3u/LineReader.kt` | `class LineReader(source: BufferedSource, maxLineBytes: Int)`: byte-level lines split on LF, CR or CRLF, over-long lines skipped, BOM handling, yields `ByteString`. Also `Utf16LineReader` for UTF-16 BOMs |
| `m3u/CharsetDecoderChain.kt` | Strict UTF-8 per line, with the fallback charset (chosen at the first malformed line) used only for lines that fail (§4.3; reviewed 2026-10-13, S15) |
| `m3u/ExtinfLexer.kt` | Quote-aware attribute lexer: `"…"`, `'…'`, unquoted, empty, case-insensitive keys, display name after the first comma outside quotes |
| `m3u/HeaderAttrs.kt` | `#EXTM3U` attrs → `M3uHeader` (aliases, comma-split EPG URLs) |
| `m3u/AuxDirectives.kt` | `#EXTVLCOPT`, `#KODIPROP`, `#EXTHTTP`, `#EXTGRP` → pending maps |
| `m3u/PipeHeaders.kt` | `fun split(url: String): Pair<String, Map<String,String>>` (§3.4 M9) |
| `m3u/HeaderPrecedence.kt` | Canonical header names + merge order |
| `m3u/EntryClassifier.kt` | `EntryKind` from tvg-type, path, extension, radio, group heuristics |
| `m3u/UrlResolution.kt` | Relative URL → absolute against `baseUrl`; `url-logo` prefixing |
| `identity/StableKeyDeriverImpl.kt` | Priority chain (§3.2) |
| `identity/UrlKey.kt` | Host- and credential-agnostic path key |
| `identity/ContentHasher.kt` | `contentHash`, `urlHash`, `nameHash` (§3.3.1) |
| `identity/GroupKeys.kt` | `groupKey(channel)` |
| `refresh/PlaylistDifferImpl.kt` | Two-pass engine, batch emitter, finish computation (§3.3) |
| `refresh/ExistingIndex.kt` | Primitive index over existing rows: `MutableLongIntMap` + parallel arrays |
| `refresh/CredentialTemplater.kt` | Pass-1 vote and `{{u}}`/`{{p}}` substitution (§3.2.4) |
| `refresh/RefreshReportBuilder.kt` | Counters, samples, warnings cap |
| `refresh/RemapPlannerImpl.kt` | Mass-remap proposal by `forKey(name)` (§3.3.6) |

Tests: `core/src/test/kotlin/app/thebox/core/{m3u,identity,refresh}/…`, fixtures in §5.1.

### 1.4 WP-B: XMLTV parser, epg.db, generation swap, window and search

Module `:core`. Consumes: `Compression`, `Hash64`, `NameNormalizer.forMatch` (for `epg_channel_name.norm`), `epg/EpgContracts`, `CancelCheck`, `ImportGate`. Exposes: `EpgStore`/`EpgReader` (WP-D wires it, M3 guide and M4 consume it).

| File (`core/src/main/kotlin/app/thebox/core/…`) | Contents |
|---|---|
| `xmltv/XmltvParser.kt` | `class XmltvParser(factory: () -> XmlPullParser)` with `fun parse(input: InputStream, handler: XmltvHandler, cancel: CancelCheck)`. Sets **every** parser feature itself (§3.5.7), so the Android and JVM factories behave the same (reviewed 2026-10-13, S4) |
| `xmltv/XmltvHandler.kt` | `interface XmltvHandler { fun onChannel(c: XmltvChannel); fun acceptProgramme(channelId: String, startMin: Int, stopMin: Int): Boolean; fun onProgramme(p: XmltvProgramme) }`. Skip-before-parse hook (§3.5.3) |
| `xmltv/XmltvChannel.kt`, `xmltv/XmltvProgramme.kt` | Channel is immutable. Programme is a **reusable mutable** record, valid only inside `onProgramme` |
| `xmltv/XmltvTime.kt` | Allocation-free time parser to epoch minutes (§3.5.1) |
| `xmltv/EpisodeNum.kt` | `xmltv_ns`, `onscreen`, `dd_progid`, `SxxExx` (§3.5.2) |
| `xmltv/LangPicker.kt` | Picks the element per preferred language, else the first |
| `epg/store/EpgSchema.kt` | DDL strings, `SCHEMA_VERSION`, pragmas (§2.3) |
| `epg/store/Sql.kt` | Small helpers over `SQLiteConnection` (`exec`, `prepare`, `inTransaction`, bind helpers) |
| `epg/store/FtsSupport.kt` | FTS5 vs FTS4 detection and DDL |
| `epg/store/GenerationFiles.kt` | Naming, pointer read/write (temp + rename + fsync), orphan cleanup (§2.5) |
| `epg/store/GenerationHandle.kt` | Ref-counted connections for one generation |
| `epg/store/EpgStoreImpl.kt` | `EpgStore`: `build`/`patch`/`recover`/`clear`, disk precheck, mode choice |
| `epg/store/ImportSession.kt` | Per-source `XmltvHandler` implementation: channel rows, whitelist call, text dedup, split, insert batches |
| `epg/store/TextDedup.kt` | `MutableLongIntMap` hash → text id; prepared insert |
| `epg/store/RepairPass.kt` | Per-channel clip/infer/stats (§3.5.5) |
| `epg/store/EpgReaderImpl.kt` | `window`, `detail`, `at`, `search`, `forEachChannel`, `findChannels`, `resolve`, title LRU |

Tests: `core/src/test/kotlin/app/thebox/core/{xmltv,epg/store}/…`, plus the `perf` tag (§5.5). Test utilities `XmltvGenerator.kt` and `EpgTestDb.kt` live in `core/src/testFixtures/kotlin/app/thebox/core/epg/testutil/` (owned by B; WP-D uses them read-only through `testImplementation(testFixtures(project(":core")))`). (reviewed 2026-10-13, B2: another module cannot see `src/test` classes, so these moved to test fixtures.)

### 1.5 WP-C: Xtream client, URL builders, account info, TimeBase, CatchupUrlBuilder

Modules `:core` and `:data`. Consumes: `XtreamStreamUrl`, `source/*`, `model/TimeBase`, `model/CatchupMode`, `catchup/CatchupContracts`, `xtream/XtreamContracts`, `data/contracts`. Exposes: `XtreamCatalogDecoder`, `CatchupResolver`, `CatchupUrlBuilder`, `XtreamApi`, `XtreamUrls`, `XtreamLoginUrl`, `TimeBaseFactory` (WP-D consumes them; M2 and M4 consume the builder).

| File | Contents |
|---|---|
| `core/.../xtream/LenientJson.kt` | Lenient accessors over `JsonObject` (§3.7) |
| `core/.../xtream/XtreamDtos.kt` | Plain Kotlin classes: `XtreamLiveStream`, `XtreamVodStream`, `XtreamSeries`, `XtreamSeriesInfo` (+`Season`, `Episode`), `XtreamVodInfo`. No `@Serializable` |
| `core/.../xtream/XtreamDecoders.kt` | Tree-per-item decoders for every action (live, vod, series, series info, vod info, short EPG, simple data table, categories, account) |
| `core/.../xtream/XtreamCatalogDecoderImpl.kt` | Contract implementation, mapping to `ParsedChannel` (§3.7.4) |
| `core/.../xtream/Base64OrPlain.kt` | Per-response base64 detection (§3.7.3) |
| `core/.../xtream/XtreamUrls.kt` | `class XtreamUrls(base, user, pass)`: `playerApi(action, params)`, `getPhp(type, output)`, `xmltv()`, `live(id, ext)`, `movie(id, ext)`, `episode(id, ext)`, `timeshiftPath(...)`, `timeshiftPhp(...)`. Percent-encoding rules (§3.7.5) |
| `core/.../xtream/XtreamLoginUrl.kt` | Parses a pasted `get.php`/`player_api.php`/stream URL into `(base, user, pass, output)` for "upgrade to Xtream" |
| `core/.../catchup/CatchupResolverImpl.kt` | Mode chain and archive-days chain (§3.8.1–3.8.2) |
| `core/.../catchup/TimeBaseFactoryImpl.kt` | Implements the `TimeBaseFactory` contract (§1.2.8): `fromAccount(type, info, settings)`, where the skew reference is `info.measuredAtDeviceMs`, and `forM3u(settings)`; zone validation; skew; `templateZone` (§3.8.3). (reviewed 2026-10-13, B2: it is now a contract, so WP-D can compile against it on day one) |
| `core/.../catchup/TokenFormatter.kt` | `Y m d H M S` formatting in a zone, ISO output |
| `core/.../catchup/TemplateExpander.kt` | Placeholder table (§3.8.4) |
| `core/.../catchup/FlussonicUrl.kt` | Live URL parsing and the derived URLs (§3.8.6) |
| `core/.../catchup/CatchupUrlBuilderImpl.kt` | Per-mode builders and variant lists |
| `data/.../xtream/XtreamClient.kt` | OkHttp client: base rotation, semaphore(3), 429 backoff, auth detection, streaming download (§3.7.6) |
| `data/.../xtream/XtreamHttpErrors.kt` | HTTP/IO → `SourceError` |

Tests: `core/src/test/kotlin/app/thebox/core/{xtream,catchup}/…` (JUnit 5), `data/src/test/kotlin/app/thebox/data/xtream/…` (JUnit 4, MockWebServer, no Robolectric).

### 1.6 WP-D: library.db, repositories, matcher, workers, Hilt, credentials

Modules `:core` (matcher), `:data`, `:app`. Consumes everything above. Exposes the repositories (M3–M5 consume them) and `EpgStore` as a Hilt singleton.

| File | Contents |
|---|---|
| `core/.../match/EpgMatcherImpl.kt` | `class EpgMatcher(settings: MatchSettings)`: `prepare(subjects)`, `matchSource(prepared, sourceId, channels, overrides)`, `suggest(subject, channels, limit)` (§3.6) |
| `core/.../match/Similarity.kt` | Jaro-Winkler, token-set ratio, digit guard |
| `core/.../match/TokenIndex.kt` | Inverted token index over library subjects with a df cap |
| `data/.../db/LibraryDatabase.kt` | `@Database` (version 1, exportSchema), builder (BundledSQLiteDriver, WAL, IO context) |
| `data/.../db/entity/*.kt` | One file per table in §2.1 |
| `data/.../db/dao/*.kt` | `PlaylistDao`, `ChannelDao`, `GroupDao`, `FavouriteDao`, `ChannelPrefsDao`, `EpgSourceDao`, `EpgMappingDao`, `EpgMatchDao`, `ReminderDao`, `RefreshLogDao`, `XtreamAccountDao`, `CredentialDao` |
| `data/.../db/Converters.kt` | JSON map columns (kotlinx), enums by name |
| `data/.../db/Migrations.kt` | Empty in v1. Policy in §2.1.3 |
| `data/.../repo/RoomMergeSink.kt`, `RoomExistingChannels.kt` | `MergeSink` and `ExistingChannels` over `useWriterConnection`/`useReaderConnection` with prepared statements |
| `data/.../repo/PlaylistRepository.kt` | CRUD, secrets, `timeBase(playlist)`, `catchupDefaults(playlist)`, account card flow |
| `data/.../repo/ChannelRepository.kt` | Read models (`Channel`), browse/number/search queries with the parental predicate, `remapUserData` |
| `data/.../repo/RefreshRepository.kt` | Orchestrates one playlist refresh (fetch → parse → merge → report → match → patch enqueue) |
| `data/.../repo/EpgRepository.kt` | Source list, build/patch orchestration, `EpgWriteLock`, `resolvedRefs()` (source priority), shift resolution |
| `data/.../repo/EpgMatchRepository.kt` | `MatcherWhitelistResolver` (implements `EpgWhitelistResolver`), incremental matching, unmatched queries, override CRUD |
| `data/.../repo/FavouritesRepository.kt`, `ChannelPrefsRepository.kt`, `ReminderRepository.kt` | User data by `(playlist_id, stable_key)` |
| `data/.../repo/SyncStatusRepository.kt` | `StateFlow<SyncStatus>` from WorkInfo + refresh_log (S15 chip) |
| `data/.../repo/DataSettings.kt` | `interface DataSettings` (EPG past/future days, match level, store descriptions, keep cached XMLTV) with an in-memory default impl. M5 backs it with DataStore |
| `data/.../net/HttpModule.kt` | OkHttp clients (API and download profiles), DoH hook point, UA default |
| `data/.../net/OkHttpSourceFetcher.kt` | `SourceFetcher`: conditional GET, `.part` + rename, size cap, error mapping |
| `data/.../net/ContentCopier.kt` | `M3U_FILE` (SAF URI) → cache file |
| `data/.../security/TinkCredentialStore.kt` | `CredentialStore` (§3.9) |
| `data/.../epg/AndroidFreeSpace.kt` | `StatFs`. (reviewed 2026-10-13, S1: `SqliteTmpDir.kt` removed. The bundled SQLite is built with `TEMP_STORE=3`, so `SQLITE_TMPDIR` is never read) |
| `data/.../work/PlaylistRefreshWorker.kt`, `EpgImportWorker.kt`, `EpgPatchWorker.kt` | `@HiltWorker` (§3.10); each implements `getForegroundInfo()` (reviewed 2026-10-13, B4) |
| `data/.../work/ImportMutex.kt` | (reviewed 2026-10-13, S9) Process-wide `Mutex` held by the playlist merge phase and by EPG build/patch, so the two importers never overlap (§4.5 budget) |
| `data/.../work/RefreshScheduler.kt` | Periodic and one-time scheduling, app-start staleness |
| `data/.../work/WorkNotifications.kt` | Channel + foreground info (dataSync) |
| `data/.../di/DataModule.kt`, `DatabaseModule.kt`, `EpgModule.kt`, `DispatchersModule.kt`, `Qualifiers.kt` | Hilt bindings |
| `app/.../TheBoxApplication.kt` | `Configuration.Provider` + `HiltWorkerFactory`; installs the `BoxLog` sink |
| `app/src/main/AndroidManifest.xml` | Removes `WorkManagerInitializer`; adds FGS dataSync permission and `SystemForegroundService` type |

### 1.7 Integration sequence

1. Lead: commit 0. CI green.
2. A, B, C and D work in parallel. D uses fakes for A/B/C in its unit tests (`FakeDiffer`, `FakeEpgStore` in `data/src/test`).
3. As each of A, B and C merges, D enables the matching integration test in §5.6 (they ship `@Ignore("needs WP-x")`).
4. Lead review pass, then the code review and the §6 gates that M1 can measure (JVM perf; device numbers are recorded and left open until hardware exists, per master plan §7).

---

## 2. Schemas

### 2.1 library.db (Room 2.8.5, `BundledSQLiteDriver`, WAL)

#### 2.1.1 Principles

- **User data never lives on refresh-owned rows.** `channel` and `channel_group` belong to the refresh: they are upserted, soft-deleted and purged. Everything the user sets lives in tables keyed by `(playlist_id, stable_key)` or `(playlist_id, kind, group_key)`: `channel_prefs`, `group_prefs`, `favourite`, `epg_mapping`, `reminder`, `stream_source`. Purging a channel leaves its user data alone. When the channel comes back, its prefs and favourites come back with it.
- **No column anywhere references an `epg.db` row id.** EPG references are `(epg_source.id, xmltv_id)`. `epg_source.id` is a library.db row created by the user or by a playlist, and it is stable.
- **No secrets in clear.** Source URLs, usernames, passwords and secret headers live in `credential` (Tink AEAD). `channel.url` holds a template (§3.2.4). (reviewed 2026-10-13, B3) The same applies to everything derived from a secret: `channel.catchup_source` is templated like `url`; `playlist.header_json` stores EPG URLs only in redacted form; `epg_source.url_hash` hashes the **templated/redacted** URL (a plain 64-bit FNV of `xmltv.php?username=…&password=…` can be brute-forced back to the password); `stable_key` never contains a credential segment (§3.2.2).
- Times are epoch **milliseconds** (`*_at`) except EPG keys, which are epoch **minutes** (`start_min`).
- Enums are stored by name (TEXT), except `kind`, which is stored by ordinal (hot query column; `EntryKind` is append-only).
- Every DAO channel query that feeds UI takes the parental predicate (architecture review C7) as SQL, not as a UI filter:

```sql
-- :unlocked = 1 when the PIN session is open
AND COALESCE(cp.hidden, 0) = 0
AND COALESCE(gp.hidden, 0) = 0
AND (:unlocked = 1 OR (COALESCE(cp.locked, c.is_adult) = 0 AND COALESCE(gp.locked, g.is_adult, 0) = 0))
```

#### 2.1.2 DDL (normative; Room entities mirror it, the exported `data/schemas/…/1.json` must match)

```sql
-- Playlists. Secrets are in credential(ref = credential_ref).
CREATE TABLE playlist (
  id                       INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  name                     TEXT    NOT NULL,
  type                     TEXT    NOT NULL,              -- PlaylistType
  credential_ref           TEXT    NOT NULL,
  display_source           TEXT    NOT NULL,              -- redacted: "panel.example.com:8080", "my_list.m3u"
  sort_index               INTEGER NOT NULL,
  enabled                  INTEGER NOT NULL DEFAULT 1,
  needs_reauth             INTEGER NOT NULL DEFAULT 0,    -- set on Credentials(KEY_INVALIDATED) or Auth errors
  user_agent               TEXT,                          -- null = app default
  extra_headers_json       TEXT,                          -- non-secret headers; secret ones go in PlaylistSecret.headers
  use_playlist_epg         INTEGER NOT NULL DEFAULT 1,
  update_on_start          INTEGER NOT NULL DEFAULT 1,
  refresh_interval_h       INTEGER NOT NULL DEFAULT 24,   -- 0 = manual
  numbering_mode           TEXT    NOT NULL DEFAULT 'PROVIDER',  -- PROVIDER | PLAYLIST_ORDER | START_AT:<n>
  order_mode               TEXT    NOT NULL DEFAULT 'PROVIDER',  -- PROVIDER | NUMBER | NAME
  xtream_output            TEXT    NOT NULL DEFAULT 'AUTO',      -- AUTO | TS | HLS
  content_types            INTEGER NOT NULL DEFAULT 7,    -- bit0 live+radio, bit1 movies, bit2 series
  extgrp_sticky            INTEGER NOT NULL DEFAULT 0,
  charset_override         TEXT,
  catchup_mode_setting     TEXT    NOT NULL DEFAULT 'AUTO',      -- AUTO | OFF | <CatchupMode>
  catchup_days_override    INTEGER,
  catchup_probe_winner     TEXT,                          -- CatchupVariants.* that worked last
  token_zone_setting       TEXT    NOT NULL DEFAULT 'AUTO',      -- AUTO | UTC | DEVICE | SERVER | <IANA id>
  server_zone              TEXT,                          -- validated IANA id from server_info.timezone
  server_offset_sec        INTEGER,                       -- TimeBase.offsetFromServerInfo, when zone invalid
  clock_skew_sec           INTEGER NOT NULL DEFAULT 0,    -- timestamp_now − device now
  catchup_correction_h     REAL,                          -- user value; null → header → 0
  padding_before_sec       INTEGER NOT NULL DEFAULT 60,
  padding_after_sec        INTEGER NOT NULL DEFAULT 300,
  max_connections_override INTEGER,
  ignore_tls_errors        INTEGER NOT NULL DEFAULT 0,
  active_base_index        INTEGER NOT NULL DEFAULT 0,    -- index into PlaylistSecret.urls that worked last
  header_json              TEXT,                          -- M3uHeader, EPG URLs redacted (reviewed 2026-10-13, B3)
  etag                     TEXT,                          -- written only by MergeSink.finish (reviewed 2026-10-13, S10)
  last_modified            TEXT,
  refresh_seq              INTEGER NOT NULL DEFAULT 0,
  live_count               INTEGER NOT NULL DEFAULT 0,    -- live+radio after last success (shrink guard)
  last_refresh_at          INTEGER,
  last_success_at          INTEGER,
  last_status              TEXT,                          -- RefreshOutcome or SourceError.code
  created_at               INTEGER NOT NULL
);

CREATE TABLE credential (
  ref         TEXT PRIMARY KEY NOT NULL,                  -- "cred-<uuid>"
  ciphertext  BLOB    NOT NULL,                           -- AEAD(JSON(PlaylistSecret)), AD = ref
  key_version INTEGER NOT NULL DEFAULT 1,
  updated_at  INTEGER NOT NULL
);

CREATE TABLE xtream_account (
  playlist_id        INTEGER PRIMARY KEY NOT NULL REFERENCES playlist(id) ON DELETE CASCADE,
  info_json          TEXT    NOT NULL,                    -- XtreamAccountInfo
  status             TEXT    NOT NULL,
  expires_at         INTEGER,                             -- epoch ms, null = unlimited
  max_connections    INTEGER,
  active_connections INTEGER,
  fetched_at         INTEGER NOT NULL
);

-- Refresh-owned. Identity = (playlist_id, kind, group_key).
CREATE TABLE channel_group (
  id            INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  playlist_id   INTEGER NOT NULL REFERENCES playlist(id) ON DELETE CASCADE,
  kind          INTEGER NOT NULL,                         -- EntryKind ordinal (RADIO shares LIVE groups: stored as 0)
  group_key     TEXT    NOT NULL,                         -- "xcat:<id>" | "g:<forKey(name)>" | "g:"
  name          TEXT    NOT NULL,
  sort_index    INTEGER NOT NULL,                         -- provider order
  is_adult      INTEGER NOT NULL DEFAULT 0,               -- name ~ /\b(xxx|adult|18\+|porn)\b/i or Xtream is_adult majority
  missing_since INTEGER
);
CREATE UNIQUE INDEX index_channel_group_identity ON channel_group(playlist_id, kind, group_key);

-- Refresh-owned. Identity = (playlist_id, stable_key).
CREATE TABLE channel (
  id                   INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,   -- internal only; never referenced by user data
  playlist_id          INTEGER NOT NULL REFERENCES playlist(id) ON DELETE CASCADE,
  stable_key           TEXT    NOT NULL,
  key_rule             TEXT    NOT NULL,                  -- KeyRule
  kind                 INTEGER NOT NULL,                  -- EntryKind ordinal
  name                 TEXT    NOT NULL,
  norm_name            TEXT    NOT NULL,                  -- NameNormalizer.forMatch(name)
  number               INTEGER,                           -- tvg-chno / Xtream num
  sort_index           INTEGER NOT NULL,                  -- provider position; rewritten each refresh
  group_id             INTEGER REFERENCES channel_group(id) ON DELETE SET NULL,
  logo_url             TEXT,
  tvg_id               TEXT,
  tvg_name             TEXT,
  url                  TEXT    NOT NULL,                  -- template (§3.2.4)
  headers_json         TEXT,                              -- ParsedChannel.headers
  xtream_stream_id     INTEGER,
  container_ext        TEXT,
  epg_shift_min        INTEGER,                           -- tvg-shift
  catchup_type         TEXT,                              -- RawCatchup.*
  catchup_source       TEXT,                              -- templated like url (reviewed 2026-10-13, B3)
  catchup_days         INTEGER,
  catchup_correction_h REAL,
  timeshift_days       INTEGER,
  tvg_rec_days         INTEGER,
  catchup_time_sec     INTEGER,
  tv_archive           INTEGER,
  tv_archive_days      INTEGER,
  is_adult             INTEGER NOT NULL DEFAULT 0,
  extras_json          TEXT,                              -- {"attrs":{},"kodi":{},"vlc":{},"groups":[],"duration":n}
  content_hash         INTEGER NOT NULL,
  missing_since        INTEGER,
  missing_count        INTEGER NOT NULL DEFAULT 0,
  first_seen_at        INTEGER NOT NULL,
  updated_at           INTEGER NOT NULL
);
CREATE UNIQUE INDEX index_channel_identity ON channel(playlist_id, stable_key);
CREATE INDEX index_channel_browse  ON channel(playlist_id, kind, group_id, sort_index);
CREATE INDEX index_channel_number  ON channel(playlist_id, kind, number);
CREATE INDEX index_channel_tvg     ON channel(playlist_id, tvg_id);
CREATE INDEX index_channel_missing ON channel(playlist_id, missing_since);
CREATE INDEX index_channel_group   ON channel(group_id);

-- Title-only channel search (architecture §3.7).
-- (reviewed 2026-10-13, S6) A standalone Room @Fts4 entity (tokenizer = unicode61,
-- tokenizerArgs = ["remove_diacritics=2"]), NOT contentEntity = ChannelEntity. Room's content-sync
-- triggers are `BEFORE/AFTER UPDATE ON channel` with no column list (verified in room-compiler 2.8.5),
-- so every ChannelTouch (sort_index, missing_since) would rewrite FTS rows. RoomMergeSink maintains it:
-- insert on channel insert, delete+insert when the name changes, delete on purge. rowid = channel.id.
-- Search joins on rowid and applies `missing_since IS NULL` + the parental predicate.
CREATE VIRTUAL TABLE channel_fts USING FTS4(name, tokenize=unicode61 `remove_diacritics=2`);

-- v1.1 multi-source failover. Ships empty in v1. A logical channel (playlist_id, stable_key) gets
-- extra sources, each another channel identity, tried in priority order.
CREATE TABLE stream_source (
  playlist_id        INTEGER NOT NULL REFERENCES playlist(id) ON DELETE CASCADE,
  stable_key         TEXT    NOT NULL,
  priority           INTEGER NOT NULL,                    -- 1.. (0 is the channel's own stream, implicit)
  source_playlist_id INTEGER NOT NULL REFERENCES playlist(id) ON DELETE CASCADE,
  source_stable_key  TEXT    NOT NULL,
  enabled            INTEGER NOT NULL DEFAULT 1,
  created_at         INTEGER NOT NULL,
  PRIMARY KEY (playlist_id, stable_key, priority)
);
CREATE INDEX index_stream_source_source ON stream_source(source_playlist_id, source_stable_key);

-- User data ---------------------------------------------------------------
CREATE TABLE group_prefs (
  playlist_id       INTEGER NOT NULL REFERENCES playlist(id) ON DELETE CASCADE,
  kind              INTEGER NOT NULL,
  group_key         TEXT    NOT NULL,
  hidden            INTEGER NOT NULL DEFAULT 0,
  locked            INTEGER,                              -- null = auto (channel_group.is_adult)
  custom_sort_index INTEGER,
  custom_name       TEXT,
  updated_at        INTEGER NOT NULL,
  PRIMARY KEY (playlist_id, kind, group_key)
);

CREATE TABLE channel_prefs (
  playlist_id       INTEGER NOT NULL REFERENCES playlist(id) ON DELETE CASCADE,
  stable_key        TEXT    NOT NULL,
  hidden            INTEGER NOT NULL DEFAULT 0,
  locked            INTEGER,                              -- null = auto (channel.is_adult / group lock)
  custom_name       TEXT,
  custom_number     INTEGER,
  custom_logo       TEXT,
  custom_sort_index INTEGER,                              -- user order inside its group (dense per group)
  epg_shift_min     INTEGER,                              -- per-channel guide shift (overrides tvg-shift)
  aspect_mode       TEXT,
  audio_lang        TEXT,
  subtitle_lang     TEXT,
  video_decoder     TEXT,
  buffer_profile    TEXT,
  last_watched_at   INTEGER,
  watch_count       INTEGER NOT NULL DEFAULT 0,
  updated_at        INTEGER NOT NULL,
  PRIMARY KEY (playlist_id, stable_key)
);
CREATE INDEX index_channel_prefs_recent ON channel_prefs(last_watched_at);

CREATE TABLE favourite_list (
  id         INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  name       TEXT    NOT NULL,
  sort_index INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE TABLE favourite (
  list_id     INTEGER NOT NULL REFERENCES favourite_list(id) ON DELETE CASCADE,
  playlist_id INTEGER NOT NULL REFERENCES playlist(id) ON DELETE CASCADE,
  stable_key  TEXT    NOT NULL,
  sort_index  INTEGER NOT NULL,
  added_at    INTEGER NOT NULL,
  PRIMARY KEY (list_id, playlist_id, stable_key)
);
CREATE INDEX index_favourite_channel ON favourite(playlist_id, stable_key);

-- EPG sources (ordered). id is the stable sourceId used in epg.db and EpgChannelRef.
-- (reviewed 2026-10-13, S14) Playlist-derived sources (PLAYLIST_HEADER, XTREAM) are identified by
-- (playlist_id, origin, ordinal) and their URL is UPDATED IN PLACE. Delete+recreate on a URL change
-- (tokenised x-tvg-url, new host) would cascade-delete the user's epg_mapping overrides.
CREATE TABLE epg_source (
  id              INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  name            TEXT    NOT NULL,
  origin          TEXT    NOT NULL,                       -- USER | PLAYLIST_HEADER | XTREAM
  playlist_id     INTEGER REFERENCES playlist(id) ON DELETE CASCADE,  -- null = USER source, applies to all playlists
  ordinal         INTEGER NOT NULL DEFAULT 0,             -- position in the playlist's x-tvg-url list (0 for XTREAM/USER)
  credential_ref  TEXT    NOT NULL,                       -- URL may embed credentials (xmltv.php)
  url_hash        INTEGER NOT NULL,                       -- Hash64 of the TEMPLATED/redacted URL, for de-duplication (reviewed 2026-10-13, B3)
  display_source  TEXT    NOT NULL,
  priority        INTEGER NOT NULL,                       -- ascending; first source with data wins per channel
  enabled         INTEGER NOT NULL DEFAULT 1,
  shift_min       INTEGER NOT NULL DEFAULT 0,             -- per-source guide shift
  assume_zone     TEXT,                                   -- zone for offset-less times; null = UTC
  etag            TEXT,
  last_modified   TEXT,
  cached_file     TEXT,                                   -- cacheDir/epg/<id>.src (last good)
  cached_bytes    INTEGER,
  last_import_at  INTEGER,
  last_status     TEXT,
  channel_count   INTEGER NOT NULL DEFAULT 0,
  programme_count INTEGER NOT NULL DEFAULT 0,
  created_at      INTEGER NOT NULL
);
CREATE INDEX index_epg_source_playlist ON epg_source(playlist_id);
CREATE INDEX index_epg_source_hash ON epg_source(url_hash);
CREATE UNIQUE INDEX index_epg_source_origin ON epg_source(playlist_id, origin, ordinal);  -- USER rows: playlist_id NULL, never conflict

-- User overrides (TiviMate "Channel → EPG"). Both null = explicit "no guide data".
CREATE TABLE epg_mapping (
  playlist_id INTEGER NOT NULL REFERENCES playlist(id) ON DELETE CASCADE,
  stable_key  TEXT    NOT NULL,
  source_id   INTEGER REFERENCES epg_source(id) ON DELETE CASCADE,
  xmltv_id    TEXT,
  created_at  INTEGER NOT NULL,
  PRIMARY KEY (playlist_id, stable_key)
);
CREATE INDEX index_epg_mapping_source ON epg_mapping(source_id);

-- Matcher cache (derived data, may be wiped and recomputed). One AUTO row at most per
-- (channel, source); up to MatchSettings.maxSuggestions SUGGESTED rows.
CREATE TABLE epg_match (
  playlist_id INTEGER NOT NULL REFERENCES playlist(id) ON DELETE CASCADE,
  stable_key  TEXT    NOT NULL,
  source_id   INTEGER NOT NULL REFERENCES epg_source(id) ON DELETE CASCADE,
  xmltv_id    TEXT    NOT NULL,
  status      INTEGER NOT NULL,                           -- 0 AUTO, 1 SUGGESTED
  method      TEXT    NOT NULL,                           -- MatchMethod
  score       REAL    NOT NULL,
  has_data    INTEGER NOT NULL DEFAULT 0,                 -- programme_count > 0 in the current generation
  inputs_hash INTEGER NOT NULL,                           -- Hash64(tvg_id, tvg_name, name, settings, MATCHER_VERSION)
  matched_at  INTEGER NOT NULL,
  PRIMARY KEY (playlist_id, stable_key, source_id, xmltv_id)
);
CREATE INDEX index_epg_match_status ON epg_match(playlist_id, status);
CREATE INDEX index_epg_match_source ON epg_match(source_id);

CREATE TABLE reminder (
  id           INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  playlist_id  INTEGER NOT NULL REFERENCES playlist(id) ON DELETE CASCADE,
  stable_key   TEXT    NOT NULL,
  start_min    INTEGER NOT NULL,                          -- RAW (unshifted) programme start, epoch minutes
  end_min      INTEGER NOT NULL,
  title_hash   INTEGER NOT NULL,                          -- Hash64.of(title)
  title        TEXT    NOT NULL,
  channel_name TEXT    NOT NULL,
  lead_sec     INTEGER NOT NULL DEFAULT 60,
  state        INTEGER NOT NULL DEFAULT 0,                -- 0 PENDING, 1 FIRED, 2 DISMISSED, 3 ORPHANED
  created_at   INTEGER NOT NULL
);
CREATE UNIQUE INDEX index_reminder_identity ON reminder(playlist_id, stable_key, start_min);
CREATE INDEX index_reminder_due ON reminder(state, start_min);

CREATE TABLE refresh_log (
  id           INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  target_type  TEXT    NOT NULL,                          -- PLAYLIST | EPG_BUILD | EPG_PATCH
  target_id    INTEGER,                                   -- playlist.id; null for EPG builds
  trigger      TEXT    NOT NULL,                          -- PERIODIC | APP_START | MANUAL | ONBOARDING | MAPPING | PLAYLIST_CHANGED
  started_at   INTEGER NOT NULL,
  finished_at  INTEGER,
  outcome      TEXT    NOT NULL,                          -- RefreshOutcome
  error_code   TEXT,                                      -- SourceError.code
  error_detail TEXT,                                      -- Redact.text(...)
  bytes        INTEGER,
  report_json  TEXT,                                      -- RefreshReport | EpgImportReport
  acknowledged INTEGER NOT NULL DEFAULT 0                 -- S15: failures persist until acknowledged
);
CREATE INDEX index_refresh_log_target ON refresh_log(target_type, target_id, started_at);

-- (reviewed 2026-10-13, S8) Tombstones for purged channel keys: the clock for the 180-day orphan rule.
-- Written by MergeSink.finish for MergeFinish.purgedKeys. Deleted when the key reappears (insert path).
CREATE TABLE purged_key (
  playlist_id INTEGER NOT NULL REFERENCES playlist(id) ON DELETE CASCADE,
  stable_key  TEXT    NOT NULL,
  purged_at   INTEGER NOT NULL,
  PRIMARY KEY (playlist_id, stable_key)
);
CREATE INDEX index_purged_key_age ON purged_key(purged_at);
```

Retention: `refresh_log` keeps the newest 30 rows per `(target_type, target_id)`. The trim runs in the same transaction as the insert. Orphaned user data (prefs, favourites, EPG mappings, reminders) whose channel row was purged more than 180 days ago is removed by a weekly maintenance step in `PlaylistRefreshWorker`. (reviewed 2026-10-13, S8) The orphan age is `purged_key.purged_at`, **not** `favourite.added_at`/`channel_prefs.updated_at`: with those, an old favourite would be deleted the day its channel was purged. Only keys with a tombstone older than 180 days and no `channel` row count. Their tombstone is deleted with them.

Example entity, which fixes the conventions for the rest (camelCase properties, explicit `@ColumnInfo(name, defaultValue)`, `String` keys rather than value classes):

```kotlin
@Entity(
    tableName = "channel",
    foreignKeys = [
        ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlist_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ChannelGroupEntity::class, parentColumns = ["id"], childColumns = ["group_id"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [
        Index(value = ["playlist_id", "stable_key"], unique = true, name = "index_channel_identity"),
        Index(value = ["playlist_id", "kind", "group_id", "sort_index"], name = "index_channel_browse"),
        Index(value = ["playlist_id", "kind", "number"], name = "index_channel_number"),
        Index(value = ["playlist_id", "tvg_id"], name = "index_channel_tvg"),
        Index(value = ["playlist_id", "missing_since"], name = "index_channel_missing"),
        Index(value = ["group_id"], name = "index_channel_group"),
    ],
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "playlist_id") val playlistId: Long,
    @ColumnInfo(name = "stable_key") val stableKey: String,
    @ColumnInfo(name = "key_rule") val keyRule: String,
    val kind: Int,
    val name: String,
    @ColumnInfo(name = "norm_name") val normName: String,
    val number: Int?,
    @ColumnInfo(name = "sort_index") val sortIndex: Int,
    @ColumnInfo(name = "group_id") val groupId: Long?,
    // … remaining columns exactly as in the DDL …
    @ColumnInfo(name = "is_adult", defaultValue = "0") val isAdult: Boolean,
    @ColumnInfo(name = "content_hash") val contentHash: Long,
    @ColumnInfo(name = "missing_since") val missingSince: Long?,
    @ColumnInfo(name = "missing_count", defaultValue = "0") val missingCount: Int,
    @ColumnInfo(name = "first_seen_at") val firstSeenAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
```

#### 2.1.3 Database setup and migration policy

```kotlin
Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db")
    .setDriver(BundledSQLiteDriver())
    .setQueryCoroutineContext(ioDispatcher)
    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
    .build()
```

(reviewed 2026-10-13, S2) There is no open callback; Room 2.8.5 already does all of this. Its generated open delegate runs `PRAGMA foreign_keys = ON` in `onOpen` whenever an entity declares a foreign key. `RoomConnectionManager` calls that `onOpen`, plus `synchronous = NORMAL` (WAL) and `busy_timeout`, on **every** pooled connection, and `InvalidationTracker` sets `temp_store = MEMORY`. A `RoomDatabase.Callback.onOpen` runs only for the first connection, so per-connection pragmas placed there would never reach the pool. I-1 asserts `PRAGMA foreign_keys = 1` on a reader and on the writer.

- `version = 1`, `exportSchema = true`, schema JSON committed under `data/schemas/`.
- Every later schema change bumps the version and ships an `@AutoMigration` or a hand-written `Migration` plus an instrumented `MigrationTestHelper` test. `fallbackToDestructiveMigration*` is forbidden: it would wipe favourites (complaint #6).
- `channel`, `channel_group` and `epg_match` are re-derivable. A future migration may drop and refill them by setting `playlist.etag = NULL` and `refresh_seq = 0`, which forces a full refresh. User-data tables must always be migrated.

#### 2.1.4 Key queries (WP-D)

Unmatched channels (S13; "most watched first"). The screen is M5, the query ships in M1:

```sql
SELECT c.playlist_id, c.stable_key, c.name, c.tvg_id, c.tvg_name, g.name AS group_name,
       COALESCE(cp.watch_count, 0) AS watch_count,
       (SELECT em.source_id || char(31) || em.xmltv_id || char(31) || em.score
          FROM epg_match em
         WHERE em.playlist_id = c.playlist_id AND em.stable_key = c.stable_key AND em.status = 1
         ORDER BY em.score DESC LIMIT 1) AS best_suggestion
FROM channel c
JOIN playlist p            ON p.id = c.playlist_id AND p.enabled = 1
LEFT JOIN channel_group g  ON g.id = c.group_id
LEFT JOIN channel_prefs cp ON cp.playlist_id = c.playlist_id AND cp.stable_key = c.stable_key
LEFT JOIN group_prefs gp   ON gp.playlist_id = c.playlist_id AND gp.kind = 0 AND gp.group_key = g.group_key
LEFT JOIN epg_mapping m    ON m.playlist_id = c.playlist_id AND m.stable_key = c.stable_key
WHERE c.kind IN (0, 1)
  AND c.missing_since IS NULL
  AND m.stable_key IS NULL
  AND NOT EXISTS (SELECT 1 FROM epg_match a
                   WHERE a.playlist_id = c.playlist_id AND a.stable_key = c.stable_key AND a.status = 0)
  -- + parental predicate (§2.1.1)
ORDER BY watch_count DESC, COALESCE(cp.last_watched_at, 0) DESC, c.playlist_id, c.sort_index;
```

A sibling query, `matchedWithoutData`, uses the same joins with `EXISTS (… status = 0) AND NOT EXISTS (… status = 0 AND has_data = 1)` to list channels matched to an EPG channel that has no programmes (product: "No EPG" is the commonest confusion). `matchCounts(playlistId)` returns `(live, matched, matchedWithData)` for S5 stage 5 and S13.

The browse query (guide rows for one group, used by M3):

```sql
SELECT c.*, cp.custom_name, cp.custom_number, cp.custom_logo, cp.epg_shift_min AS pref_shift
FROM channel c
LEFT JOIN channel_group g  ON g.id = c.group_id
LEFT JOIN channel_prefs cp ON cp.playlist_id = c.playlist_id AND cp.stable_key = c.stable_key
LEFT JOIN group_prefs gp   ON gp.playlist_id = c.playlist_id AND gp.kind = 0 AND gp.group_key = g.group_key
WHERE c.playlist_id = :playlistId AND c.kind IN (0, 1) AND c.group_id = :groupId AND c.missing_since IS NULL
  -- + parental predicate
ORDER BY cp.custom_sort_index IS NULL, cp.custom_sort_index, c.sort_index;
```

`ORDER BY … IS NULL` puts channels the user has ordered first in their order. New channels that arrive later sort after them in provider order. That is the "sort_index preservation" rule (§3.3.3).

### 2.2 Resolved EPG reference per channel (WP-D, in memory)

`EpgRepository.resolvedRefs(playlistIds)` returns `Map<ChannelIdentity, ResolvedEpg(ref: EpgChannelRef?, shiftMin: Int, method: MatchMethod?)>`, computed as:

1. `epg_mapping` row: an explicit ref, or explicit "none".
2. Otherwise, for sources in `priority` order (enabled, and applicable to the playlist: `playlist_id IS NULL OR playlist_id = c.playlist_id`), the first `epg_match` row with `status = AUTO AND has_data = 1`.
3. Otherwise the first `AUTO` row without data (shown as "matched, no programmes").

`shiftMin = epg_source.shift_min + (channel_prefs.epg_shift_min ?: channel.epg_shift_min ?: round(header.tvgShiftHours × 60) ?: 0) + globalShiftMin`.

The guide calls `EpgReader.resolve(refs)` once per generation change and keeps the `IntArray` in memory. These ints never leave the process.

### 2.3 epg.db (raw `androidx.sqlite`, generation-named)

```sql
PRAGMA user_version = 1;                       -- EpgSchema.SCHEMA_VERSION

CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID;
-- keys: schema_version, generation, patch_seq, built_at_ms, complete ('1' only after a successful build),
--       fts ('fts5' | 'fts4'), window_from_min, window_to_min, programme_count, channel_count, app_version

CREATE TABLE source_stat (
  source_id INTEGER PRIMARY KEY, channels INTEGER NOT NULL, programmes_seen INTEGER NOT NULL,
  programmes_kept INTEGER NOT NULL, bytes INTEGER NOT NULL, imported_at_ms INTEGER NOT NULL
);

-- Every <channel> of every source, whitelisted or not (architecture review B3.3).
CREATE TABLE epg_channel (
  id              INTEGER PRIMARY KEY,           -- generation-local: NEVER stored outside this file
  source_id       INTEGER NOT NULL,              -- library.db epg_source.id
  xmltv_id        TEXT    NOT NULL,
  display_name    TEXT    NOT NULL,              -- preferred-language display-name ('' if none)
  icon_url        TEXT,
  imported        INTEGER NOT NULL DEFAULT 0,    -- 1 = programmes whitelisted in this generation
  programme_count INTEGER NOT NULL DEFAULT 0,
  max_len_min     INTEGER NOT NULL DEFAULT 360,  -- longest stored row (≤ 360 after splitting)
  first_start_min INTEGER,
  last_end_min    INTEGER,
  UNIQUE (source_id, xmltv_id)
);
CREATE INDEX epg_channel_ci ON epg_channel(xmltv_id COLLATE NOCASE);

CREATE TABLE epg_channel_name (
  epg_channel_id INTEGER NOT NULL,
  name           TEXT    NOT NULL,
  norm           TEXT    NOT NULL,               -- NameNormalizer.forMatch(name)
  lang           TEXT,
  PRIMARY KEY (epg_channel_id, name)
) WITHOUT ROWID;
CREATE INDEX epg_channel_name_norm ON epg_channel_name(norm);

-- De-duplicated programme text (repeats are common).
CREATE TABLE epg_text (
  id            INTEGER PRIMARY KEY,             -- generation-local
  hash          INTEGER NOT NULL,                -- Hash64 over all columns below
  title         TEXT    NOT NULL,
  subtitle      TEXT,
  description   TEXT,                            -- capped at maxDescriptionChars; null when not stored
  categories    TEXT,                            -- '\u001F'-joined, preferred language
  episode_label TEXT,                            -- display form, e.g. "S3 E10" or onscreen text
  season        INTEGER,                         -- 1-based
  episode       INTEGER,                         -- 1-based
  icon_url      TEXT,
  rating        TEXT,                            -- "MPAA:PG-13"
  star_rating   TEXT,                            -- "7.5/10"
  year          INTEGER,
  credits       TEXT,                            -- compact "d:Name\u001Fa:Name (Role)…", capped at 512 chars
  series_ref    TEXT                             -- dd_progid series part ("EP01234567"), for v2 series rules
);

-- Clustered per channel: the window query is a PK range scan.
CREATE TABLE programme (
  epg_channel_id INTEGER NOT NULL,
  start_min      INTEGER NOT NULL,               -- RAW UTC epoch minutes (no EPG shift applied)
  end_min        INTEGER NOT NULL,
  text_id        INTEGER NOT NULL,
  flags          INTEGER NOT NULL DEFAULT 0,     -- ProgrammeFlags
  catchup_id     TEXT,                           -- XMLTV programme@catchup-id
  PRIMARY KEY (epg_channel_id, start_min)
) WITHOUT ROWID;

-- Created after the bulk load:
CREATE INDEX programme_by_text ON programme(text_id, start_min);   -- search → airings
CREATE INDEX epg_text_hash ON epg_text(hash);                       -- patch / in-place dedup

-- FTS on title + subtitle only (architecture review B3.2). FTS5 when compiled in, else FTS4.
CREATE VIRTUAL TABLE epg_fts USING fts5(title, subtitle, content='epg_text', content_rowid='id',
                                        tokenize='unicode61 remove_diacritics 2');
-- FTS4 fallback:
-- CREATE VIRTUAL TABLE epg_fts USING fts4(content="epg_text", title, subtitle, tokenize=unicode61 "remove_diacritics=2");
-- Filled once after the load: INSERT INTO epg_fts(epg_fts) VALUES('rebuild');
```

FTS detection: `SELECT sqlite_compileoption_used('ENABLE_FTS5')` on the build connection. The kind is stored in `meta.fts`, and readers build their `MATCH` SQL accordingly (`bm25` only with FTS5).

Pragmas:

| Connection | Pragmas |
|---|---|
| Build (new generation) | `journal_mode=OFF`, `synchronous=OFF`, `locking_mode=EXCLUSIVE`, `secure_delete=OFF`, `cache_size=-16000` (16 MB native), `page_size=4096` before the first table. Before close: `ANALYZE`, `PRAGMA journal_mode=DELETE`, `meta.complete='1'` |
| Reader | `SQLITE_OPEN_READONLY`; `query_only=1`, `cache_size=-4000`, `mmap_size=67108864`, `busy_timeout=250` |
| Patch / in-place writer | read-write on the current file; `journal_mode=DELETE`, `synchronous=NORMAL`, `secure_delete=OFF`, `busy_timeout=2000` |

(reviewed 2026-10-13, S1) **Temp storage is always in memory.** `sqlite-bundled` 2.6.2 (SQLite 3.50.1) is compiled with `TEMP_STORE=3`. That holds for both the JVM jar (checked with `PRAGMA compile_options`) and every ABI of the Android AAR (checked in the `.so` strings). So `temp_store=FILE` and `SQLITE_TMPDIR` are ignored, and there is no temp-directory trap. The cost moves to **native memory**: the sorter for `CREATE INDEX programme_by_text` (1M rows), `epg_text_hash` and the FTS `rebuild` keep their spill in RAM, an estimated 30–60 MB peak on the 1M case. I-5 records `Debug.getNativeHeapAllocatedSize()` across the build, with a budget of ≤ 64 MB native peak. If it is exceeded, `EpgSchema.INDEX_BEFORE_LOAD = true` creates `programme_by_text` before the bulk insert (no sort, about 20 % slower inserts). `secure_delete` is compiled on, so EPG writers turn it off: there is nothing secret in `epg.db`, and in-place deletes would otherwise zero every freed page.

### 2.4 Window and search queries

Window: one prepared statement per reader, run once per visible channel. Each run is a bounded PK range scan, there is no IN-list limit, and the result order needs no sort:

```sql
-- ?1 epg_channel_id, ?2 raw window start (= display start − shiftMin), ?3 raw window end,
-- ?4 channel max_len_min (cached per reader in an IntArray, ≤ 360)
SELECT p.start_min, p.end_min, p.text_id, p.flags
FROM programme p
WHERE p.epg_channel_id = ?1
  AND p.start_min >= ?2 - ?4
  AND p.start_min <  ?3
  AND p.end_min   >  ?2
ORDER BY p.start_min;
```

Titles come from a per-reader `LruCache<Int, String>` keyed by `text_id` (4,096 entries). On a miss, a second prepared statement `SELECT title FROM epg_text WHERE id = ?` runs. This keeps the scroll path free of string allocation for programmes already seen. `EpgReaderImpl.window` fills `EpgWindow` with display minutes = raw + `shiftMin[i]`, plus the raw start.

Detail: `SELECT p.*, t.* FROM programme p JOIN epg_text t ON t.id = p.text_id WHERE p.epg_channel_id = ? AND p.start_min = ?`.

At (programme airing at a minute): `… WHERE p.epg_channel_id = ?1 AND p.start_min <= ?2 AND p.start_min >= ?2 - ?3 AND p.end_min > ?2 ORDER BY p.start_min DESC LIMIT 1`.

Search (FTS5 form; FTS4 drops `bm25` and orders by `p.start_min`):

```sql
SELECT p.epg_channel_id, p.start_min, p.end_min, t.title, t.subtitle
FROM epg_fts f
JOIN epg_text t  ON t.id = f.rowid
JOIN programme p ON p.text_id = t.id          -- programme_by_text
WHERE epg_fts MATCH ?1 AND p.end_min > ?2
ORDER BY bm25(epg_fts), p.start_min
LIMIT ?3;
```

The query string is built from the user's text: split it on non-letter/digit characters, drop empty tokens, double any `"` inside a token, wrap each token in quotes and add `*` for prefix matching (`"bbc"* "new"*`), and join with spaces (implicit AND). Parental filtering of search hits happens in `SearchRepository` (M3) before any result leaves `:data`. `:ui` never sees the unfiltered list.

`findChannels(text)` (picker): `SELECT DISTINCT ec.* FROM epg_channel_name n JOIN epg_channel ec ON ec.id = n.epg_channel_id WHERE n.norm LIKE ?1 || '%' OR ec.xmltv_id LIKE ?2 || '%' COLLATE NOCASE LIMIT ?3`, where `?1 = forMatch(text)`.

### 2.5 Generation pointer mechanism

#### 2.5.1 Files

Directory: `context.noBackupFilesDir/epg/`. It is excluded from backup, and app-private.

| File | Meaning |
|---|---|
| `epg-<gen>.db` | Published or retired generation. `<gen>` is a decimal `Long`, strictly increasing (`max(existing)+1`, starting at 1) |
| `epg-<gen>.db.building` | Generation under construction. Never opened by readers |
| `epg-<gen>.db-journal` | Rollback journal of a patch writer |
| `epg-current` | Pointer: one line `"<gen>\n"` |
| `epg-current.tmp` | Pointer being written |

#### 2.5.2 Build and atomic switch (`EpgStoreImpl.build`)

1. **Disk precheck** (§2.5.5). If it fails: use the in-place path when a generation exists, else throw `SourceException(DiskSpace)`.
2. `gen = max(existing gens) + 1`. Open `epg-<gen>.db.building` (read-write, create) with the build pragmas, then create the schema without the secondary indexes.
3. Per source, in priority order: parse (§3.5), whitelist callback, inserts; then the repair pass.
4. Create `programme_by_text`, `epg_text_hash`, `epg_channel_ci`, `epg_channel_name_norm`; FTS `rebuild`; `ANALYZE`; write `meta`; `PRAGMA journal_mode=DELETE`; close.
5. `FileChannel.force(true)` on the file, then rename `*.building` to `epg-<gen>.db` (same directory, so the rename is atomic on ext4/f2fs).
6. Write `epg-current.tmp`, `force(true)`, then rename it over `epg-current`. **This rename is the commit point.** A crash before it leaves the old generation current. A crash after it leaves the new one current.
7. In memory: open a `GenerationHandle` on the new file (2 read-only connections) and run `SELECT value FROM meta WHERE key='complete'`. Then `current.getAndSet(newHandle)`, `old?.releaseBase()`, and emit `info`.

If anything fails in steps 2–5, the `.building` file is deleted and the old generation stays current. The report carries `failedSourceId`.

#### 2.5.3 Reader lifecycle

```kotlin
internal class GenerationHandle(val gen: Long, val file: File, private val open: () -> ReaderConnections) {
    private val refs = AtomicInteger(1)                // 1 = the "published" reference
    fun tryRetain(): Boolean { while (true) { val r = refs.get(); if (r == 0) return false; if (refs.compareAndSet(r, r + 1)) return true } }
    fun release() { if (refs.decrementAndGet() == 0) { closeConnections(); if (!isCurrent) file.delete() } }
    fun releaseBase() = release()
}
```

- `openReader()`: loop `{ h = current.get() ?: return null; if (h.tryRetain()) return EpgReaderImpl(h) }`. `EpgReaderImpl.close()` calls `h.release()` exactly once (idempotent).
- Each handle owns two read-only connections: one for `window`/`at`/`detail` (the guide thread), one for `search`/`forEachChannel`/`findChannels`. Each connection is guarded by its own lock, and prepared statements are cached per connection.
- Guide contract: hold one reader per guide screen. When `info.generation` changes, open the new reader, re-`resolve()` the refs, swap, and close the old one. When only `patchSeq` changes, keep the reader but **re-`resolve()` the refs** and reload its per-channel `max_len_min` cache before re-running the visible window. (reviewed 2026-10-13, S13: a patch adds `epg_channel` rows for new mappings. Without re-resolving they stay `-1` until the next rebuild.)
- The old file is deleted when its last reader closes, so there is never more than one retired generation on disk unless a caller leaks a reader. `recover()` cleans up after a leak or a crash.

#### 2.5.4 Startup recovery and cleanup (`recover()`)

1. Read `epg-current`. If it is valid and `epg-<gen>.db` exists, opens, has `meta.complete='1'` and `user_version == SCHEMA_VERSION`, it is current.
2. Otherwise pick the highest `epg-*.db` that passes the same checks, and rewrite the pointer to it.
3. Delete every other `epg-*.db`, every `*.building`, `epg-current.tmp`, and any `-journal` whose database is not current. A `-journal` next to the current file is a hot journal from an interrupted patch: open the database read-write once so SQLite rolls it back, then continue.
4. If the schema version differs (app upgrade): no current generation. `EpgRepository` then enqueues a rebuild from the cached source files with no network.

#### 2.5.5 Disk-space precheck

```
current   = size(epg-<current>.db) or 0
estimate  = if (current > 0) current else Σ min(sourceFileBytes × 8, 400 MB) × 0.15   // whitelisting keeps ~5–20 %
required  = 2.5 × estimate + 64 MB                                                    // B3.4
free      = FreeSpace.bytesFree(dir)
```

If `free < required` and a generation exists, use the **in-place** path. If `free < required` and none exists, throw `DiskSpace(required, free)`. The cached-source policy (`DataSettings.keepCachedXmltv`, default "keep if free > 1 GB") is enforced by WP-D after each build.

#### 2.5.6 Patch and in-place paths

- **Patch** (new mappings, ≤ a few hundred channels): one writer connection on the current file (DELETE journal). Per source, run a streaming parse of the cached file with whitelist = the requested refs. Text dedup is seeded on demand through `epg_text_hash`. In transactions of 5,000 rows: `DELETE FROM programme WHERE epg_channel_id = ?` for each requested channel, then insert, then repair those channels. Set `imported = 1`, insert FTS rows for new texts (`INSERT INTO epg_fts(rowid, title, subtitle) …`), increment `meta.patch_seq`, emit `info`. Readers see `SQLITE_BUSY` only during the short commit, which `busy_timeout` covers.
- **In-place** (low disk): the same writer, for every source and every whitelisted channel. At the end: `DELETE FROM epg_text WHERE id NOT IN (SELECT text_id FROM programme)`, FTS `rebuild`, `ANALYZE`, `patch_seq++`. The guide may briefly show a mix of old and new data. This is accepted for a rare fallback, and the report says `mode = IN_PLACE`.

### 2.6 The EPG row-id rule

`epg_channel.id`, `epg_text.id` and every Int "channel" handed out by `EpgReader` are **generation-local**. They:

- must never be written to library.db, a file, `SavedStateHandle`, a `WorkRequest` input, a notification or a backup;
- must be re-resolved from `EpgChannelRef` after every generation change;
- are not guaranteed stable across a patch either, because patch can add `epg_channel` rows.

Durable references to a programme are `(playlist_id, stable_key, raw start_min, Hash64(title))`, as used by reminders now and recordings in v2. WP-D adds a lint-style unit test that fails if any Room entity has a column named `*epg_channel_id*` or `text_id`.

---

## 3. Algorithms

### 3.1 Name normalisation (shared, §1.2.3)

| Input | `forKey` | `forMatch` |
|---|---|---|
| `BBC One HD` | `bbc one` | `bbc one` |
| `BBC One FHD ᴴᴰ` | `bbc one` | `bbc one` |
| `UK: BBC One (Backup)` | `uk bbc one` | `bbc one` |
| `HBO (East)` / `HBO (West)` | `hbo east` / `hbo west` | `hbo` / `hbo` |
| `Channel 4 +1` | `channel 4 +1` | `channel 4 +1` |
| `Télé-Québec` | `tele quebec` | `tele quebec` |
| `|DE| Das Erste 4K` | `de das erste` | `das erste` |
| `HD` | `hd` (fallback: never empty) | `hd` |

Rules: NFKD, combining marks removed, `lowercase(Locale.ROOT)`. Quality tokens are removed only as whole words. `+` is kept (`+1` channels are different channels). Brackets and country prefixes are removed only for matching. A 2-letter prefix counts as a country prefix only when it is an ISO-3166 code or `uk`. These functions must not change after M1 ships without a key migration (§2.1.3): `forKey` feeds persisted stable keys.

### 3.2 StableKey derivation (WP-A, `StableKeyDeriverImpl`)

#### 3.2.1 Priority (first rule that yields a key wins)

| # | Rule | Condition | Key |
|---|---|---|---|
| 1 | XTREAM | `entry.xtreamStreamId != null` (Xtream API), **or** (M3U) `ctx.xtreamShaped` **and** `XtreamStreamUrl.parse(url, allowShort = true)` succeeds **and** its `(username, password)` equals `ctx.credentials` (reviewed 2026-10-13, B6: the long form alone matched `http://cdn/live/bbc/hd/1.m3u8` in the review probe) | LIVE/RADIO `xc:<id>`, MOVIE `xcm:<id>`, SERIES `xce:<id>` |
| 2 | TVG | `tvgId` non-null after junk filtering (§3.4 M15) | `tvg:<tvgId.trim().lowercase(ROOT)>|<forKey(name)>` |
| 3 | URL | `UrlKey.of(url, detectedCredentials)` non-null | `url:<key>` |
| 4 | NAME_GROUP | always | `ng:<forKey(name)>|<forGroup(groupTitle ?: "")>` (reviewed 2026-10-13, B5) |

Then **duplicates**: the differ counts occurrences per key (`MutableLongIntMap` keyed by `Hash64.of(key)`, verified on collision) in playlist order. The 2nd occurrence becomes `key~2`, the 3rd `key~3`, and so on. `duplicateKeys` in the report counts them. (Collapsing duplicates into one channel with several sources is the v1.1 multi-source feature, and the `stream_source` schema is ready for it.)

Rule 1 also applies to M3U playlists. An M3U exported by `get.php` therefore gets the same keys as the Xtream login for the same line, so "upgrade to Xtream API" keeps favourites.

#### 3.2.2 `UrlKey.of(url)` (host- and credential-agnostic)

1. Schemes `udp`, `rtp`, `igmp`: multicast is identified by group and port, so `udp/<host>:<port>` (the host is kept, `@` removed). `rtmp*`/`rtsp`: as http below.
2. `http(s)`: drop the scheme, userinfo, host, port and fragment.
3. Path: split on `/`, drop empty segments, and drop segments equal to the detected username or password. (reviewed 2026-10-13, B3) Also drop the two credential segments of **any** URL that `XtreamStreamUrl.parse(url, allowShort = true)` recognises, whatever the vote said. Otherwise `http://h/u/p/102` in a mixed-credential list keys as `url:p/102` and the password is persisted in `stable_key`, favourites and backups. Keep the **last two** remaining segments (one if only one exists). Normalise percent-escapes to upper case. Case is otherwise preserved.
4. Query: parse it, then drop keys (case-insensitive) in `{token, auth, key, sig, signature, expires, exp, e, st, hash, wmsauthsign, hdnts, hdnea, username, password, user, pass, u, p, session, sid, t, ts, time, nonce}`. Sort the remaining `k=v` pairs and append them as `?k=v&…`.
5. Return null (go to rule 4) when the path part is a single generic segment in `{index.m3u8, playlist.m3u8, mono.m3u8, video.m3u8, chunklist.m3u8, mpegts, live, stream, play, index.html}` with no surviving query, or when the URL cannot be parsed.

Examples: `http://lb3.x.tv:8080/hls/bbc1/index.m3u8?token=abc` gives `url:bbc1/index.m3u8`. `udp://@239.1.1.5:1234` gives `url:udp/239.1.1.5:1234`. `http://a.tv/play.php?id=55&token=zz` gives `url:play.php?id=55`.

#### 3.2.3 Group keys

Xtream: `xcat:<category_id>` (`xcat:none` for null, displayed as "Uncategorised"). M3U: `g:<forGroup(groupTitle)>`, or `g:` when there is no group (displayed as "Ungrouped"). RADIO channels use the LIVE group space (`channel_group.kind = 0`). (reviewed 2026-10-13, B5: was `forKey`, which strips quality tokens. "Sports HD"/"Sports SD" and "UK HD"/"UK SD" became one group, and `group_prefs` would have pinned that merged identity.)

#### 3.2.4 Credential templating (security, CLAUDE.md "Credentials via Tink")

- **Xtream API:** `XtreamCatalogDecoder` emits `{{base}}/live/{{u}}/{{p}}/<id>.{{ext}}`. M2's `StreamUrlResolver` substitutes `{{base}}` (rotation), `{{u}}`, `{{p}}` and `{{ext}}` (TS/HLS policy).
- **M3U:** pass 1 runs a vote over http(s) entries. `xtreamShaped = true` when ≥ 80 % of them parse as `XtreamStreamUrl` (long or short form) **and** one `(username, password)` pair accounts for ≥ 95 % of the parsed ones. The pair and the majority base go to `MergeSink.begin(…, DetectedCredentials)`, and WP-D stores them in the playlist's `PlaylistSecret` (`username`/`password`; the base is appended to `urls` if absent). In pass 2, every URL that parses with that pair has its two credential path segments replaced by `{{u}}`/`{{p}}`. Host, port, path and extension stay literal. Other URLs are stored as they are. (reviewed 2026-10-13, B3) The same substitution applies to `catchup-source` and to any attribute value that contains the pair (path segments, or `username=`/`password=` query values). For non-shaped lists, per-URL tokens and the credentials of minority pairs stay in `channel.url`. This is accepted residual risk and a lead decision in the review.
- The templates use double braces so they never collide with catch-up placeholders (`{utc}`). `ChannelRepository` never returns an expanded URL. Only M2's resolver expands, in memory.

### 3.3 Diff-merge semantics (WP-A engine, WP-D sink)

#### 3.3.1 Hashes

- `contentHash = Hash64` folded over, in this order: `kind`, `name`, `urlTemplate`, `groupKey`, `groupTitle`, `extraGroups`, `logoUrl`, `number`, `tvgId`, `tvgName`, `epgShiftMinutes`, `headers` (map), every `RawCatchup` field, `xtreamStreamId`, `containerExtension`, `adult`, `durationSec`, `kodiProps`, `vlcOpts`, `attributes`. Position is excluded.
- `urlHash = Hash64.of(urlTemplate)`, `nameHash = Hash64.of(name)` (for the "URLs changed" and "renamed" counters).

#### 3.3.2 Existing index (memory: about 34 bytes per existing row, so about 7 MB at 200k)

`ExistingChannels.forEach` streams rows (WP-D runs `SELECT id, stable_key, content_hash, url, name, sort_index, missing_since, missing_count FROM channel WHERE playlist_id = ?` over `useReaderConnection` and fills `urlHash = Hash64.of(url)` and `nameHash = Hash64.of(name)` while streaming; the hashes are not stored columns, and the strings are dropped immediately). The engine keeps:

- `MutableLongIntMap keyHash → slot` (`Hash64.of(stableKey)`). If two *existing* keys collide, both go into a small `HashMap<String, Int>` side table and the long map stores the sentinel `-2` for that hash.
- Parallel primitive arrays per slot: `id`, `contentHash`, `urlHash`, `nameHash`, `sortIndex`, `missingSince` (`Long.MIN_VALUE` = null), `missingCount`, and a `BitSet seen`.

No `String` is retained per existing row.

#### 3.3.3 Two passes

**Pass 1 (validate, no writes):** run `entries.produce` once. Count kinds, collect groups (`LinkedHashMap<groupKey, GroupRow>` in first-seen order, `adult` = group-name rule or a majority of adult entries), run the credential vote (§3.2.4), and count derived keys per rule. Then:

1. `entries == 0` → throw `NotASource(EMPTY)`.
2. Shrink guard (§3.3.5).
3. Otherwise call `sink.begin(groups, detected)`.

**Pass 2 (merge):** run `entries.produce` again. For each entry: derive the key, add the occurrence suffix, template the URL, compute the hashes, `sortIndex = entry.position`, and look up the slot.

| Existing slot | Content hash | Emitted | Counters |
|---|---|---|---|
| none | — | `insert` | `added` (+ first 10 names in `addedSample`) |
| found | differs | `ChannelUpdate(restored = missingSince != null)` | `updated`, `urlChanged` if url hash differs, `renamed` if name hash differs, `restored` |
| found | same, and (`sortIndex` differs or missing) | `ChannelTouch` | `moved` / `restored` |
| found | same, same position, not missing | nothing | `unchanged` |

When `inserts + updates + touches ≥ batchSize` (2,000): `gate.awaitClear()`, `cancel.throwIfCancelled()`, `sink.apply(batch)`. Each `apply` is **one write transaction** in WP-D.

**Finish:** for every slot not `seen`:

- not missing before → `newlyMissingIds`;
- missing before, and `now − missingSince ≥ graceMs` and `missingCount + 1 ≥ purgeAfterMissingRefreshes` → `purgeIds` (their keys → `purgedKeys`);
- otherwise → `stillMissingIds`.

(reviewed 2026-10-13, S7) **Automatic single-key migration.** During pass 2 the differ remembers each *inserted* entry's `urlHash → key`. Values are kept only while unique, and a second occurrence sets a sentinel. At finish, every *newly missing* slot whose `urlHash` maps to exactly one inserted key, used by no other missing slot, becomes a `KeyRemap(old, new, sameGroup)` in `MergeFinish.autoRemaps`. This catches the common single-channel case that the 70 % mass-remap prompt misses: the provider adds or fixes a `tvg-id`, so the key moves from `url:`/`ng:` to `tvg:`, or renames a `tvg:` channel. Tokenised URLs that change every refresh simply never match. The sink applies the remaps with the same `remapUserData` SQL (§3.3.6) inside the finish transaction and counts `keysMigrated`. The old row is then soft-deleted as usual.

`sink.finish(…)` is one transaction. It also sets `missing_since` on groups not in `groupKeysSeen`, clears it on seen ones, and deletes groups that are past grace with no channels. It updates `playlist.live_count`, `refresh_seq` and `last_success_at`, and **only here** `playlist.etag`/`last_modified` (reviewed 2026-10-13, S10: persisting them before a merge that then fails would make the next refresh a 304 that never retries the merge). It applies `autoRemaps`, writes `purged_key` tombstones and deletes tombstones for keys seen again, writes `refresh_log`, and computes `favouritesKept`/`favouritesOrphaned` with a join of `favourite` against non-missing channels. On any exception the differ calls `sink.abort(e)` (no-op except logging) and rethrows. Batches already applied stay applied: they are correct upserts, and nothing has been marked missing yet.

**Sort-index preservation:** `channel.sort_index` is always the provider position, and the refresh owns it. The user's own order lives in `channel_prefs.custom_sort_index` and `group_prefs.custom_sort_index`, which the refresh never touches. The browse query orders by custom index, then provider index (§2.1.4). Channel `number` follows `numbering_mode` at read time (`PROVIDER`: `number` else position+1; `PLAYLIST_ORDER`: position+1; `START_AT:n`: n + position), and `custom_number` overrides it.

#### 3.3.4 Soft delete and grace

A channel missing from a refresh gets `missing_since = now`, `missing_count = 1` and disappears from every UI query (`missing_since IS NULL` predicate). Its favourites and prefs remain. It is purged (row deleted, user data kept) only after **7 days and at least 2 consecutive refreshes** in which it was missing. If it reappears before that, `missing_since` is cleared and `restored` is counted. If it reappears after a purge, it is a new row with the same `stable_key`, so its user data reattaches automatically.

#### 3.3.5 Shrink guard

If `previousLiveCount ≥ 50` and pass 1 found `live + radio < 0.2 × previousLiveCount`, and `forceAcceptShrink` is false: throw `SuspiciousShrink(previous, now)` before any write. The report outcome is `FAILED` with `errorCode = "shrink"`. M5's text: "The playlist returned 12 channels instead of 2,140. Your channels were kept. [Accept anyway] [Retry]". *Accept* re-runs with `forceAcceptShrink = true`.

#### 3.3.6 Mass-remap prompt

`massRemapSuggested = true` when the playlist had at least 20 active (non-missing) channels before, `newlyMissing ≥ 0.7 × activeBefore`, and `added ≥ 0.7 × (live + radio)` (architecture review B3.5). (reviewed 2026-10-13, S18) All three counts are **LIVE+RADIO only**. `added` included VOD, so a provider adding 50k movies satisfied the third term on its own. Nothing is blocked. The old rows are soft-deleted as usual, so the user has the grace period to accept "Remap favourites by name".

`RemapPlannerImpl.plan(missing, added)`: index `added` by `forKey(name)`. For each missing candidate, take the added candidates with the same normalised name. If more than one, keep those whose `forKey(groupName)` is equal. Accept only when exactly one candidate remains and it has not been claimed already (one-to-one). WP-D's `ChannelRepository.remapUserData(playlistId, remaps)` then rewrites `favourite`, `channel_prefs`, `epg_mapping`, `reminder` and `stream_source` (both sides) in one transaction: `UPDATE OR IGNORE … SET stable_key = new WHERE stable_key = old`, after which leftover `old` rows (where the new key already had data) are deleted. The current row wins.

### 3.4 M3U grammar and edge cases (WP-A)

The parser is a state machine over lines: `pending = null` → `#EXTINF` opens `pending` → aux lines attach to the *next entry* → the first line that is not blank and does not start with `#` is the URL, which closes and emits the entry. Rules, numbered so tests can cite them:

| # | Case | Handling |
|---|---|---|
| M1 | UTF-8 BOM `EF BB BF` | Stripped. UTF-16 LE/BE BOM → `Utf16LineReader`. UTF-32 BOM → `NotASource(BINARY)` |
| M2 | Not a playlist | First non-whitespace bytes: `<tv` or `<?xml … <tv` → `WRONG_FORMAT` (an XMLTV URL given as a playlist); other `<` → `HTML_PAGE`; `{` or `[` → `WRONG_FORMAT` (JSON; probably an API URL); a NUL byte in the first 1 KB → `BINARY` |
| M3 | Missing `#EXTM3U` | Accepted. `hadHeader = false`, and `onHeader(M3uHeader())` is still called before the first entry |
| M4 | Line endings | LF, CRLF and lone CR all end a line. Lines are trimmed (ASCII whitespace and U+00A0). Blank lines are ignored. A trailing `\r` never reaches a URL |
| M5 | Huge lines (base64 logos) | Over `maxLineBytes` (64 KB): skipped to the next terminator without buffering, `LINE_TOO_LONG`. If it was an `#EXTINF`, the pending entry is dropped |
| M6 | Charset | §4.3 |
| M7 | `#EXTINF` | Directive matched case-insensitively. Duration = leading number (int or float; `-1`, `0` ok; `> 0` → `durationSec`). Attributes are lexed up to the **first comma outside quotes**, and the display name is the trimmed rest. An empty name falls back to `tvg-name`, then to the URL's last path segment (decoded, extension removed) |
| M8 | Attribute lexer | Key `[A-Za-z0-9_.:\-]+`, lower-cased. Values `"…"` (no escapes), `'…'`, unquoted (up to whitespace or the delimiting comma), or empty. A bare key gives `""`. An unterminated quote takes the value to the end of the attribute region, with `BAD_ATTRIBUTE`. For duplicate keys the first wins. Values over 4 KB are dropped (`data:` logos) |
| M9 | Pipe headers | Split at the **last** `|` whose remainder matches `^!?[A-Za-z0-9_\-]+=`. Split pairs on `&`, strip a leading `!`, percent-decode the value once (`+` stays literal), and canonicalise the name (M10). A malformed pair is ignored with `BAD_ATTRIBUTE` |
| M10 | Header precedence | pipe > `#EXTVLCOPT`/`#EXTHTTP`/`#KODIPROP …headers` > EXTINF attributes. (The playlist UA and app default are applied at play time by M2.) Canonical names: `user-agent`, `http-user-agent` → `User-Agent`; `referer`, `referrer`, `http-referrer`, `http-referer` → `Referer`; `origin`, `http-origin` → `Origin`; `cookie`, `http-cookie` → `Cookie`. Other names are kept as written and de-duplicated case-insensitively |
| M11 | `#EXTVLCOPT:k=v` | Every key goes into `vlcOpts`. The `http-*` keys above also become headers |
| M12 | `#KODIPROP:k=v` | Every key (lower-cased) goes into `kodiProps` (`inputstreamaddon` is aliased to `inputstream`). `inputstream.adaptive.stream_headers`, `common_headers` and `manifest_headers` are parsed as URL-encoded `a=b&c=d` into headers. DRM keys are stored for v1.1 and not interpreted |
| M13 | `#EXTHTTP:{json}` | Parsed with `Json.parseToJsonElement` (small). String values become headers. Invalid JSON → `BAD_ATTRIBUTE` |
| M14 | `#EXTGRP:` | Default (non-sticky): applies to the next entry only, and only if that entry has no `group-title`. With `extgrpSticky`: applies to all following entries until the next `#EXTGRP` (an empty one clears it). An explicit `group-title` wins for its own entry and does not reset the sticky value |
| M15 | `tvg-id` junk | After trimming, `""`, `none`, `null`, `nil`, `0`, `-`, `n/a`, `na`, `unknown`, `noepg`, `no-epg`, `no_epg`, `undefined` (case-insensitive) → null |
| M16 | Channel number | `tvg-chno`, `ch-number`, `channel-number`, `tvg-num` (first present). Accept `^\d{1,6}(\.0+)?$`, otherwise null |
| M17 | `tvg-shift` | Double (a decimal comma is accepted). `|v| ≤ 24` → hours; otherwise seconds. Rounded to whole minutes; a result outside ±24 h → null with `BAD_ATTRIBUTE`. The header-level value is stored in `M3uHeader` and applied at read time |
| M18 | Groups | `group-title` split on `;`: the first trimmed non-empty part is `groupTitle`, the rest are `extraGroups`. Empty → `#EXTGRP` (M14) → null |
| M19 | Kind | In order: `tvg-type` (live/movie/series/radio) → `radio="true"|"1"` → `XtreamStreamUrl` kind → path contains `/movie/` or `/series/` → extension in the VOD set `{mp4, mkv, avi, mov, wmv, flv, m4v, webm, mpg, mpeg, divx, 3gp}` → MOVIE (SERIES if the group or name matches `(?i)\b(series|tv shows?|season)\b|\bs\d{1,2}\s?e\d{1,3}\b`) → LIVE. The group-name heuristic never reclassifies a URL without a VOD extension |
| M20 | Schemes | `http`, `https`, `rtmp`, `rtmps`, `rtmpt`, `rtmpe`, `rtsp`, `udp`, `rtp`, `mms` are accepted. `udp://@239.x` is kept verbatim (the M2 udpxy relay rewrites it). `file:`, `content:`, `javascript:` and unknown schemes → entry skipped, `UNSUPPORTED_SCHEME` |
| M21 | Relative URLs | Resolved against `baseUrl` (RFC 3986). No base → skipped, `RELATIVE_URL_UNRESOLVED` |
| M22 | Logos | A relative `tvg-logo` with header `url-logo` → `urlLogo + value`, plus `.png` when the value has no extension (Kodi). Without `url-logo` → resolved against `baseUrl`. Inline `data:` logos over 4 KB are dropped (M8) |
| M23 | Orphans | An `#EXTINF` followed by another `#EXTINF` or EOF → dropped, `EXTINF_WITHOUT_URL`. A URL without `#EXTINF` → an entry named after the URL's last segment, `URL_WITHOUT_EXTINF` |
| M24 | HLS manifest | Any `#EXT-X-TARGETDURATION`, `#EXT-X-STREAM-INF`, `#EXT-X-MEDIA-SEQUENCE` or `#EXT-X-PLAYLIST-TYPE` **before the first `#EXTINF`** → `NotASource(HLS_MANIFEST)` (M5 offers "Add as a single channel"). After the first `#EXTINF`, `#EXT-X-*` lines are ignored |
| M25 | Header attributes | `x-tvg-url`, `url-tvg`, `tvg-url` split on `,` and whitespace, trimmed, de-duplicated → `epgUrls`. Also `tvg-shift`; `catchup`/`catchup-type`, `catchup-source`, `catchup-days`, `catchup-correction` → `M3uHeader.catchup`; `url-logo`, `refresh`, `max-conn`. Everything else goes into `attributes`. Several `#EXTM3U` lines (concatenated lists): attributes merge with the first value per key winning, and `epgUrls` is the union |
| M26 | Catch-up attributes | `catchup`/`catchup-type` → `type`; `catchup-source`; `catchup-days` (leading digits, e.g. `7d` → 7); `catchup-correction` (Double); `timeshift` (Int days); `tvg-rec` (Int); `catchup-time` (Long seconds) |
| M27 | Adult | `adult="1|true"`, `censored="1"`, or a non-empty `parent-code` → `adult = true` |
| M28 | Nested playlists | `type="playlist"` → skipped with `UNSUPPORTED_SCHEME` (not followed in v1) |
| M29 | Unknown `#` lines | Ignored (`#EXTIMG`, `#EXTALB`, `#EXTART`, `#PLAYLIST`, comments) |
| M30 | Every unknown EXTINF attribute | Kept in `attributes` (protocols §1.3: never drop data at parse time) |

### 3.5 XMLTV (WP-B)

#### 3.5.1 Time parsing without `java.time` per row (`XmltvTime.parseMinutes(s: String, assumed: OffsetResolver): Int`)

1. Read ASCII digits by index (no `substring`, no regex). Fewer than 8 digits → `BAD_TIME`, programme skipped. Fields `YYYY MM DD [hh [mm [ss]]]`; missing fields are 0. Validate the ranges (month 1–12, day 1–31, hour 0–23, minute 0–59, second 0–60).
2. Skip spaces. Offset forms: `±HHMM`, `±HH:MM`, `±HH` → minutes. `Z`, `UTC`, `GMT`, `UT` → 0. Abbreviations from a fixed table (`EST −300`, `EDT −240`, `CST −360`, `CDT −300`, `MST −420`, `MDT −360`, `PST −480`, `PDT −420`, `CET 60`, `CEST 120`, `BST 60`, `EET 120`, `EEST 180`, `MSK 180`). Anything else counts as missing.
3. `epochDay = daysFromCivil(y, m, d)` (Hinnant):
   ```
   y -= if (m <= 2) 1 else 0
   era = (if (y >= 0) y else y - 399) / 400
   yoe = y - era * 400
   doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
   doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
   return era * 146097 + doe - 719468
   ```
   `localMin = epochDay * 1440 + hh * 60 + mm` (seconds are floored away).
4. Offset known → `localMin − offsetMin`. Offset missing → `assumed.offsetMinutes(localMin)`. For UTC (the default) that is 0. For a named zone it is a per-source cache `IntIntMap(localHour → offset)`, filled on a miss through `ZoneRules.getOffset(LocalDateTime)` (gaps and overlaps follow java.time: the earlier offset in an overlap). `java.time` runs once per distinct local hour, never per row.

The result is an `Int` of epoch minutes. Stop and start are both floored, and a programme whose stop ≤ start after flooring is dropped (`badTimes`).

#### 3.5.2 `episode-num`

- `xmltv_ns`: `S.E.P`, each part optionally `n/total`, parts may be empty, all **zero-based**. `season = S+1`, `episode = E+1`, `part = P+1`. Label: `S{season} E{episode}`, plus ` (Part {p}/{total})` when a part is present. Example: `2.9.0/2` gives S3 E10 Part 1/2.
- `onscreen`: `(?i)s\s*(\d{1,3})\s*[ex.]\s*(\d{1,4})` → season and episode, else `(?i)\be(?:p(?:isode)?)?\.?\s*(\d{1,4})` → episode. The label is the raw text.
- `dd_progid`: `EP01234567.0012` gives `series_ref = "EP01234567"`. The `EP`/`SH`/`MV`/`SP` prefix is kept as written.
- `SxxExx` (system name): same as `onscreen`.
- Precedence for season/episode: `xmltv_ns` > `onscreen`/`SxxExx`. For the label: `onscreen` text if present, else the derived `S… E…`.

#### 3.5.3 Whitelist strategy and handler flow

```
for each source (priority order):
  stream = Compression.decompress(CountingSource(file))
  parser.parse(stream, session):
    <channel>   → session.onChannel: INSERT epg_channel (+ epg_channel_name rows), remember id in HashMap<String, Int>
    first <programme> (or end of document) →
        whitelist = resolver.whitelistFor(sourceId, channelsOfThisSource)   // WP-D matcher
    <programme start stop channel …> → session.acceptProgramme(channelAttr, start, stop):
        id = decision[channelAttr] ?: decide(channelAttr)    // HashMap<String,Int>, computed once per distinct attr
            decide: if whitelist.accepts(attr): row = byXmltvId[attr] ?: INSERT lazy epg_channel(display_name='') ; mark imported
                    else -1
        return id >= 0 && inWindow(start, stop)              // false → parser skips the subtree, no text built
    accepted </programme> → session.onProgramme(p): text dedup, split, INSERT OR REPLACE
```

- Every `<channel>` is stored, so the picker can list all of them (B3.3).
- **Window:** `from = nowMin − pastDays × 1440`, `to = nowMin + futureDays × 1440`. A programme is dropped if `stop` is known and `stop ≤ from`, or if `start ≥ to`. The importer counts `skippedWindow`.
- **Programmes for unknown channels** (no `<channel>` element) are accepted if `whitelist.accepts(id)`. That covers exact-id matches by `tvg-id`, which WP-D's whitelist includes at the id level, so sources without `<channel>` elements still work.
- **Late `<channel>` elements** (after programmes have started) are stored. Exact ids work through `accepts`. Name-only matches for them are found by WP-D's post-build matching, which sees them with `imported = 0` and enqueues a patch. B needs no second pass.
- Per-source decisions are cached by attribute string. The `String` that kxml allocates for the attribute is the only per-programme allocation on the skip path.

#### 3.5.4 Programme content

Children are read only for accepted programmes:

- `title`, `sub-title`, `desc` by `LangPicker` (the first element whose `lang` is in `preferredLanguages` order, else the first one). `desc` is truncated to `maxDescriptionChars` on a code-point boundary, and is null when `storeDescriptions = false`.
- `category`: up to 5, preferred language, de-duplicated.
- `icon@src`: first. `rating`: `system:value` of the first. `star-rating`: first value. `date`: the first 4 digits → `year`.
- `credits`: compact form, capped at 512 chars.
- Flags: `new`, `premiere`, `last-chance`, `previously-shown`, `subtitles` present; `video/quality` `HDTV` → `HD`, `UHD`/`4K` → `UHD`; non-standard `<live/>` or a category equal to `Live` → `LIVE`. `catchup-id` present → `HAS_CATCHUP_ID`, and the value goes in `programme.catchup_id`.
- Text dedup: `hash = Hash64` over (title, subtitle, description, categories, episode_label, season, episode, icon, rating, star_rating, year, credits, series_ref). `MutableLongIntMap hash → text_id`; on a miss, `INSERT INTO epg_text`. The map is seeded empty for a rebuild, and from `epg_text_hash` on demand for patch/in-place.

#### 3.5.5 Insert, split and repair

- `stop` known and `stop − start > 360` → split into 360-minute segments with the same `text_id`. Each segment but the last gets `CONTINUED`, and each but the first gets `CONTINUATION` (`split` counted).
- `stop` missing → `end_min = start + 1` placeholder, with `STOP_INFERRED`.
- `INSERT OR REPLACE INTO programme(…)`: for a duplicate `(channel, start)`, the later row wins.
- Commit every **10,000** programme rows: `gate.awaitClear()`, `cancel.throwIfCancelled()`, `progress(…)`.
- **Repair pass** after each source. For every channel of the source with `imported = 1`, read `SELECT start_min, end_min, flags FROM programme WHERE epg_channel_id = ? ORDER BY start_min` (PK order, no sort) into reusable `IntArray`s, then for each row *i* with next start *n*:
  - (reviewed 2026-10-13, S12) first, if the **next** row is a `CONTINUATION` segment that starts inside row *i*'s unclipped span while row *i* is a real (non-continuation) programme, delete that continuation row and re-evaluate with the following one. Real programmes always win over the 6 h segments of a long placeholder. Without this, the 06:00/12:00/18:00 segments of a 30 h "Off air" row would clip the real programmes that follow;
  - `STOP_INFERRED`: `end = if (n exists) min(n, start + 360) else min(start + 60, windowTo)`;
  - else if `end > n`: `end = n`, `|= CLIPPED` (`clipped` counted);
  - `end ≤ start` → delete the row;
  - `CONTINUED` set but the next row is not a `CONTINUATION` → clear `CONTINUED`.

  Apply the changes with prepared `UPDATE`/`DELETE` statements. Then write `programme_count`, `max_len_min`, `first_start_min` and `last_end_min` to `epg_channel`. Memory is bounded by the largest single channel (a few thousand rows).
- **Interleaved files** (programmes ordered by time across channels rather than grouped by channel) are correct by construction: rows land in the clustered PK in any order, and the repair pass reads them back in PK order. They are slower only through B-tree page churn, which the 16 MB build cache absorbs.

#### 3.5.6 Truncated or malformed files

`XmlPullParserException` or `EOFException` mid-file:

- If the compressed bytes consumed are at least **90 %** of the file size, the source is accepted as truncated (`truncated = true`, `XML_RECOVERED` warning, repair runs).
- Otherwise the source has failed. In a rebuild, the `.building` file is deleted and the report returns `failedSourceId`. WP-D then retries the build once using that source's last-good cached file, or excludes the source if it has none.

#### 3.5.7 Parser configuration

`XmlPullParser` comes from the injected factory: `Xml::newPullParser` on Android, `::KXmlParser` in JVM tests. (reviewed 2026-10-13, S4) The factory only constructs, and `XmltvParser` applies **every** setting itself, in this order, because the two parsers start from different defaults. Android's `Xml.newPullParser()` turns on `FEATURE_PROCESS_DOCDECL` and namespaces. kxml2 2.3.0 rejects DOCDECL (review probe: "unsupported feature"). Android's KXmlParser refuses `defineEntityReplacementText` while DOCDECL processing is on. In relaxed mode, kxml2 silently **drops** unknown entities (`A&nbsp;B` → `AB` in the probe):

1. `setFeature(FEATURE_PROCESS_DOCDECL, false)` in try/catch (kxml2 throws; Android needs it off for step 4). DOCTYPE is then skipped and no external DTD is fetched.
2. `setFeature(FEATURE_PROCESS_NAMESPACES, false)`.
3. The relaxed feature `http://xmlpull.org/v1/doc/features.html#relaxed`, in try/catch. kxml2 2.3.0 accepts it (verified), and it tolerates stray `&` and unknown entities.
4. `setInput(inputStream, null)`, so the parser detects the encoding from the BOM or the XML declaration (verified for `ISO-8859-1` on kxml2). Then, in try/catch, `defineEntityReplacementText` for `nbsp` (U+00A0), `ndash`, `mdash`, `hellip`, `laquo`, `raquo`, `copy`, `reg`, `euro`, `apos`, so these become characters instead of being dropped.

Instrumented test I-8 parses `entities.xml`, `latin1.xml` and `basic.xml` with `Xml.newPullParser()` on the emulator and asserts the same `XmltvProgramme` values as the JVM test.
- Skipping uses depth counting with `next()` and never calls `getText()`. `nextText()` is called only on text elements of accepted programmes.

### 3.6 EPG matcher (WP-D, `:core` `app.thebox.core.match`)

#### 3.6.1 Inputs and outputs

Subjects are the non-missing LIVE/RADIO channels of the playlists the source applies to. Overrides come from `epg_mapping`. Settings come from `DataSettings`. `matchSource` streams the source's `EpgChannelInfo` once and returns one `MatchResult` per subject.

#### 3.6.2 Stages (lower rank wins; within a rank, prefer a channel with `programmeCount > 0`, then the first seen)

| Rank | Method | Rule | Score | Applied when level ≥ |
|---|---|---|---|---|
| — | `OVERRIDE` | `epg_mapping` row for this subject and source | 1.0 | always |
| 0 | `ID_EXACT` | `tvgId == xmltv_id` | 1.0 | EXACT_ONLY |
| 1 | `ID_CASE_INSENSITIVE` | equal after `lowercase(ROOT)` (when `caseInsensitiveIds`) | 1.0 | EXACT_ONLY |
| 2 | `ID_BASE` | iptv-org feeds: `substringBefore('@')` equal (case-insensitive), with at least one side containing `@` | 0.99 | EXACT_ONLY |
| 3 | `TVG_NAME` | `forMatch(tvgName.replace('_',' '))` equals `forMatch` of any display-name | 0.98 | EXACT_AND_NAMES |
| 4 | `DISPLAY_NAME` | `forMatch(name)` equals `forMatch` of any display-name | 0.97 | EXACT_AND_NAMES |
| 5 | `FUZZY` | similarity (§3.6.3) of `forMatch(name)` (and of `tvgName`) against each display-name | 0–1 | SUGGEST_FUZZY |

(reviewed 2026-10-13, S11) **Name stages must be unambiguous.** `forMatch` drops brackets and country prefixes, so "HBO (East)", "HBO (West)" and "HBO" all become `hbo`, and "UK: BBC One" equals every "BBC One" in a multi-country EPG. Ranks 3 and 4 therefore compare `forKey` forms first and fall back to `forMatch` equality. A rank-3/4 hit is `AUTO` only when the matched normalised name points to **exactly one** `xmltv_id` in this source, after dropping candidates whose country hint conflicts with the subject's. Otherwise all the candidates become `SUGGESTED` (score 0.97/0.98, best first). The "prefer `programmeCount > 0`, then first seen" tie-break applies only among candidates with the same `xmltv_id` base (iptv-org feeds).

Outcome per subject and source:

- rank ≤ 2, or rank 3–4 and unambiguous (above) → `AUTO`;
- otherwise, with the best fuzzy score *s* and the runner-up *s2*:
  - `AUTO` (`FUZZY`) only if the level is `AUTO_FUZZY`, `s ≥ 0.92`, `s − s2 ≥ 0.02`, and there is no country conflict;
  - else `SUGGESTED` if `s ≥ 0.75` (best plus up to 3 alternatives);
  - else no match.

Fuzzy matches below the auto rules are **never applied** (protocols §2.5, product §4.3).

#### 3.6.3 Similarity

```
sim(a, b):                                  // a, b = forMatch strings
  if digitSet(a) != digitSet(b): return 0   // "sky sports 1" vs "sky sports 2", "+1" channels
  s1 = jaroWinkler(compact(a), compact(b), p = 0.1, maxPrefix = 4)
  s2 = tokenSetRatio(tokens(a), tokens(b))
  s  = max(s1, s2)
  if hintA != null && hintB != null && hintA != hintB: s *= 0.85
  return s
tokenSetRatio(A, B):                        // fuzzywuzzy definition
  I = sorted(A ∩ B).join(" "); RA = (I + sorted(A − B)).trim(); RB = (I + sorted(B − A)).trim()
  return if (I.isEmpty()) ratio(RA, RB) else max(ratio(I, RA), ratio(I, RB), ratio(RA, RB))
ratio(x, y) = 1 − levenshtein(x, y) / max(|x|, |y|)
digitSet(s) = set of maximal digit runs, plus "+1"-style tokens
```

The country hint comes from `countryHint(name, tvgId)` for the subject and from `countryHint(displayName, xmltvId)` for the EPG channel.

#### 3.6.4 Blocking, so fuzzy matching stays near-linear

`prepare(subjects)` builds:

- `HashMap`s for exact, case-insensitive and base ids, and for normalised `tvgName` and `name`, all mapping to subject indices (`IntArray` lists);
- an inverted `TokenIndex` over the subjects' `forMatch` tokens. Tokens present in more than `max(50, 2 %)` of subjects are "common" and are not used for blocking.

For each EPG display-name (at most 3 per channel), the candidate subjects are the union of the postings of its non-common tokens (or of its rarest token if all are common), capped at 200, excluding subjects that already have a rank ≤ 4 hit in this source. Each subject keeps a top-(k+1) list of `(score, ref)` in small primitive arrays.

Budget: 20k subjects × 50k EPG channels in **≤ 3 s on the JVM**, with ≤ 8 MB retained.

`suggest(subject, channels, limit)` (the S13 picker) scores one subject against all EPG channels of all sources by brute force (≈ 50 ms).

#### 3.6.5 Whitelist and persistence (WP-D `MatcherWhitelistResolver`)

`whitelistFor(sourceId, channels)` loads the subjects (one query), runs `matchSource`, writes the `epg_match` rows for that source (replace per subject, transactions of 2,000 subjects, `inputs_hash = Hash64(tvgId, tvgName, name, settings.level, MATCHER_VERSION = 1)`), and returns:

```
accepts(id) = id ∈ autoIds(source) ∪ overrideIds(source) ∪ subjectTvgIds
           || (caseInsensitive && id.lowercase() ∈ subjectTvgIdsLower)
           || id.substringBefore('@').lowercase() ∈ subjectTvgIdBasesLower
```

After a build, WP-D:

1. sets `epg_match.has_data` from `EpgReader.forEachChannel()` programme counts;
2. re-runs matching for the stage-3/4 names of late channels;
3. enqueues `epg-patch` for every `AUTO` or override ref whose EPG channel has `imported = 0`.

After a **playlist** refresh, it matches only subjects whose `inputs_hash` changed or which are new, against the current generation, and patches as above. A new manual override triggers a patch for that ref only.

### 3.7 Xtream lenient decoding (WP-C)

#### 3.7.1 Transport and sniffing

Every response is streamed to a file (`XtreamApi.download`) and decoded from a `FileInputStream`:

1. Strip a UTF-8 BOM and skip whitespace.
2. Look at the first byte:
   - `[` → list;
   - `{` → object (a list endpoint returning `{"0":{…},"1":{…}}` is decoded as a `JsonObject` and its `values` are used);
   - `<` → `NotASource(HTML_PAGE)` (on login: `Auth(UNKNOWN)` with an "expired page?" hint);
   - empty → `NotASource(EMPTY)` (on login: `Auth(BAD_CREDENTIALS)`).
3. `Content-Type` is ignored.

Lists are decoded with `Json.decodeToSequence(stream, JsonElement.serializer(), DecodeSequenceMode.ARRAY_WRAPPED)` and mapped per element. **One `JsonObject` is alive per item.** The configuration is `Json { ignoreUnknownKeys = true; isLenient = true; allowSpecialFloatingPointValues = true }`.

(reviewed 2026-10-13, S5) **The object form streams too.** Decoding `{"0":{…},"1":{…}}` as one `JsonObject` keeps the whole tree alive, roughly 100 MB of `JsonElement`s for a 20k-stream panel and an OOM on a stick. The object form goes through `Json.decodeFromStream(MapValuesStreamer(consumer), stream)`. This is a small custom `DeserializationStrategy<Unit>` whose descriptor is a map of `String` to `JsonElement`. It loops `decodeElementIndex`, decodes the key, decodes the value with `JsonElement.serializer()`, hands the value to the consumer and keeps nothing. The test fixture `live_streams_object.json` runs under the `perfTest` heap cap with 20k generated entries.

#### 3.7.2 Lenient accessors (`LenientJson`)

| Accessor | Accepts | Absent / null |
|---|---|---|
| `str(o, k)` | string (trimmed); number or bool → `content` | `""`, `"null"`, JSON null, array, object |
| `long(o, k)` | number (integral, or a float with an integral value); numeric string (`"12"`, `"12.0"`); bool → 1/0 | `""`, non-numeric, null |
| `int(o, k)` | `long` within the Int range | same |
| `bool(o, k)` | `true`/`false`; numbers (≠ 0 → true); strings `1/true/yes/y/on`, `0/false/no/n/off` (case-insensitive) | `""`, null, anything else |
| `double(o, k)` | number; numeric string (decimal comma accepted) | same |
| `strList(o, k)` | array → mapped with `str`; string → JSON array if it starts with `[`, else split on `,`/`;`; object → values | empty list |
| `obj(o, k)` | object; `[]` (an empty PHP array) → empty object | null |
| `objOrArrayValues(o, k)` | object → values; array → elements | empty |

`exp_date` `null`, `"0"` or `""` → unlimited. `category_id` null → `"none"`.

#### 3.7.3 Base64-or-plain (EPG `title`/`description`)

Decided **per response**. Take the first ≤ 5 non-empty titles. A title is decodable when it matches `^[A-Za-z0-9+/\r\n]+={0,2}$`, its length without whitespace is a multiple of 4, `kotlin.io.encoding.Base64.Default.decode` succeeds, the result is valid UTF-8 (`CharsetDecoder` with `REPORT`), and at least 90 % of the decoded code points are not control characters (`\t\n\r` allowed). If a majority are decodable, the response is in base64 mode: every field is decoded, falling back to the plain value per field on failure. Otherwise every field is plain. `kotlin.io.encoding.Base64` is pure Kotlin and runs on API 25. `java.util.Base64` is API 26+ and must not be used.

#### 3.7.4 Mapping

- **Live → `ParsedChannel`:**
  - `stream_id` is required (otherwise skip with a warning);
  - `name` (fallback `"Stream <id>"`);
  - kind `RADIO` if `stream_type` contains `radio`, else `LIVE`;
  - URL template `{{base}}/live/{{u}}/{{p}}/<id>.{{ext}}`;
  - group: the name of `category_id` (`"Uncategorised"` when missing); `xtreamCategoryId`; `extraGroups` from XUI `category_ids`;
  - `tvgId = epg_channel_id` (junk-filtered with M15); `logoUrl = stream_icon`; `number = num`;
  - catch-up: `xtreamTvArchive = bool(tv_archive)`, `xtreamArchiveDays = int(tv_archive_duration)`;
  - `adult = bool(is_adult)`;
  - `attributes`: `direct_source` (when non-empty), `custom_sid`, `added`, `thumbnail`;
  - `position` = array order.
- **Account:**
  - `user_info` missing, or `auth` false or 0 → `Auth(reason)`, where reason comes from `status` (`Expired` → EXPIRED, `Banned` → BANNED, `Disabled` → DISABLED, otherwise BAD_CREDENTIALS);
  - otherwise `XtreamAccountInfo`, with `status` mapped case-insensitively and `allowed_output_formats` through `strList`.
- **Series info:**
  - `episodes` may be an object (season key → array), an array of arrays (index + 1 = season unless `episode.season` is set), or a flat array (grouped by `int(season) ?: 1`);
  - when `seasons` is empty, seasons are derived from the episode keys;
  - `info` may be `[]` (`obj`).
- **VOD info:** `info`/`movie_data` through `obj`; `backdrop_path` through `strList`; `duration_secs`, else `duration` parsed as `HH:MM:SS`.
- **Series list:** `releaseDate ?: release_date`, `backdrop_path` through `strList`.
- **EPG listings:** `title`/`description` per §3.7.3. `start_timestamp`/`stop_timestamp` (UTC) preferred. Otherwise `start`/`end` as `yyyy-MM-dd HH:mm:ss` in `timeBase.effectiveTokenZone ?: serverOffset ?: UTC` (UTC adds a warning). `now_playing`, `has_archive` through `bool`.

#### 3.7.5 URL building (`XtreamUrls`)

- **Base normalisation:** trim, add `http://` when there is no scheme, drop the trailing `/` and any path after `host:port` unless it ends before `player_api.php`/`get.php` (`XtreamLoginUrl` strips those).
- **Path segments** (`username`, `password`): percent-encode everything outside RFC 3986 `pchar` (`/ ? # [ ] % space` and non-ASCII, as UTF-8).
- **Query values:** encode everything outside the unreserved set.
- **Templates:**
  - `player_api.php?username=&password=[&action=][&k=v]`;
  - `get_series_info` sends `series_id=`, and `XtreamClient` retries with `series=` when the result is empty;
  - `get.php?username=&password=&type=m3u_plus&output=ts|m3u8`, `xmltv.php?username=&password=`;
  - live, movie and episode paths as in protocols §3.3; timeshift path and php in §3.8.5.

#### 3.7.6 Client behaviour (`XtreamClient`, `:data`)

- **Clients:** OkHttp clients from WP-D's `HttpModule`. `@ApiClient`: connect 10 s, read 60 s. `@DownloadClient`: read 120 s. Redirects followed. `User-Agent` = playlist UA, else the app default. Extra headers are added.
- **Concurrency:** at most **3** concurrent requests per playlist (`Semaphore` in a `ConcurrentHashMap<Long, Semaphore>`).
- **Base rotation:** start at `preferredBaseIndex`. On `IOException` (DNS, connect, timeout, reset), 5xx or 520–524, move to the next base. When every base has failed → `Network`/`Http`. `XtreamLogin.baseIndexUsed` is persisted by WP-D.
- **429:** back off 2, 4 and 8 s (honour `Retry-After` up to 30 s), then `Http(429)`.
- **Login status mapping:** 401 or 513 → `Auth(BAD_CREDENTIALS)`. 403 → `Http(403)` (the UI hints at the User-Agent setting). 200 with `auth=0`, `[]` or an empty body → `Auth`. HTML → see §3.7.1.
- **Cancellation:** downloads stream to `dest.part` and rename on success. Coroutine cancellation cancels the `Call` (`suspendCancellableCoroutine` + `call.cancel()`).
- **Time measurement:** `measuredAtDeviceMs` is taken when the response headers arrive.
- **Logging:** every log line goes through `BoxLog` (redacted), and the client calls `Redact.registerSecret(username/password)` before the first request.

### 3.8 Catch-up (WP-C)

#### 3.8.1 Mode chain (`CatchupResolverImpl`, protocols §4.1 and §4.5)

```
setting Off            → OFF
setting Forced(m)      → m   (every channel of the playlist; days from §3.8.2)
setting Auto:
  a. raw.type recognised by fromM3uAttribute(…) != OFF          → that mode
     (unrecognised non-empty values fall through)
  b. raw.timeshiftDays > 0 || raw.tvgRecDays > 0                → SHIFT   (Kodi: timeshift/tvg-rec force SIPTV)
  c. raw.xtreamTvArchive == true                                 → XC
     raw.xtreamTvArchive == false                                → OFF     (stop: the panel said no)
  d. header.type recognised                                      → that mode (playlist-wide default)
  e. (raw.source ?: header.source) != null                       → DEFAULT
  f. defaults.xtreamShaped && XtreamStreamUrl.parse(url, allowShort = true) on the template
       && (raw.days > 0 || raw.catchupTimeSec > 0)               → XC
     (reviewed 2026-10-13, B6: gated on the playlist vote; the URL regex alone matches non-Xtream CDN paths)
  g. otherwise                                                   → OFF
post-rules:
  DEFAULT/APPEND with no source (raw.source ?: header.source)    → SHIFT (diagnostic "no catchup-source")
  source = raw.source ?: header.source
  correctionHours = raw.correctionHours   (channel attr; overrides TimeBase.correctionHours)
```

#### 3.8.2 Archive-days fallback chain (M0 L11)

```
days, src =
  defaults.daysOverride                      → PLAYLIST_OVERRIDE
  raw.days (> 0)                             → CATCHUP_DAYS
  raw.timeshiftDays (> 0)                    → TIMESHIFT
  raw.tvgRecDays (> 0)                       → TVG_REC
  ceil(raw.catchupTimeSec / 86400) (> 0)     → CATCHUP_TIME
  raw.xtreamArchiveDays (> 0)                → XTREAM_DURATION
  header.days (> 0)                          → PLAYLIST_HEADER
  defaults.defaultDays (3)                   → DEFAULT        // e.g. tv_archive=1 with no duration: not "off"
days = days.coerceIn(1, 30) when mode != OFF; 0 when OFF
```

#### 3.8.3 `TimeBaseFactory`

- `serverZone`: `server_info.timezone`, accepted only if `ZoneId.of` succeeds, the result is a `ZoneRegion` (not an offset id such as `UTC+3`), and the id contains `/` or is `UTC`/`Etc/UTC`.
- `serverOffset`: `TimeBase.offsetFromServerInfo(timeNow, timestampNow)` when both are present (`timeNow` with `' '` replaced by `'T'`).
- If both exist and `serverZone`'s offset *now* differs from `serverOffset`, the device tzdata is stale or the panel is misconfigured: set `serverZone = null` (use the fixed, freshly measured offset) and log a diagnostic (B5.2). The login runs on every refresh, so the fixed offset stays current.
- `clockSkew = timestampNow − measuredAtDeviceMs/1000`, used only if `|skew| < 24 h` (otherwise 0 plus a diagnostic).
- `tokenZone` from the setting: **Auto → null for every playlist type**. Utc → UTC. Device → `ZoneId.systemDefault()`, resolved when the TimeBase is built. Server → null. Fixed(id) → `ZoneId.of(id)`.
- `templateZone` (reviewed 2026-10-13, blocker B1): Auto → `ZoneOffset.UTC` for M3U playlists (product §4.2: "M3U `{Y}`: UTC"), null for Xtream. The earlier rule set `tokenZone = UTC` for M3U. Because `effectiveTokenZone = tokenZone ?: serverZone ?: …`, that made the `xc` timeshift segment UTC on every Xtream-shaped M3U playlist (the common `get.php` paste), breaking the "panel zone" rule. Now `xc` always reads `effectiveTokenZone`, and template tokens read `effectiveTemplateZone`.
- **Xtream-shaped M3U playlists** (pass 1 found `DetectedCredentials`) are built with `fromAccount(M3U_URL, info, …)`. `PlaylistRefreshWorker` logs in once per refresh with the detected pair and base (§3.10) and stores `server_zone`/`server_offset_sec`/`clock_skew_sec`. A failed login keeps the previous values. With no values ever, `xc` falls back to UTC with the diagnostic below.
- `correctionHours = playlist.catchup_correction_h ?: header.correctionHours ?: 0.0`. Padding comes from the playlist (60 s / 300 s defaults).
- When `effectiveTokenZone` is null for an `xc` build, the builder uses UTC and returns a diagnostic ("panel time zone unknown, set it in playlist settings").

#### 3.8.4 Placeholder table (`TemplateExpander`)

Definitions:

- `S = timeBase.archiveStart(programmeStart, catchup.correctionHours)`;
- `E = timeBase.archiveStop(programmeStop, …)`, or `N` when open-ended;
- `N = deviceNow + clockSkew`;
- `D = E − S` in seconds;
- `Z = effectiveTemplateZone ?: UTC` for the template tokens below. The `xc` path/php segment uses `effectiveTokenZone ?: UTC` (§3.8.5). (reviewed 2026-10-13, B1)

Programme times are always **raw** (unshifted).

| Token(s) | Value |
|---|---|
| `{utc}` `${start}` `{start}` | S, unix seconds |
| `{utcend}` `${end}` `{end}` | E, unix seconds |
| `{lutc}` `${now}` `${timestamp}` `{timestamp}` | N, unix seconds |
| `{duration}` `${duration}` | D |
| `{duration:N}` | D / N (integer division; `{duration:60}` = minutes) |
| `{offset:N}` `${offset}` | (N − S) / N, positive "seconds ago" (Kodi); `${offset}` = N − S |
| `{Y}` `{m}` `{d}` `{H}` `{M}` `{S}` | S in Z, zero-padded (4, 2, 2, 2, 2, 2) |
| `{utc:FMT}` `${start:FMT}` | S in Z, where `FMT` letters `Y m d H M S` are replaced and every other character is literal |
| `{utcend:FMT}` `${end:FMT}` `{lutc:FMT}` `${now:FMT}` | E or N, same rule |
| `${start-iso}` `${end-iso}` | ISO-8601 with offset, in Z (`2026-10-13T19:30:00+01:00`) |
| `{catchup-id}` | `programme.catchup_id`, percent-encoded as a query value |

Unknown `{…}` tokens are left as they are, with a diagnostic. Expansion is single-pass: substituted values are never re-scanned.

#### 3.8.5 Per-mode builds

| Mode / variant | URL | `nativeSeekRange` |
|---|---|---|
| `xc` / `xc-path` | `{base}/timeshift/{u}/{p}/{⌈D/60⌉}/{S in effectiveTokenZone (panel zone): yyyy-MM-dd:HH-mm}/{id}.{ext}` (reviewed 2026-10-13, B1: never `templateZone`) | `[S, E]` when `ext = m3u8`, else null |
| `xc` / `xc-php` | `{base}/streaming/timeshift.php?username={u}&password={p}&stream={id}&start={yyyy-MM-dd:HH-mm}&duration={⌈D/60⌉}` | null |
| `xc` / `xc-path-sec` | as `xc-path` with `yyyy-MM-dd:HH-mm-ss` | as `xc-path` |
| `default` | `expand(source)`. A relative source is resolved against the live URL | null |
| `append` | `liveUrl + expand(source)`. A leading `?` becomes `&` when `liveUrl` already has a query | null |
| `shift` | `liveUrl + ("&" if liveUrl has "?" else "?") + "utc={S}&lutc={N}"` | null |
| `flussonic*` | §3.8.6 | see there |
| `vod` | `expand(source)`; `catchupId` required, else `Unsupported("no catchup-id")` | `[S, E]` |

- `xc` gets `{base}/{u}/{p}/{id}` from `target.xtream`. For Xtream-shaped M3U channels, WP-D's mapper fills `target.xtream` by parsing the expanded live URL with `XtreamStreamUrl`. `ext` = `target.xtream.extension` (`m3u8` only when the playlist's output is HLS and `allowed_output_formats` contains it; otherwise `ts`).
- **Open-ended** windows (pause-live resume, "watch from here"): `E = N` and `D = min(N − S, 180 min)` for `xc` (B5.1: one request should not stop at a programme boundary).
- **An absolute `catchup-source` overrides the derivation** for `xc`, `shift` and `flussonic*` (Kodi behaviour; the fake panel's ch4 relies on it). `variants()` then returns `["default", <the mode's own variants…>]`.
- Headers: `target.headers` are passed through unchanged.
- **Errors:** `CatchupBuild.Unsupported(reason)` covers: mode OFF; `xc` without `target.xtream`; a Flussonic live URL that does not parse; `vod` without an id; a programme start older than `days`; a programme start in the future. (reviewed 2026-10-13, S17) The depth check uses the corrected start **without padding** (`programmeStart − correction < N − days × 86400`). When only the padding crosses the window edge, `S` is clamped to `N − days × 86400 + 60 s` instead of rejecting a programme that is inside the archive.

#### 3.8.6 Flussonic (incl. Kodi conversions)

Parse the live URL with Kodi's regex `^(https?://[^/]+)/(.*)/([^/]*)(mpegts|\.m3u8)(\?.+=.+)?$`, giving `host = g1`, `stream = g2`, `name = g3` (`index`, `video`, `mono`, or `""` for `/mpegts`), `container = g4`, `query = g5 ?: ""`.

Container: `FLUSSONIC` takes it from `g4` (`mpegts` → TS, `.m3u8` → HLS); `FLUSSONIC_HLS` → HLS; `FLUSSONIC_TS` → TS. HLS type = `name` if it is in `{index, video, mono}`, else `index`.

| Variant | HLS | TS | Seek |
|---|---|---|---|
| `flussonic-range` (default; fixed range) | `{host}/{stream}/{type}-{S}-{D}.m3u8{query}` | `{host}/{stream}/archive-{S}-{D}.ts{query}` | `[S, E]` (HLS VOD; TS with Range) |
| `flussonic-range`, open-ended | `{host}/{stream}/{type}-{S}-now.m3u8{query}` | `{host}/{stream}/timeshift_abs-{S}.ts{query}` | null |
| `flussonic-abs` | `{host}/{stream}/timeshift_abs-{S}.m3u8{query}` | `{host}/{stream}/timeshift_abs-{S}.ts{query}` | null |
| `flussonic-kodi` (pvr.iptvsimple conversions) | `index.m3u8` → `{host}/{stream}/timeshift_rel-{N−S}.m3u8{query}`; `mono`/`video` → `{host}/{stream}/{name}-timeshift_rel-{N−S}.m3u8{query}` | `mpegts` → `{host}/{stream}/timeshift_abs-{S}.ts{query}` | null |

`S` and `D` are unix UTC seconds. The query (token) is always preserved.

#### 3.8.7 Variant (probe) order

| Mode | `variants()` |
|---|---|
| `xc` | `[winner?] + xc-path, xc-php, xc-path-sec` (B5.2) |
| `flussonic*` | `[winner?] + flussonic-range, flussonic-abs, flussonic-kodi` |
| `default`, `append`, `vod` | single variant |
| `shift` | `[shift]`, plus `xc-path, xc-php` when `target.xtream != null` (reviewed 2026-10-13, S17: `timeshift=`/`tvg-rec=` force SIPTV `shift` (Kodi), which an Xtream panel cannot serve, and the probe otherwise never leaves the mode) |

`winner` = `playlist.catchup_probe_winner` when it belongs to the same mode. M4 probes (404 or empty body → next) and persists the winner via `PlaylistRepository.setProbeWinner`.

### 3.9 Credential store (WP-D, `TinkCredentialStore`)

- Tink `AeadConfig.register()`. The keyset comes from `AndroidKeysetManager.Builder().withSharedPref(ctx, "credentials_keyset", "thebox_keysets").withKeyTemplate(KeyTemplates.get("AES256_GCM")).withMasterKeyUri("android-keystore://thebox_credentials_master").build().keysetHandle`. The primitive is `keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)`. (reviewed 2026-10-13: every name verified against `tink-android-1.23.0.jar`. `withSharedPref` declares `IOException`, and `build()` declares `GeneralSecurityException, IOException`. The design was missing `.keysetHandle`.)
- `put`: `ciphertext = aead.encrypt(Json.encodeToByteArray(secret), ref.toByteArray())`. Upsert `credential`. `Redact.registerSecret` for username, password and secret header values.
- `get`: decrypt, then register the secrets. Failure modes:
  - (reviewed 2026-10-13, S16) **Permanent** loss only: `KeyPermanentlyInvalidatedException`, `UnrecoverableKeyException`, or the keyset itself failing to decrypt with `AEADBadTagException` → delete the keyset prefs and **every** `credential` row (they can never be decrypted again), set `playlist.needs_reauth = 1` for all playlists, return `Err(Credentials(KEY_INVALIDATED))`. M5 shows "Re-enter the password" (architecture review C4).
  - **Anything else** from building the keyset manager or the primitive (`KeyStoreException`, other `GeneralSecurityException`, `ProviderException`, `IOException`). These happen transiently on Fire OS right after boot. Retry once after 1 s, then return `Err(Credentials(UNAVAILABLE))` with **nothing deleted**. Workers map it to `Result.retry()`. An earlier draft wiped every login on any `GeneralSecurityException`.
  - Bad tag on one row → `Err(Credentials(CORRUPT))` for that ref.
- If `AndroidKeysetManager.isUsingKeystore()` is false (Fire OS Keystore flakiness), store the fact in diagnostics. Encryption still works, but the keyset is only as safe as app-private storage.
- All crypto runs on the IO dispatcher. Results are not cached beyond the repository's in-memory `Map<ref, PlaylistSecret>`, which is cleared on `TRIM_MEMORY_COMPLETE`.

### 3.10 Workers, scheduling, Hilt (WP-D)

| Unique work name | Type / policy | Worker | Constraints | Notes |
|---|---|---|---|---|
| `playlist-periodic-<id>` | Periodic, `refresh_interval_h`, `UPDATE` | `PlaylistRefreshWorker` | CONNECTED, storageNotLow | not scheduled when the interval is 0 |
| `playlist-refresh-<id>` | One-time; `KEEP` (app start), `REPLACE` (manual or onboarding) | `PlaylistRefreshWorker` | CONNECTED | expedited for manual/onboarding (`RUN_AS_NON_EXPEDITED_WORK_REQUEST` fallback). (reviewed 2026-10-13, B4) Below API 31 WorkManager runs expedited work as a foreground service and calls `getForegroundInfo()`; `CoroutineWorker`'s default throws, which crashes every Fire TV. The worker **must** override it (dataSync notification) |
| `epg-periodic` | Periodic 12 h (`DataSettings`), `UPDATE` | `EpgImportWorker` | CONNECTED, storageNotLow | |
| `epg-import` | One-time, `APPEND_OR_REPLACE` | `EpgImportWorker` | CONNECTED for download, none if every source is cached | foreground `dataSync` while building |
| `epg-patch` | One-time, `APPEND_OR_REPLACE` | `EpgPatchWorker` | none (cached files) | input: none. The worker reads the pending refs from `EpgMatchRepository` (no ids in `Data`) |

- **`PlaylistRefreshWorker`** steps:
  0. (reviewed 2026-10-13, S9) Acquire `ImportMutex` for steps 3–4 (the merge), so a playlist merge and an EPG build never run together (§4.5 heap budget).
  1. Read the secret. `Credentials(UNAVAILABLE)` → `Result.retry()`; any other `Credentials` error → `Result.failure` and a log row.
  2. Download to `cacheDir/playlists/<id>.src` with conditional GET (M3U), or Xtream `login` + `download(LIVE_CATEGORIES)` + `download(LIVE_STREAMS)`. Not modified → report `NOT_MODIFIED`.
  3. Run `PlaylistDiffer.merge` with an `EntryProducer` that re-opens the file and decompresses it (`Compression`) on each pass.
  4. Store `M3uHeader` (EPG URLs redacted, B3) and account info. Upsert playlist EPG sources by `(playlist_id, origin, ordinal)`, updating the URL in place (S14): `x-tvg-url` → `PLAYLIST_HEADER`, Xtream → `XtreamUrls.xmltv()` as `XTREAM`. (reviewed 2026-10-13, B1) For an **Xtream-shaped M3U** (`DetectedCredentials` present), also call `XtreamApi.login` with the detected base and pair. It is best effort (a failure only logs), and on success `TimeBaseFactory.fromAccount` refreshes `server_zone`/`server_offset_sec`/`clock_skew_sec`, so `xc` catch-up uses the panel zone. Persist `etag`/`last_modified` only through `MergeSink.finish` (S10).
  5. Incremental matching, then enqueue `epg-patch` if needed, or `epg-import` when the playlist's EPG sources changed.
  6. Write the report.

  Network errors → `Result.retry()` (exponential backoff from 30 s, at most 3 attempts, then failure with a persisted log row). `Auth` and `NotASource` → failure with no retry.
- **`EpgImportWorker`:** `setForeground(ForegroundInfo(id, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC))` on API 29+ (the 2-argument form below 29). (reviewed 2026-10-13, B4) The call is wrapped in `try/catch (IllegalStateException)`: on API 31+ a worker started in the background (periodic work on Google TV/Onn) gets `ForegroundServiceStartNotAllowedException`. The import then continues without the FGS, inside WorkManager's 10-minute window, which a ≤ 90 s build fits. It also overrides `getForegroundInfo()`. Build/patch run under `ImportMutex` + `EpgWriteLock`. For each enabled source (USER sources, and the sources of enabled playlists), a conditional GET to `cacheDir/epg/<sourceId>.new`. If every source is not-modified and no subject changed since the last build → skip. Otherwise, under `EpgWriteLock` (`Mutex`): `EpgStore.build(…)` with `MatcherWhitelistResolver`, falling back to the last-good file on `failedSourceId` (§3.5.6). On success, promote each `.new` file to `<sourceId>.src` and update `epg_source` counts, `etag` and `last_modified` (written only now, reviewed 2026-10-13, S10). Progress goes through `setProgress` and `SyncStatusRepository`.
- **App start** (`RefreshScheduler.onAppStart()`, from `Application.onCreate` on a background scope): enqueue `playlist-refresh-<id>` with `KEEP` for playlists with `update_on_start` and `last_success_at` older than 12 h. Do the same for `epg-import` when the generation is older than its interval or missing. Boxes are often unplugged, so periodic work drifts.
- **Hilt-work (M0 L2):**

```kotlin
@HiltAndroidApp
class TheBoxApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.WARN)
            .build()
}
```

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<application …>
  <provider android:name="androidx.startup.InitializationProvider"
            android:authorities="${applicationId}.androidx-startup"
            android:exported="false" tools:node="merge">
    <meta-data android:name="androidx.work.WorkManagerInitializer"
               android:value="androidx.startup" tools:node="remove" />
  </provider>
  <service android:name="androidx.work.impl.foreground.SystemForegroundService"
           android:foregroundServiceType="dataSync" tools:node="merge" />
</application>
```

- **Hilt modules (`:data`):**
  - `DispatchersModule` binds `@IoDispatcher` (`Dispatchers.IO`) and `@DefaultDispatcher`, plus `@ApplicationScope CoroutineScope` (`SupervisorJob + Default`).
  - `DatabaseModule` provides the `LibraryDatabase` singleton and DAOs.
  - `EpgModule` provides the `EpgStore` singleton. It builds `EpgStoreImpl(File(noBackupFilesDir, "epg"), BundledSQLiteDriver(), AndroidFreeSpace, Xml::newPullParser, System::currentTimeMillis)` and runs `recover()` on `@ApplicationScope` + IO. (reviewed 2026-10-13, S1: no `SqliteTmpDir`, since the build uses `TEMP_STORE=3`. S4: the factory only constructs, and `XmltvParser` sets the features.)
  - `DataModule` binds `M3uPlaylistParser`, `PlaylistDiffer`, `StableKeyDeriver`, `RemapPlanner`, `XtreamCatalogDecoder`, `CatchupResolver`, `CatchupUrlBuilder` (`@Singleton`, constructed directly because the `:core` classes have no Hilt annotations), and `XtreamApi → XtreamClient`, `SourceFetcher → OkHttpSourceFetcher`, `CredentialStore → TinkCredentialStore`.
  - `HttpModule` provides `@ApiClient`/`@DownloadClient` OkHttp from one shared base client (shared connection pool and dispatcher).

---

## 4. Streaming contract

### 4.1 Inputs and emission

| Parser | Input | Emission | Never |
|---|---|---|---|
| M3U (A) | `okio.BufferedSource` (already decompressed) | push: `M3uSink.onEntry(ParsedChannel)` | `readUtf8()`, `readByteArray()`, `lines()`, a `List<ParsedChannel>` |
| XMLTV (B) | `java.io.InputStream` (already decompressed) | push: `XmltvHandler` with skip-before-parse; one reusable `XmltvProgramme` | DOM, `nextText()` on skipped elements, a list of programmes |
| Xtream (C) | `java.io.InputStream` from the downloaded file | pull: `Sequence<ParsedChannel>` (lazy `decodeToSequence`), consumed inside `use {}` | `decodeFromString(body.string())`, a list of streams |
| Playlist differ (A) | `EntryProducer` (re-opens the cached file per pass) | `MergeSink` batches | holding entries across batches |

Every source is **downloaded to a file first** (`SourceFetcher` or `XtreamApi.download`), and parsing reads the file. This allows two passes, gives the 90 % truncation rule an accurate size, and keeps the last-good copy (`*.src`) for retries and EPG patches.

### 4.2 Compression

`Compression.decompress` (§1.2.3) is applied to every file before parsing:

- detection is by magic bytes only: gzip `1F 8B`, xz `FD 37 7A 58 5A 00`, zip `50 4B 03 04` (first file entry, skipping `__MACOSX/`);
- up to 3 layers (double gzip);
- the xz dictionary is capped at 16 MiB (a larger one fails with `Unsupported`).

`OkHttpSourceFetcher` does not set `Accept-Encoding`, so OkHttp adds `gzip` and decodes `Content-Encoding: gzip` transparently. A `.gz` *file* served as `application/octet-stream` still lands compressed, and the sniff handles it (protocols §2.2).

### 4.3 Text decoding

- **M3U**, per line, on bytes (`LineReader` yields `ByteString`):
  1. `forcedCharset`, if set.
  2. A BOM (UTF-8 is stripped; UTF-16 LE/BE switches to `Utf16LineReader`).
  3. `charsetHint` from `Content-Type`, if it is not UTF-8.
  4. Otherwise strict UTF-8 (`CodingErrorAction.REPORT`) **per line**. On the first malformed line, choose the fallback charset once and emit `CHARSET_FALLBACK`. From then on each line is still tried as strict UTF-8 first, and only lines that fail are decoded with the fallback. (reviewed 2026-10-13, S15: a permanent switch garbles every later line of a UTF-8 file with one corrupt line. High bytes in 1251/1252 text almost never form valid UTF-8, so trying UTF-8 per line is safe.) The fallback is `windows-1251` when at least 70 % of the line's bytes ≥ `0x80` fall in `0xC0–0xFF` (Cyrillic letters) and there are at least 3 of them, otherwise `windows-1252` (a printable superset of Latin-1). The charset appears in the report.

  The user can override with `playlist.charset_override`. No charset-detection library is added (§7).
- **Line endings:** LF, CRLF and lone CR. Lines are trimmed. No `\r` survives into a URL or a name.
- **XMLTV:** `setInput(stream, null)`, so kxml honours the BOM and the `encoding=` declaration. Malformed bytes become U+FFFD and are not fatal.
- **Xtream JSON:** UTF-8 only. The BOM is stripped before `decodeToSequence`.

### 4.4 Batch sizes and transactions

| Writer | Unit | Transaction size | Between batches |
|---|---|---|---|
| Playlist merge (`RoomMergeSink.apply`) | channel rows (insert/update/touch) | 2,000 | `gate.awaitClear()`, cancel check |
| Playlist finish | missing/purge/groups/report | 1 transaction | — |
| EPG rebuild (`ImportSession`) | programme rows (after split) | 10,000 | gate, cancel, progress |
| EPG patch / in-place | programme rows | 5,000 (short write locks for readers) | gate, cancel, progress |
| Repair pass | channels | 500 channels per transaction | gate, cancel |
| Matcher persistence | subjects | 2,000 | — |
| `epg_channel` / `epg_text` inserts | rows | part of the enclosing programme batch | — |

All inserts use statements prepared **once per session** (`SQLiteConnection.prepare`) and are re-bound per row (`bindLong`/`bindText`/`bindNull`, `step()`, `reset()`, `clearBindings()`).

### 4.5 Memory budget (importer heap ≤ 20 MB retained, plan §6)

| Consumer | 20k-channel list / typical EPG | 200k-entry list / 1M-programme EPG |
|---|---|---|
| Stream buffers (okio, gzip, kxml) | ~0.3 MB | ~0.3 MB |
| xz dictionary (only `.xz` sources) | ≤ 8 MB typical (preset 6), hard cap 16 MB | same |
| Differ existing index (§3.3.2) + seen-key occurrence map | ~1.2 MB | ~10 MB |
| One merge batch (2,000 `KeyedChannel`) | ~3 MB | ~3 MB |
| XMLTV channel map (`HashMap<String, Int>`, id → row or decision) | ~1 MB (5k channels) | ~4.5 MB (50k channels) |
| Text dedup `MutableLongIntMap` | ~1 MB | ~5 MB (300k texts) |
| Repair buffers | < 0.1 MB | < 0.2 MB |
| Matcher during the whitelist callback (subjects, token index) | ~1 MB | ≤ 8 MB (20k subjects) |
| **Peak (playlist path / EPG path)** | **~5 / ~4 MB** | **~14 / ~18 MB** (+ xz dictionary when used) |

Native, not Java heap: the SQLite page cache (16 MB build, 4 MB × 2 per reader), a 64 MB reader `mmap` window (virtual), and the in-memory temp storage of the index/FTS build (`TEMP_STORE=3`, ≤ 64 MB peak budget, §2.3). The two importers never run at the same time. (reviewed 2026-10-13, S9) The periodic playlist and EPG workers are separate unique works and *can* overlap, so the guarantee comes from the process-wide `ImportMutex`, which the playlist merge and EPG build/patch both take. Enqueue order is not enough. **Risk:** an `.xz` source with an 8 MB dictionary on a 200k/1M worst case reaches ~26 MB. The perf test runs gz. If xz matters on hardware, M6 measures it and either lowers the cap or decompresses to a temp file first (§7 D18).

### 4.6 Threads, priority, cancellation, progress

- `:core` code is blocking and dispatcher-free. `:data` calls it inside `withContext(io)` (injected). For the duration of an import it calls `android.os.Process.setThreadPriority(THREAD_PRIORITY_BACKGROUND)` and restores the old priority in `finally` (B3.4).
- Cancellation: workers pass `CancelCheck { !coroutineContext.isActive }`, captured before entering the blocking section. Parsers poll it at least every 1,000 entries or programmes and at every batch. `CancellationException` always propagates, and the `.building` file is deleted.
- Progress: bytes are counted on the *compressed* file stream (`CountingSource` under `Compression`). Callbacks are throttled to at most one per 500 ms. Workers forward them to `setProgress` and `SyncStatusRepository` (S5/S15: "Downloading 120 MB", "Matching 1,912 of 2,140").
- Single writers: one `EpgWriteLock` (`kotlinx.coroutines.sync.Mutex`) for build/patch/clear; one refresh per playlist (unique work); library.db writes go through Room's single writer connection. One `ImportMutex` across the playlist merge and EPG build/patch (reviewed 2026-10-13, S9). Lock order is always `ImportMutex` → `EpgWriteLock`.

---

## 5. Tests

### 5.1 Where tests run (decision)

| Suite | Runner | Runs | Why |
|---|---|---|---|
| `:core:test` | JVM, JUnit 5 | parsers, keys, normaliser, differ (with an in-memory `MergeSink`), matcher, Xtream decoding and URL builders, `CatchupResolver`/`CatchupUrlBuilder`/`TimeBaseFactory` golden tests, **`EpgStoreImpl` on `sqlite-bundled` JVM natives** (schema, import, repair, generation swap, recovery, patch, in-place, window, search, FTS5 and a forced FTS4 path) | Same SQLite build as the device (verified 2026-10-13: SQLite 3.50.1 with FTS5 runs from the JVM jar on the Intel dev Mac, `osx_x64`; the jar also ships `linux_x64` for CI), sub-second feedback, no emulator |
| `:core:perfTest` | JVM, JUnit 5, tag `perf`, `-Xmx64m` | §5.5 | Kept out of the default build. Nightly CI, **report-only** there: heap budgets are enforced, while time budgets are only reported on shared runners and enforced locally with 1.5× tolerance (reviewed 2026-10-13, S21) |
| `:data:testDebugUnitTest` | JVM, JUnit 4 | Plain JUnit: `XtreamClient` and `OkHttpSourceFetcher` against MockWebServer. **Robolectric** (`sdk=35`): Room DAOs, `RoomMergeSink`/`RoomExistingChannels`, the unmatched queries, repositories, `MatcherWhitelistResolver`, workers via `work-testing` `TestListenableWorkerBuilder` with fakes, all on `AndroidSQLiteDriver` | Fast Room coverage. The bundled driver's Android `.so` cannot load on the host JVM, so Room logic is tested on the framework engine there and the bundled engine is covered by the instrumented suite. Robolectric's SDK 35 jar needs a **JDK 21** runtime (local and CI already use 21). (reviewed 2026-10-13, S21) CI runs this suite: `android.yml` becomes `./gradlew :core:test :data:testDebugUnitTest :app:assembleDebug` |
| `:data:connectedDebugAndroidTest` | API 30 TV emulator (`TheBox_TV_API30`, x86; the AAR ships x86 natives), plus I-9 on `Pixel_6_Pro_API_34` (x86_64) | I-1 … I-9 below | Things only the device has: bundled `.so`, Keystore, WorkManager + Hilt, native memory during the build, the platform XML parser, API 31+ FGS rules, real `StatFs`. Run locally; not in CI (no emulator job in M1) |

Rejected: Robolectric for `epg.db` (FTS5 and the bundled build are not guaranteed there), and instrumented-only Room tests (too slow for the inner loop).

### 5.2 Fixture files

All fixtures are hand-written, small (< 50 KB) and committed. Large inputs are generated at test time (§5.5). Each file starts with a comment line (`#` for M3U, `<!-- -->` for XML; JSON fixtures get a sibling `.md` note) saying which rules it covers.

`core/src/test/resources/m3u/` (WP-A):

| File | Content |
|---|---|
| `iptv_org.m3u` | `#EXTM3U x-tvg-url="a.xml.gz,b.xml"`; `tvg-id="2MMonde.ma@SD"` ids; `http-referrer=`/`http-user-agent=` on EXTINF **and** duplicated in `#EXTVLCOPT`; `group-title="News;Morocco"`; a comma inside a quoted `tvg-name="News, UK"`; `tvg-logo` absolute; 30 entries |
| `xtream_get_php.m3u` | `m3u_plus` style: `/live/u%40x/p%23w/101.ts` with an encoded `@`/`#`, short-form `http://h:8080/u/p/102`, `/movie/u/p/2001.mkv`, `/series/u/p/3001.mp4`, `tvg-type`, `xui-id`, `catchup="xc" catchup-days="3"`, `timeshift="2"` on one, 25 % VOD |
| `pipe_headers.m3u` | `|User-Agent=Foo%20Bar&Referer=https://x/`, `|!X-Token=abc`, a URL whose query contains `|` but no `k=` after it, an empty pipe, pipe + EXTVLCOPT conflict (pipe wins) |
| `kodi_props.m3u` | `#KODIPROP` before and after EXTINF: `inputstream.adaptive.manifest_type=mpd`, `license_type=com.widevine.alpha`, `license_key=https://lic|Content-Type=a|R{SSM}|`, `stream_headers=User-Agent%3DX%26Referer%3DY`; `#EXTHTTP:{"Cookie":"a=b"}`; an `inputstreamaddon=` alias |
| `bom_crlf.m3u` | UTF-8 BOM + CRLF, one lone-CR line, trailing spaces and tabs, blank lines, a URL followed by `\r` |
| `no_header.m3u` | No `#EXTM3U`, a URL without EXTINF, an EXTINF without a URL at EOF |
| `latin1.m3u` | ISO-8859-1 bytes (`Télé`, `Zürich`, `Ñ`) after 5 ASCII lines (exercises the mid-file switch) |
| `cp1251.m3u` | Windows-1251 Cyrillic names (`Первый канал`) |
| `utf16le_bom.m3u` | UTF-16 LE with BOM, 3 entries |
| `extgrp.m3u` | `#EXTGRP` per-entry vs sticky, an empty `#EXTGRP:`, an explicit `group-title` inside a sticky run |
| `catchup_variants.m3u` | Header `catchup="append" catchup-source="?utc={utc}" catchup-days="5" catchup-correction="-1.5"`; channels with each mode; `catchup-type=` alias; `timeshift=`, `tvg-rec=`, `catchup-time=`; an unrecognised `catchup="1"`; per-channel correction |
| `edge_cases.m3u` | `tvg-ID` upper-case keys, single-quoted and unquoted values, empty `tvg-id=""`, `tvg-id="none"`, `tvg-chno="101.0"`, `tvg-shift="-1,5"` and `tvg-shift="3600"`, a 70 KB line, a relative URL, `rtmp://`, `udp://@239.1.1.1:1234`, `file:///x`, `type="playlist"`, duplicate names in different groups, a `data:` logo, `radio="true"`, `parent-code="1234"`, `url-logo` with a relative logo without an extension |
| `hls_manifest.m3u8` | `#EXTM3U` + `#EXT-X-STREAM-INF` → `HLS_MANIFEST` |
| `html_expired.m3u` | An HTML "account expired" page → `HTML_PAGE` |
| `xmltv_as_playlist.xml` | `<?xml…><tv>` → `WRONG_FORMAT` |
| `iptv_org.m3u.gz`, `iptv_org.m3u.gz.gz`, `iptv_org.zip` | The same list compressed (built by a script in `tools/fixtures/` that the lead keeps; committed binaries) |

`core/src/test/resources/refresh/` (WP-A): `v1.m3u` → `v2.m3u` pairs:

| Pair | v2 changes |
|---|---|
| `basic_v1/v2` | 2 added, 3 removed, 2 URL changes (token rotation), 1 logo change, reorder of 5, one `HD → FHD` rename (key must hold) |
| `host_rotation_v1/v2` | Every host changes `lb1 → lb7`; URL-rule keys must be identical |
| `xc_templated_v1/v2` | Xtream-shaped M3U; the password changes in v2 (templated URLs unchanged, `DetectedCredentials` differ) |
| `provider_migration_v1/v2` | All stream ids change, names stay → `massRemapSuggested`, `RemapPlanner` 1:1 mapping |
| `shrink_v1/v2` | v2 has 8 of 400 live channels → `SuspiciousShrink` |
| `duplicates.m3u` | Same `tvg-id`+name 3 times → `~2`, `~3` |

`core/src/test/resources/xmltv/` (WP-B):

| File | Content |
|---|---|
| `basic.xml` | 3 channels (2 display-names each, `lang`), 30 programmes using every element of §3.5.4, `catchup-id` |
| `offsets.xml` | `+0100`, `-0500`, `+05:30`, `+0545`, no space (`20261013190000+0100`), `Z`, `GMT`, `UTC`, `EST`, no offset (UTC and `assumeZone=Europe/London` across 2026-10-25 01:00), truncated `202610131900`, 8-digit date only, invalid `2026131319` |
| `episode_num.xml` | `xmltv_ns` `2.9.0/2`, `.9.`, `0.0.`, `1/3.4/10.`, `onscreen` `S03E10`, `Ep. 5`, `dd_progid` `EP01234567.0012`, several systems on one programme |
| `overlaps.xml` | Overlap, duplicate start (later wins), out-of-order programmes, a missing stop (inferred from the next start; the last one +60), a 30-hour programme (split 6 h), zero-length, negative length |
| `interleaved.xml` | 50 channels × 20 programmes ordered by time across channels |
| `no_channels.xml` | Programmes only (whitelist by id) |
| `late_channel.xml` | `<channel>` elements after the first programmes |
| `entities.xml` | `&amp;`, `&#233;`, `&nbsp;` (relaxed), CDATA, comments, DOCTYPE with an external DTD, a processing instruction |
| `latin1.xml` | `encoding="ISO-8859-1"` declaration with Latin-1 bytes |
| `lang.xml` | Titles in `fr`/`en`/none; preferred `["en"]` |
| `long_desc.xml` | 5 KB description (cap at 2,048 on a code-point boundary with an emoji at the edge) |
| `truncated.xml.gz` | Cut at 95 % (accepted, `truncated`) and `truncated_early.xml.gz` cut at 40 % (source fails) |
| `basic.xml.gz`, `basic.xml.xz`, `basic.zip`, `basic.xml.gz.gz` | Compression matrix |

`core/src/test/resources/xtream/` (WP-C; mirrors the fake panel's clean and quirks modes, M0 L12):

| File | Content |
|---|---|
| `login_ok.json` | All strings (`"max_connections":"2"`, `"exp_date":"1798761600"`, `"is_trial":"0"`), `allowed_output_formats` array, IANA timezone |
| `login_ints.json` | Ints and bools, `exp_date: null` (unlimited) |
| `login_xui.json` | `xui: true`, `version`, `timezone: "UTC+3"` (non-IANA → offset path) |
| `login_auth0.json`, `login_expired.json`, `login_banned.json` | `auth: 0`; `status: "Expired"`; `"Banned"` |
| `login_empty_array.json` (`[]`), `login_empty.json` (0 bytes), `login_html.html` | Auth failure variants |
| `live_categories.json` | Incl. `parent_id` and a numeric `category_id` |
| `live_streams.json` | 20 streams: `"tv_archive":"1"`/`1`/`true`/`"0"`, `tv_archive_duration` `"7"`/`3`/`""`, `epg_channel_id` `null`/`""`/`"bbc1.uk"`, `category_id: null`, XUI `category_ids:[1,5]`, `stream_type:"radio_streams"`, `is_adult:"1"`, non-empty `direct_source`, `num` as a string |
| `live_streams_object.json` | The same as `{"0":{…},"1":{…}}` |
| `live_streams_bom.json` | UTF-8 BOM + leading whitespace |
| `vod_streams.json`, `vod_info.json`, `vod_info_empty_info.json` | `info: []`, `backdrop_path` as a string vs an array, `duration` `"01:42:10"` only |
| `series.json` | `releaseDate` vs `release_date` |
| `series_info_map.json`, `series_info_array.json`, `series_info_flat.json`, `series_info_no_seasons.json` | `episodes` as an object, an array of arrays, a flat array; empty `seasons` |
| `short_epg_base64.json`, `short_epg_plain.json`, `short_epg_mixed.json` | Base64 vs plain titles; plain titles that happen to be valid base64 (`"Test"`, `"QUJD"`) |
| `simple_data_table.json` | `has_archive` 0/1, `start`/`end` in the panel zone **without** `*_timestamp` on two rows |

`core/src/test/resources/catchup/golden.tsv` (WP-C): one row per golden case (§5.4), with columns `mode, variant, liveUrl, source, zone, tokenZone, correction, padding, skew, start, stop, now, expectedUrl, expectedSeek`.

`data/src/test/resources/` (WP-D): `robolectric.properties` (`sdk=35`), and copies of nothing. Tests read `:core` fixtures through a Gradle `sourceSets.test.resources.srcDir("../core/src/test/resources")` entry that the lead adds in commit 0.

### 5.3 Test matrix per WP

**WP-A**

| Area | Cases |
|---|---|
| Lexer | quotes (`"`, `'`, none, empty, unterminated), comma in quotes, upper-case keys, bare flags, duplicates, 4 KB value cap |
| Lines and charset | M1–M6 on the fixtures; a 70 KB line is skipped without buffering (assert the allocation via a counting `Source`) |
| Directives | M9–M14, header precedence table, EXTGRP sticky/non-sticky |
| Classification | M19 on every fixture entry (expected kinds table) |
| Rejections | M2, M24: HTML, HLS manifest, XMLTV, JSON, binary, empty |
| StableKey | every rule; priority order; `forKey` table §3.1; `UrlKey` examples §3.2.2; get.php M3U vs Xtream API give the **same** keys (cross-check with WP-C's decoder output in an integration test owned by A, enabled after C merges) |
| Templating | vote thresholds (79 % vs 80 %; 94 % vs 95 %), short form only when shaped, `{{u}}`/`{{p}}` substitution, other URLs untouched; `catchup-source` and attribute values templated too (B3) |
| Review additions (2026-10-13) | B5: `forGroup` keeps "Sports HD"/"Sports SD" and "UK HD"/"UK SD" distinct. B6: `http://cdn/live/bbc/hd/1.m3u8` in a non-shaped list → not `xc:`; a shaped list with a minority pair → not `xc:`. B3: `UrlKey` of `http://h/u/p/102` never contains `u`/`p`. S7: tvg-id added → `autoRemaps` 1:1, duplicate urlHash → no remap, tokenised URL → no remap. S18: VOD-heavy additions do not trigger mass remap. S15: one bad line in a UTF-8 file → only that line is decoded as 1252 |
| Differ | every row of the §3.3.3 table; grace (6 d vs 7 d; 1 vs 2 refreshes); restore; purge; shrink guard (49 vs 50 previous; 19 % vs 20 %); mass remap (69 % vs 70 %); batch boundaries (1,999 / 2,000 / 2,001); `abort` on exception mid-pass-2; hash-collision side table (inject a colliding hash function in a test-only constructor) |
| Report | counters on `refresh/*` pairs, samples capped at 10, warnings capped at 50, JSON round-trip |
| RemapPlanner | 1:1, ambiguity → no remap, group tie-break, already-claimed |

**WP-B**

| Area | Cases |
|---|---|
| `XmltvTime` | every `offsets.xml` form; DST gap and overlap with `assumeZone`; property test against `java.time` over 100k random timestamps 1970–2100 |
| `EpisodeNum` | §3.5.2 table |
| Parser | entities and relaxed mode, CDATA, comments, DOCTYPE, encoding, lang picking, desc cap, skip path never calls `getText` (spy parser) |
| Import | whitelist call timing (once per source, before the first accepted programme), channels-without-elements, late channels, window drop, split/clip/infer (`overlaps.xml` expected rows), interleaved equals grouped result, text dedup count, flags |
| Store | rebuild, publish, pointer file contents; reader on gen N stays valid while N+1 publishes; the old file is deleted only after the last reader closes; crash simulation at each step of §2.5.2 (kill before/after the renames) followed by `recover()`; schema-version mismatch; orphan cleanup; disk precheck → in-place; patch (busy readers, `patchSeq`); `clear()` |
| Queries | `window` (shift ±90 min, gaps, a 6 h boundary programme found via `max_len_min`, `-1` channels); `at`; `detail`; `search` FTS5 and forced FTS4 (`EpgStoreImpl(forceFts4 = true)` test hook), diacritics, prefix, quotes in the query; `resolve` (case-insensitive fallback); `findChannels` |
| Failures | truncated 95 % vs 40 %; cancellation mid-source deletes `.building`; `failedSourceId` |
| Review additions (2026-10-13) | S12: 30 h placeholder followed by real programmes, so the continuations are dropped and the real rows unclipped. S13: patch adds a channel, then `resolve()` after `patchSeq` finds it; `forEachChannel` stopped early does not leak the lock. S4: `XmltvParser` with a bare `KXmlParser` decodes `&nbsp;` to U+00A0 (not dropped). S3: a 32 MiB-dictionary `.xz` gives `SourceException(Unsupported)` |

**WP-C**

| Area | Cases |
|---|---|
| `LenientJson` | every row of §3.7.2 × every JSON type |
| Decoders | every `xtream/` fixture; object-form lists (streamed, 20k generated entries under the 64 MB perf heap, S5); BOM; base64 detection on the mixed fixture |
| Mapping | `ParsedChannel` field-by-field on `live_streams.json` |
| `XtreamUrls` / `XtreamLoginUrl` | encoding of `@ # & + / space é`, base normalisation, get.php/player_api/stream-URL paste parsing |
| `TimeBaseFactory` | IANA, non-IANA (`UTC+3`), missing; stale-tzdata disagreement → offset; skew bounds |
| `CatchupResolver` | §3.8.1 a–g and post-rules; §3.8.2 each link; Forced/Off; L11 (`tv_archive=1`, no duration → 3 days) |
| `CatchupUrlBuilder` | golden matrix §5.4; Unsupported reasons; variant order with and without a winner; absolute `catchup-source` override. Review additions (2026-10-13): B1, an Xtream-shaped M3U with `server_zone = Asia/Tokyo` and Auto token zone gives an `xc` segment in Tokyo time while `{Y}…` tokens stay in UTC; S17, `shift` on an Xtream target lists `xc-path, xc-php`; padding across the archive edge is clamped, not rejected |
| `XtreamClient` (MockWebServer) | rotation on connect failure and 5xx; 429 with `Retry-After`; auth mapping table; semaphore (4th request waits); streaming download (100 MB body, heap stays flat); cancellation cancels the call; `series=` fallback; redaction (no credential in captured logs) |

**WP-D**

| Area | Cases |
|---|---|
| Matcher | each stage; digit guard (`Sky Sports 1/2`, `Channel 4 / +1`); country penalty; ambiguity margin; levels; blocking recall (brute force vs blocked on 2k × 5k random names: identical AUTO results, ≥ 99 % identical suggestions); `ID_BASE` iptv-org |
| Room (Robolectric) | schema matches §2.1.2 (query `sqlite_master`); FK cascades; parental predicate on browse/number/search/unmatched; unmatched and `matchedWithoutData` queries; orphan trim; refresh_log retention; the "no EPG row id column" rule |
| `RoomMergeSink` | applying a differ run on `refresh/*` pairs end to end with the real differ (after A merges), favourites survive `basic`, `host_rotation`, `xc_templated`; purge keeps user data; `remapUserData` |
| Credentials | `TinkCredentialStore` with a fake `Aead` on the JVM (round trip, AD binding, corrupt row); Keystore in I-2 |
| Workers | with fakes: success, NOT_MODIFIED, retry on Network, failure on Auth/NotASource, shrink → FAILED + log row, `epg-patch` enqueued for `imported = 0`, app-start staleness |
| `SourceFetcher` | conditional GET 304, `.part` rename, `maxBytes`, error mapping |
| Review additions (2026-10-13) | B3: `header_json` and `epg_source.url_hash` contain no credential (scan every TEXT column after an Xtream-shaped import for the password). S6: a refresh that only re-orders channels writes 0 `channel_fts` rows. S8: a 2-year-old favourite survives a purge for 180 days. S14: an `x-tvg-url` change keeps the `epg_source.id` and the user's `epg_mapping`. S16: a transient `KeyStoreException` gives `UNAVAILABLE` and deletes nothing. S9: playlist merge and EPG build serialise on `ImportMutex`. B4: `PlaylistRefreshWorker.getForegroundInfo()` returns a dataSync `ForegroundInfo` |

### 5.4 Catch-up golden matrix (WP-C; architecture review B7.3, M0 L13)

`modes × variants` (`xc-path`, `xc-php`, `xc-path-sec`, `default` with each placeholder family, `append`, `shift`, `flussonic-range` HLS and TS and open-ended, `flussonic-abs`, `flussonic-kodi` for `index`/`mono`/`video`/`mpegts`, `vod`) × zones {`UTC`, `Europe/London` on 2026-10-24 and 2026-10-25 (the DST end, both sides of 01:00 local), `America/New_York` on 2026-11-01, `Asia/Kolkata` (+05:30), `Asia/Kathmandu` (+05:45)} × `catchup-correction` {0, −4.0, +1.5} × padding {0/0, 60/300} × skew {0, +95 s}.

Each case asserts the URL, `requestStart`, `requestEnd` and `nativeSeekRange`. The xc rows double as `timeshift_expect` values for the fake panel (`tools/fakepanel`, README "strict mode"), so M4's device test can replay them.

### 5.5 Performance tests (`:core:perfTest`, JUnit tag `perf`)

Generators live in test code:

- `M3uGenerator(entries = 200_000, vodShare = 0.6, seed)`: realistic names, groups, `tvg-id` on 70 %, Xtream-shaped URLs on 50 %.
- `XmltvGenerator(channels = 50_000, whitelisted = 20_000, days = 7, perDay = 7, gzip = true, seed)`: about 1M kept programmes, about 1.5M skipped. 15 % of titles are unique and the rest repeat (dedup). 5 % of programmes have no stop. 1 % overlap.

| Test | JVM budget (CI runner) | Device gate (plan §6; recorded by I-5 on the emulator, enforced on hardware in M6) |
|---|---|---|
| M3U 200k: parse + key + diff into an in-memory sink, first import | ≤ 3 s | ≤ 30 s end-to-end incl. Room |
| M3U 200k: second refresh, no changes | ≤ 2.5 s, 0 rows written | — |
| M3U 200k retained heap (max of `used` after `System.gc()` at 25/50/75/100 %) | ≤ 20 MB | ≤ 20 MB |
| XMLTV 1M-programme rebuild (parse, insert, repair, indexes, FTS, ANALYZE) | ≤ 25 s | **≤ 90 s while playing** |
| XMLTV importer retained heap | ≤ 20 MB | **≤ 20 MB** |
| Window query: 36 channels × 12 h on the 20k × 7 d database, warm (p95 of 1,000 runs) | ≤ 2 ms | **≤ 5 ms** |
| Window query cold (fresh reader, first call) | ≤ 15 ms | **≤ 30 ms** |
| Search `"news"` p95 | ≤ 30 ms | ≤ 100 ms |
| Matcher 20k subjects × 50k EPG channels | ≤ 3 s, ≤ 8 MB | ≤ 15 s |

`perfTest` runs with `maxHeapSize = "64m"` (so a leak fails loudly) and `-XX:+UseSerialGC` (stable `used` numbers). Results are written to `build/perf/*.json` for the M6 trend.

### 5.6 Integration tests (enabled as the WPs merge)

| Test | Owner | Needs |
|---|---|---|
| `M3uToRoomIT` (Robolectric): parse → differ → `RoomMergeSink` on every `refresh/*` pair | D | A |
| `XtreamRefreshIT` (Robolectric + MockWebServer): login, categories, streams (clean and quirks fixtures) → channels; account card; `active_base_index` | D | A, C |
| `EpgBuildIT` (Robolectric, `AndroidSQLiteDriver`, FTS4 path): `EpgStoreImpl` + `MatcherWhitelistResolver` + `epg_match.has_data` + patch enqueue | D | B |
| `GetPhpEqualsXtreamKeysIT` (JVM): same line as M3U vs API → identical key sets | A | C |

### 5.7 Instrumented (API 30 TV emulator)

| # | Test |
|---|---|
| I-1 | `LibraryDatabase` on `BundledSQLiteDriver`: open, FTS4 `channel_fts` search, `PRAGMA foreign_keys` = 1 on the writer **and on a reader connection** (S2), `MigrationTestHelper.createDatabase(1)` matches the exported JSON |
| I-2 | `TinkCredentialStore` with the real Keystore: round trip; delete the master key alias → `KEY_INVALIDATED`, rows wiped, `needs_reauth` set |
| I-3 | `EpgImportWorker` end to end with a MockWebServer serving `basic.xml.gz`: publish, reader window, generation swap with an open reader (old file deleted after close) |
| I-4 | `PlaylistRefreshWorker` with `WorkManagerTestInitHelper`: M3U and Xtream (quirks fixtures), refresh_log rows, S15 status flow |
| I-5 | 1M-programme generated build on the device. Records the time, Java heap, DB size and **native heap** (`Debug.getNativeHeapAllocatedSize()` sampled every 250 ms; budget ≤ 64 MB peak above baseline). (reviewed 2026-10-13, S1: `TEMP_STORE=3` keeps index/FTS sort spill in memory, so `SQLITE_TMPDIR` is moot.) Above budget → set `EpgSchema.INDEX_BEFORE_LOAD` and re-measure |
| I-6 | Hilt: a `@HiltWorker` instantiated through the app's `Configuration.Provider` (custom `HiltTestRunner`, M0 L2) |
| I-7 | Low disk: an `EpgModule` test override with `FreeSpace { 10 MB }` → in-place path; readers keep working |
| I-8 | (reviewed 2026-10-13, S4) Platform-parser parity: `XmltvParser(Xml::newPullParser)` on `entities.xml`, `latin1.xml`, `basic.xml` gives the same `XmltvProgramme` values as the JVM `KXmlParser` run, including `&nbsp;` → U+00A0 and the DOCTYPE skipped |
| I-9 | (reviewed 2026-10-13, B4) On `Pixel_6_Pro_API_34`: `EpgImportWorker` started from the background, where `setForeground` throws and the import still completes. On the API 30 TV AVD: an expedited `PlaylistRefreshWorker` runs as an FGS without crashing |

---

## 6. Build wiring (commit 0, lead)

### 6.1 Catalog additions (`gradle/libs.versions.toml`)

Pin to the latest stable release on the day of commit 0. (reviewed 2026-10-13: values checked against Maven Central / Google Maven metadata today. okio was wrong: OkHttp 5.5.0's `.module` requires okio **3.18.1**, so pin exactly that, or 3.18.2 only if OkHttp moves. Latest today: xz 1.12, Robolectric 4.17, androidx.collection 1.7.0 (`MutableLongIntMap` since 1.4), androidx.test core-ktx/runner 1.7.0, `mockwebserver3` 5.5.0, kxml2 2.3.0.)

```toml
[versions]
okio = "3.18.1"              # = OkHttp 5.5.0's requirement (verified in okhttp-5.5.0.module)
xz = "1.12"
kxml2 = "2.3.0"
androidxCollection = "1.7.0"
robolectric = "4.17"         # sdk=35 needs a JDK 21 runtime; compileSdk 37 does not matter for Robolectric
junit4 = "4.13.2"
androidxTestCore = "1.7.0"
androidxTestRunner = "1.7.0"

[libraries]
okio = { module = "com.squareup.okio:okio", version.ref = "okio" }
xz = { module = "org.tukaani:xz", version.ref = "xz" }
kxml2 = { module = "net.sf.kxml:kxml2", version.ref = "kxml2" }
androidx-collection = { module = "androidx.collection:collection", version.ref = "androidxCollection" }
sqlite = { module = "androidx.sqlite:sqlite", version.ref = "sqlite" }
sqlite-framework = { module = "androidx.sqlite:sqlite-framework", version.ref = "sqlite" }
room-testing = { module = "androidx.room:room-testing", version.ref = "room" }
work-testing = { module = "androidx.work:work-testing", version.ref = "work" }
okhttp-mockwebserver = { module = "com.squareup.okhttp3:mockwebserver3", version.ref = "okhttp" }
hilt-android-testing = { module = "com.google.dagger:hilt-android-testing", version.ref = "hilt" }
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
junit4 = { module = "junit:junit", version.ref = "junit4" }
androidx-test-core = { module = "androidx.test:core-ktx", version.ref = "androidxTestCore" }
androidx-test-runner = { module = "androidx.test:runner", version.ref = "androidxTestRunner" }
```

### 6.2 `:core` (pure JVM)

```kotlin
plugins {
    alias(libs.plugins.thebox.jvm.library)
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`                         // (reviewed 2026-10-13, B2) epg/testutil shared with :data tests
}

dependencies {
    api(libs.okio)                               // BufferedSource in public signatures
    api(libs.sqlite)                             // SQLiteDriver/SQLiteConnection in EpgStoreImpl's constructor
    api(libs.kotlinx.coroutines.core)            // StateFlow in EpgStore
    api(libs.kotlinx.serialization.json)         // @Serializable contract types
    implementation(libs.sqlite.bundled)          // KMP: JVM jar here, the Android AAR in :data/:app (verified in the .module metadata: per-consumer variant selection)
    implementation(libs.xz)
    implementation(libs.androidx.collection)     // MutableLongIntMap etc. (KMP JVM variant)
    compileOnly(libs.kxml2)                      // org.xmlpull API; the platform provides it on Android
    testImplementation(libs.kxml2)
    testImplementation(libs.kotlinx.coroutines.test)
    testFixturesImplementation(libs.sqlite.bundled)
    testFixturesImplementation(libs.okio)
}

tasks.named<Test>("test") { useJUnitPlatform { excludeTags("perf") } }

val perfTest by tasks.registering(Test::class) {
    description = "M1 performance budgets (docs/design/m1-data-layer.md §5.5)"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("perf") }
    maxHeapSize = "64m"
    jvmArgs("-XX:+UseSerialGC")
}
```

Day-0 checks for the lead, before splitting the work:

1. A `:core` JVM test opens `BundledSQLiteDriver().open(":memory:")` on the dev Mac and Linux x64 (CI) and reports `ENABLE_FTS5`. (reviewed 2026-10-13: the dev Mac is **Intel x86_64**, not arm64. The Mac half already passed in the review: SQLite 3.50.1, `ENABLE_FTS5`, `TEMP_STORE=3`, `THREADSAFE=2`. Keep the test as a regression guard.)
2. `:app:assembleDebug` packages exactly one `libsqliteJni.so` per ABI and no `natives/` jar entries.
3. `org.xmlpull` is not packaged into the APK (`compileOnly`).
4. `./gradlew :core:test :data:testDebugUnitTest :app:assembleDebug` is green with all stubs in place.
5. (reviewed 2026-10-13) One Robolectric test in `:data`: `AndroidSQLiteDriver` + `WITHOUT ROWID` + FTS4 `tokenize=unicode61 "remove_diacritics=2"` + `PRAGMA journal_mode=OFF`. If it fails, `EpgBuildIT` (§5.6) uses a fake `EpgStore`, and the real store stays covered by `:core` JVM tests and I-3.

If check 2 fails, move `implementation(libs.sqlite.bundled)` to `testImplementation` in `:core`. `EpgStoreImpl` then reaches the read-only flag through an `EpgConnections` interface implemented in `:data`. That is a one-file change for WP-B.

### 6.3 `:data`

```kotlin
android {
    namespace = "app.thebox.data"
    defaultConfig { testInstrumentationRunner = "app.thebox.data.HiltTestRunner" }   // M0 L7
    testOptions { unitTests { isIncludeAndroidResources = true; isReturnDefaultValues = true } }
    sourceSets["test"].resources.srcDir("../core/src/test/resources")   // AGP 9 DSL: verify srcDir vs directories.add on day 0
}

dependencies {
    // existing: :core (api), room-runtime/ktx/compiler, sqlite-bundled, sqlite-ktx, okhttp, dnsoverhttps,
    // work-runtime-ktx, hilt-work + androidx.hilt compiler, coroutines-android, serialization-json, tink
    testImplementation(testFixtures(project(":core")))   // (reviewed 2026-10-13, B2) XmltvGenerator, EpgTestDb
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.sqlite.framework)          // AndroidSQLiteDriver for Robolectric
    testImplementation(libs.room.testing)
    testImplementation(libs.work.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.work.testing)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    kspAndroidTest(libs.hilt.compiler)
}
```

### 6.4 `:app`

Add `implementation(libs.work.runtime.ktx)` and `implementation(libs.androidx.hilt.work)` (for `Configuration.Provider` and `HiltWorkerFactory`). The manifest changes are in §3.10. R8: kotlinx-serialization, Room, WorkManager, Tink, OkHttp and xz ship consumer rules. Add `-dontwarn org.xmlpull.v1.**` only if R8 complains.

### 6.5 JSON library: kotlinx-serialization (decision)

It is already in the catalog, with the plugin applied in `:core` and `:data` and R8 rules verified in M0. `Json.decodeToSequence(InputStream, …)` gives true streaming over the file, and the `JsonElement` tree per item is the simplest way to express the Xtream leniency rules (§3.7). Moshi was rejected: it would be a second JSON stack (codegen through KSP in `:core`, or reflection), it would need its own lenient adapters, and its `JsonReader` streaming gains nothing here, since every item becomes a small tree anyway.

### 6.6 Conventions (all WPs)

- **Dispatchers:** `:core` never touches dispatchers. `:data` injects `@IoDispatcher`/`@DefaultDispatcher`, and `Dispatchers.IO` appears only in `DispatchersModule`.
- **Errors:** blocking `:core` APIs throw `SourceException(SourceError)`. `:data` suspend APIs return `Outcome<T>`. Raw `IOException`/`SQLiteException` never cross the module boundary (map them to `Network`/`Internal`). `CancellationException` is always rethrown, never wrapped. Workers map `Outcome.Err` to `Result.retry()` (network, 429, 5xx) or `Result.failure()` (auth, format, shrink, disk, credentials), and write `refresh_log` in both cases.
- **Time:** inject the clock (`() -> Long` or `java.time.Clock`). No `System.currentTimeMillis()`/`Instant.now()` inside logic classes.
- **Logging:** `BoxLog` only, redacted. No per-row logging. Tag = class simple name. Log once per source or batch, with counts.
- **Strings and allocation in hot loops** (parsers, importer, window): no regex per row (the M3U lexer and `XmltvTime` are hand-written), no `String.format`, no boxing collections. Use `androidx.collection` primitive maps.
- **Naming:** contract implementations are `*Impl` (`:core`) or named by technology (`Room*`, `OkHttp*`, `Tink*`) in `:data`. Tests are `<Class>Test`; integration tests `*IT`; perf tests `*PerfTest` with `@Tag("perf")`.
- **Public API:** everything not in §1.2 is `internal` unless WP-D needs it, in which case the doc for that WP lists it (§1.3–1.6).

---

## 7. Decisions made and alternatives rejected

| # | Decision | Rejected alternative | Why |
|---|---|---|---|
| D1 | `epg.db` engine (schema, importer, reader, generation swap) in **`:core`** on the KMP `androidx.sqlite` API | `EpgStore` in `:data` (Android) | JVM tests on the identical bundled SQLite (FTS5, `WITHOUT ROWID`), perf tests without a device, and WP-B is fully decoupled from Android. Cost: `:core` depends on `androidx.sqlite`, which is still pure JVM. Fallback in §6.2 |
| D2 | **Download to file, then parse** (two passes for playlists) | Parse straight from the HTTP body | Validation before any write (shrink guard, HTML/HLS detection, credential vote), an exact truncation rule, last-good copies for retry and patch. Costs one extra local parse (≈ 1–2 s for 200k) |
| D3 | Playlist merge in **2,000-row transactions + a final "missing" transaction** | One transaction for the whole refresh, or a SQL staging table + `INSERT … SELECT` merge | One long transaction blocks user writes (favourite toggles) for tens of seconds. A staging merge would move the diff logic into WP-D's SQL and lose the in-memory report counters. Batches are safe because nothing is deleted or marked missing until pass 2 completes |
| D4 | **Insert in arrival order + per-channel repair pass** | Staging table + `ORDER BY` external sort, or an in-memory per-channel buffer | No sort of 1M rows, bounded memory, correct for interleaved files. (reviewed 2026-10-13: the "temp directory trap" does not exist in this build, which uses `TEMP_STORE=3`. The real reason is now memory, since any SQL sort spills to RAM; §2.3, I-5.) |
| D5 | **Raw EPG times stored; shift applied when reading** | Shift applied at import | A shift change is instant (no re-import); catch-up and reminders use raw times by construction (protocols §2.6; Kodi subtracts tvg-shift for catch-up) |
| D6 | **Patch in place** (rollback journal, `busy_timeout`) for new mappings; **in-place** fallback for low disk | Copy the generation and rebuild, or WAL on published files | A mapping must show data within a minute (B3.3). Copying 50–150 MB per mapping is wasteful. WAL would need `-shm` writes next to files readers open read-only |
| D7 | **Whitelist by callback after the `<channel>` block**, every `<channel>` stored | A two-pass XMLTV parse, or a whitelist computed only from playlist tvg-ids | One pass. Name matches get into the whitelist. Unmapped channels are still listable in the picker |
| D8 | Matcher **indexes library subjects and streams EPG channels**, with token blocking and a digit guard | Indexing EPG channels; brute-force fuzzy | Memory scales with the user's channels (≤ 20k), not the global EPG (≥ 50k). Near-linear time. The digit guard prevents the classic `Sky Sports 1 ↔ 2` mistake |
| D9 | Fuzzy matching **suggests by default**, auto only as an opt-in level | Auto ≥ 0.92 by default (protocols §2.5 wording) | Product §4.3 is explicit ("wrong mappings are worse than none"). The thresholds are as specified |
| D10 | kotlinx-serialization, **tree per item** | Annotated DTOs with `JsonTransformingSerializer`s; Moshi | Every Xtream field is unreliable, so per-field serializers would be everywhere, and `info: []` and episodes-map-or-array need tree logic anyway (§6.5) |
| D11 | kxml2 (`compileOnly`) through an **injected `XmlPullParser` factory** | A hand-written XML tokenizer; StAX (not on Android); xmlutil | The platform parser is proven and fast (50–150k programmes/s on an A53). A custom tokenizer is risky (entities, CDATA, encodings) for a marginal gain |
| D12 | Charset: **strict UTF-8, then a 1251/1252 heuristic per line**, plus a user override | ICU `CharsetDetector` (API 24+, but not available on the JVM for tests), juniversalchardet (another dependency) | Covers the real cases (Latin-1/1252 Western lists, 1251 Cyrillic) with no dependency. The override handles the rest |
| D13 | **Credentials templated out of stored URLs** (`{{u}}`/`{{p}}`), secrets in one AEAD blob per source | Encrypting every URL column; storing URLs in clear | Encrypting 200k URLs costs time and makes the URL useless for diffing. Clear text contradicts "credentials via Tink". The template also lets a password change apply without rewriting every channel |
| D14 | **Duplicate keys get `~n` suffixes**; multi-source merging waits for v1.1 (`stream_source` table ships empty) | Collapse duplicates into one channel with backup sources now | Scope: v1.1 owns failover UX. Suffixes keep each duplicate addressable and stable as long as order is stable |
| D15 | **User data never on refresh-owned rows** (`group_prefs` separate from `channel_group`) | Hidden/locked/sort columns on `channel_group` | Groups get purged and recreated like channels. One rule for both avoids a class of "my hidden group came back" bugs |
| D16 | Mass-remap **detected after applying** (soft delete keeps old rows for the grace period) | Blocking the refresh behind a prompt | Workers run headless. The grace period gives the user 7 days to accept the remap with nothing lost |
| D17 | Xtream workers fetch **live + radio only** in M1; VOD/series decoders exist and are tested | Fetching all content types on every refresh | `get_vod_streams` can be 50–100 MB, and VOD browsing is v1.1. M3U VOD entries are stored anyway because they are in the same file (S5 "kept for later") |
| D18 | xz decoded in-stream with a **16 MiB dictionary cap** | Decompress to a temp file first | Typical XMLTV `.xz` files use preset 6 (8 MiB). Revisit in M6 if a real provider exceeds the cap or the heap gate fails with xz |
| D19 | Room tests on **Robolectric + `AndroidSQLiteDriver`**; bundled driver in the instrumented suite | Instrumented-only Room tests; Room on JVM (KMP) | The Android variant of the bundled driver cannot load on the host JVM. Instrumented-only is too slow for daily work. Room-on-JVM would need a second Room setup |
| D20 | `epg_match` is a **cache table** separate from user overrides (`epg_mapping`) | One mapping table with a "source" column | Re-matching may wipe the cache freely. Overrides are user data and are only migrated |
| D21 | Archive-days default **3 days** when a channel is archive-capable but gives no depth (M0 L11) | Off; 7 days | Off hides real archives (the L11 bug). 7 days produces more dead ↺ cells than 3. The user override exists, and M4's probe corrects the mode, not the depth |
| D22 | **Window query per channel** with one re-bound statement | One `IN (…)` query per window | A bounded PK range scan per channel, no 999-parameter chunking, no result sort. 36 statement steps per page measure well under 1 ms on the JVM |

Open items for the reviewer:

- **(a)** The 70 % mass-remap and 20 % shrink thresholds are from the review, not measured.
- **(b)** Whether `{Y}{m}{d}` tokens on M3U playlists should default to UTC (product) or device time (Kodi's behaviour, which lists may be tuned to). This design follows the product spec, and M4's probe can't detect a zone error, so the per-playlist setting is the escape hatch.
- **(c)** Whether `get_series_info`'s `series=` fallback is worth a second request.
- **(d)** The `(verify)` items: `sqlite-bundled` JVM resolution and FTS5, the read-only open flag, `SQLITE_TMPDIR`, the kxml relaxed feature on Android, Robolectric SQLite with `WITHOUT ROWID`/FTS4, the Tink 1.23 API names, and whether Room with a driver enables `foreign_keys`.

(reviewed 2026-10-13) Answers, with evidence in `review/m1-design-review.md` §A:
- (a) Keep the thresholds as named constants. Mass remap now counts LIVE+RADIO only, and single-key changes are handled automatically (S7). Tune from beta refresh reports.
- (b) Keep UTC for M3U template tokens: CLAUDE.md forbids a device-zone default. It is now `TimeBase.templateZone`, and it no longer leaks into `xc` (B1).
- (c) Keep the `series=` retry code, but series is v1.1, so it is not a merge gate.
- (d) Verified: the JVM variant + FTS5 (ran), the read-only flag (ran; writes rejected), Tink names (jar), and Room `foreign_keys` (generated per connection). Moot: `SQLITE_TMPDIR` (`TEMP_STORE=3`). Still open: the platform parser's relaxed behaviour (I-8) and Robolectric SQLite (day-0 check 5).
