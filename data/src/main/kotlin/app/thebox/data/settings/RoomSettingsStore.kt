package app.thebox.data.settings

import androidx.room.useWriterConnection
import app.thebox.core.common.BoxLog
import app.thebox.core.settings.Domains
import app.thebox.core.settings.SettingsDomain
import app.thebox.core.settings.SettingsStore
import app.thebox.core.settings.SettingsTimings
import app.thebox.core.settings.state.NOT_BACKED_UP_DOMAINS
import app.thebox.core.settings.state.WP_A_DOMAINS
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.dao.AppSettingDao
import app.thebox.data.db.entity.AppSettingEntity
import app.thebox.data.di.ApplicationScope
import app.thebox.data.di.IoDispatcher
import app.thebox.data.di.WallClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The settings store over `library.db` `app_setting` (docs/design/m5-setup-settings.md §2.2, §0.4, §0.5).
 *
 * - [load] runs the one `SELECT * FROM app_setting` on IO (started in `Application.onCreate`); [ready] opens when it
 *   lands or after `SETTINGS_LOAD_TIMEOUT_MS` (defaults, logged), whichever is first.
 * - (reviewed 2026-10-16, S1) **Nothing is written for a domain before its stored document was read.** [update]s made
 *   earlier apply in memory at once (to the defaults) and are queued; when the read lands, even after the timeout, the
 *   stored document wins and the queue is replayed on it, then written.
 * - Writes are coalesced per domain (`SETTINGS_WRITE_DEBOUNCE_MS`) and run on IO; [flush] writes at once (ON_STOP,
 *   leaving Settings, before a backup). JSON is `ignoreUnknownKeys`, `encodeDefaults = false`, so a value equal to its
 *   default is not stored and a later default change reaches users who never touched it.
 * - [importInTransaction] (restore, §3.10.4) runs inside the caller's `useWriterConnection { immediateTransaction {} }`
 *   and cancels pending writes of the domains it replaces: a debounced write that was already waiting for the writer
 *   connection re-checks its generation once it gets it and is dropped.
 *
 * Main-safe: [update] and [flow] never block (one short monitor section). Values that are not user choices
 * (`PlaybackSettings.defaultUserAgent`, test hooks) never reach the store (the persisted adapters keep them).
 */
@Singleton
class RoomSettingsStore @Inject constructor(
    private val db: LibraryDatabase,
    private val dao: AppSettingDao,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val clock: WallClock,
) : SettingsStore {
    /** Test seams (Appendix B values in production). */
    internal var loadTimeoutMs = SettingsTimings.SETTINGS_LOAD_TIMEOUT_MS
    internal var debounceMs = SettingsTimings.SETTINGS_WRITE_DEBOUNCE_MS
    /** Test seam: delays the startup read (the S1 "slow read beyond the timeout" row). */
    internal var beforeRead: suspend () -> Unit = {}

    private val _ready = MutableStateFlow(false)
    override val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private class Slot<T>(val domain: SettingsDomain<T>, initial: T, var read: Boolean) {
        val state = MutableStateFlow(initial)
        /** Transforms made before the read landed (S1), replayed on the stored document. */
        val queue = ArrayList<(T) -> T>()
        var dirty = false
        var writeJob: Job? = null
        /** Bumped by a restore: a write encoded under an older generation is dropped. */
        var generation = 0L
    }

    private val lock = Any()
    private val slots = LinkedHashMap<String, Slot<*>>()
    /**
     * D-S4: what [importInTransaction] will publish once [commitImport] says the transaction committed (the slots it
     * touches and the closures that set their memory). Null when no import is pending.
     */
    private var pendingImport: PendingImport? = null

    private class PendingImport(val touched: List<Slot<*>>, val wasDirty: List<Slot<*>>, val publish: List<() -> Unit>)
    /** The stored rows once the startup read landed; null before. */
    private var stored: MutableMap<String, AppSettingEntity>? = null
    private var loadStarted = false

    init {
        // WP-A's domains exist from the start, so the read decodes them in one pass.
        synchronized(lock) { WP_A_DOMAINS.forEach { slotLocked(it) } }
    }

    override suspend fun awaitReady() {
        ready.first { it }
    }

    override fun load() {
        synchronized(lock) {
            if (loadStarted) return
            loadStarted = true
        }
        val startedAt = clock.now()
        scope.launch(io) {
            val rows = try {
                beforeRead()
                dao.all()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // An unreadable table: start from defaults. Writes stay possible (a later user change is a real choice).
                BoxLog.e(TAG, "settings read failed; defaults", e)
                emptyList()
            }
            val toWrite = ArrayList<Slot<*>>()
            synchronized(lock) {
                stored = rows.associateByTo(HashMap()) { it.domain }
                for (s in slots.values) if (applyReadLocked(s)) toWrite += s
            }
            toWrite.forEach { schedule(it) }
            _ready.value = true
            BoxLog.i(TAG) { "settings loaded: ${rows.size} documents in ${clock.now() - startedAt} ms" }
        }
        scope.launch {
            delay(loadTimeoutMs)
            if (!_ready.value) {
                BoxLog.w(TAG, "settings not read after $loadTimeoutMs ms: first frame uses defaults; changes are queued")
                _ready.value = true
            }
        }
    }

    override fun <T> flow(domain: SettingsDomain<T>): StateFlow<T> = synchronized(lock) { slotLocked(domain).state.asStateFlow() }

    override fun <T> update(domain: SettingsDomain<T>, transform: (T) -> T) {
        val write: Slot<T>?
        synchronized(lock) {
            val s = slotLocked(domain)
            val before = s.state.value
            val after = transform(before)
            if (!s.read) {
                s.queue += transform
                s.state.value = after
                write = null
            } else {
                s.state.value = after
                if (after != before) s.dirty = true
                write = if (s.dirty) s else null
            }
        }
        write?.let { schedule(it) }
    }

    override suspend fun flush() {
        val pending = synchronized(lock) {
            slots.values.filter { it.read && it.dirty }.onEach { it.writeJob?.cancel(); it.writeJob = null }
        }
        pending.forEach { write(it) }
    }

    override suspend fun export(): Map<String, JsonElement> {
        val out = LinkedHashMap<String, JsonElement>()
        val raw: Map<String, AppSettingEntity>
        val docs = synchronized(lock) {
            raw = stored?.toMap() ?: emptyMap()
            slots.values.filter { it.domain.backedUp && it.read }.associate { it.domain.name to encodeElement(it) }
        }
        out.putAll(docs)
        // Rows of domains nobody registered this process (another WP's domain not used yet): as stored.
        for ((name, row) in raw) {
            if (name in out || name in NOT_BACKED_UP_DOMAINS || synchronized(lock) { slots.containsKey(name) }) continue
            try {
                out[name] = JSON.parseToJsonElement(row.json)
            } catch (e: Exception) {
                BoxLog.w(TAG, "export: unreadable document ${row.domain}: ${e.javaClass.simpleName}")
            }
        }
        return out
    }

    override suspend fun importInTransaction(documents: Map<String, JsonElement>, replace: Boolean) {
        val now = clock.now()
        val rows = ArrayList<AppSettingEntity>()
        val deletes = ArrayList<String>()
        // D-S4: rows are written inside the caller's transaction; memory (slots, `stored`) changes only on commitImport.
        val touched = ArrayList<Slot<*>>()
        val publish = ArrayList<() -> Unit>()
        synchronized(lock) {
            pendingImport = null // an import that never committed is dropped
            for ((name, json) in documents) {
                val slot = slots[name]
                if (slot != null) {
                    if (!slot.domain.backedUp) continue
                    val (row, apply) = importSlotLocked(slot, json, now) ?: continue
                    rows += row; touched += slot; publish += apply
                } else if (name in KNOWN_DOMAINS && name !in NOT_BACKED_UP_DOMAINS) {
                    val row = AppSettingEntity(name, json.toString(), 1, now)
                    // A slot registered between the import and the commit reads the new row too.
                    publish += { stored?.put(name, row); slots[name]?.let { republishLocked(it, row) } }
                    rows += row
                }
            }
            if (replace) {
                for (slot in slots.values) {
                    if (!slot.domain.backedUp || slot.domain.name in documents) continue
                    touched += slot
                    publish += resetSlotLocked(slot)
                    deletes += slot.domain.name
                }
                stored?.keys?.filter { it !in documents && it !in NOT_BACKED_UP_DOMAINS && !slots.containsKey(it) }?.forEach {
                    publish += { stored?.remove(it); slots[it]?.let { s -> republishLocked(s, null) } }; deletes += it
                }
            }
            // Pending writes of these domains stop now (a debounced write must not overwrite the restored row); a
            // rolled-back import writes the dirty ones again (commitImport(false)).
            val wasDirty = touched.filter { it.dirty }
            for (s in touched) { s.writeJob?.cancel(); s.writeJob = null; s.generation++ }
            pendingImport = PendingImport(touched, wasDirty, publish)
        }
        rows.forEach { dao.upsert(it) }
        deletes.forEach { dao.delete(it) }
        BoxLog.i(TAG) { "settings import: ${rows.size} documents, ${deletes.size} reset (replace=$replace)" }
    }

    override fun commitImport(committed: Boolean) {
        val rewrite = synchronized(lock) {
            val p = pendingImport ?: return
            pendingImport = null
            for (s in p.touched) s.generation++
            if (committed) {
                p.publish.forEach { it() }
                emptyList()
            } else {
                p.wasDirty
            }
        }
        rewrite.forEach { schedule(it) }
        if (!committed) BoxLog.i(TAG) { "settings import rolled back: nothing published" }
    }

    // ---------------------------------------------------------------- internals (callers hold [lock])

    @Suppress("UNCHECKED_CAST")
    private fun <T> slotLocked(domain: SettingsDomain<T>): Slot<T> {
        slots[domain.name]?.let { s ->
            // Several WPs declare a domain object per name: one stored document per name needs one type.
            require(s.domain.serializer.descriptor.serialName == domain.serializer.descriptor.serialName) {
                "settings domain ${domain.name} declared with two types"
            }
            return s as Slot<T>
        }
        val rows = stored
        val s = Slot(domain, domain.default, read = rows != null)
        if (rows != null) s.state.value = decode(domain, rows[domain.name])
        slots[domain.name] = s
        return s
    }

    /** The read landed: the stored document, then the queued transforms. True when the result must be written. */
    private fun <T> applyReadLocked(s: Slot<T>): Boolean {
        if (s.read) return false
        var v = decode(s.domain, stored?.get(s.domain.name))
        val queued = s.queue.toList()
        s.queue.clear()
        for (t in queued) v = t(v)
        s.read = true
        s.state.value = v
        if (queued.isNotEmpty()) s.dirty = true
        return s.dirty
    }

    /** The row to write and (D-S4) what publishes it in memory after the commit; null when unreadable. */
    private fun <T> importSlotLocked(s: Slot<T>, json: JsonElement, now: Long): Pair<AppSettingEntity, () -> Unit>? {
        val v = try {
            JSON.decodeFromJsonElement(s.domain.serializer, json)
        } catch (e: Exception) {
            BoxLog.w(TAG, "import: ${s.domain.name} unreadable (${e.javaClass.simpleName}); kept")
            return null
        }
        val row = AppSettingEntity(s.domain.name, JSON.encodeToString(s.domain.serializer, v), s.domain.schema, now)
        return row to {
            s.writeJob?.cancel(); s.writeJob = null
            s.queue.clear()
            s.read = true
            s.dirty = false
            s.state.value = v
            stored?.put(s.domain.name, row)
        }
    }

    /** (D-S4) [s] takes the committed [row] (null: the default), dropping its queue and pending write. */
    private fun <T> republishLocked(s: Slot<T>, row: AppSettingEntity?) {
        s.writeJob?.cancel(); s.writeJob = null
        s.queue.clear()
        s.read = true
        s.dirty = false
        s.state.value = decode(s.domain, row)
    }

    /** (D-S4) What resets [s] to its default in memory after the commit. */
    private fun <T> resetSlotLocked(s: Slot<T>): () -> Unit = {
        s.writeJob?.cancel(); s.writeJob = null
        s.queue.clear()
        s.read = true
        s.dirty = false
        s.state.value = s.domain.default
        stored?.remove(s.domain.name)
    }

    private fun schedule(s: Slot<*>) {
        synchronized(lock) {
            s.writeJob?.cancel()
            s.writeJob = scope.launch(io) {
                delay(debounceMs)
                write(s)
            }
        }
    }

    private suspend fun write(s: Slot<*>) {
        val (row, generation) = synchronized(lock) {
            if (!s.dirty || !s.read) return
            s.dirty = false
            encodeRow(s) to s.generation
        }
        try {
            db.useWriterConnection {
                // A restore that ran while this write waited for the connection replaced the document: drop it.
                if (synchronized(lock) { s.generation } == generation) {
                    dao.upsert(row)
                    synchronized(lock) { stored?.put(row.domain, row) }
                }
            }
        } catch (e: CancellationException) {
            synchronized(lock) { s.dirty = true }
            throw e
        } catch (e: Exception) {
            synchronized(lock) { s.dirty = true }
            BoxLog.e(TAG, "settings write failed: ${s.domain.name}", e)
        }
    }

    private fun <T> encodeRow(s: Slot<T>): AppSettingEntity =
        AppSettingEntity(s.domain.name, JSON.encodeToString(s.domain.serializer, s.state.value), s.domain.schema, clock.now())

    private fun <T> encodeElement(s: Slot<T>): JsonElement = JSON.encodeToJsonElement(s.domain.serializer, s.state.value)

    private fun <T> decode(domain: SettingsDomain<T>, row: AppSettingEntity?): T {
        if (row == null) return domain.default
        return try {
            if (row.schemaVer < domain.schema) {
                JSON.decodeFromJsonElement(domain.serializer, domain.migrate(row.schemaVer, JSON.parseToJsonElement(row.json)))
            } else {
                JSON.decodeFromString(domain.serializer, row.json)
            }
        } catch (e: Exception) {
            BoxLog.w(TAG, "settings document ${domain.name} unreadable (${e.javaClass.simpleName}); defaults until changed")
            domain.default
        }
    }

    companion object {
        private const val TAG = "SettingsStore"

        /** §2.2: unknown keys ignored, defaults not encoded. */
        val JSON: Json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        /** §2.2 `Domains`: an import of any other name is ignored. */
        val KNOWN_DOMAINS: Set<String> = setOf(
            Domains.GUIDE, Domains.PLAYER, Domains.PLAYBACK, Domains.DATA, Domains.NETWORK, Domains.PARENTAL, Domains.REMOTE,
            Domains.BACKUP, Domains.UPDATE, Domains.SETUP,
            // (Storage locations commit 0, docs/design/m6-storage-locations.md §8) restored even before WP-S1 registers it.
            Domains.STORAGE,
        )
    }
}
