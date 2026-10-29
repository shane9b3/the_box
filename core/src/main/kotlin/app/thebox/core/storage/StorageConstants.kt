// Contract (M6 storage locations commit 0): docs/design/m6-storage-locations.md Appendix A. Every number the code uses
// is a default that lives in one named constant here. The §14 gate numbers (seek, allocation, APK budgets) live in the
// design's gate table, not here (closure fix pass, R7: nothing read them).
package app.thebox.core.storage

import app.thebox.core.playback.timeshift.TimeshiftConstants

/** Appendix A. Sizes in bytes, times in ms. The three pool constants live in [TimeshiftConstants] (§12.4). */
object StorageConstants {
    // ---------------------------------------------------------------- locations (§3.1, §3.3, §3.5)
    /** The implicit built-in location's id (never stored in `StoragePrefs.locations`). */
    const val INTERNAL_ID = "internal"
    /** Id prefixes of stored locations (§3.1). */
    const val VOLUME_ID_PREFIX = "vol-"
    const val SMB_ID_PREFIX = "smb-"
    const val SMB_DEFAULT_PORT = 445
    /** The ring folder inside the chosen SMB folder (§3.1). */
    const val TIMESHIFT_DIR_SMB = "TheBox-rewind"
    /** v2 recordings (§11). */
    const val RECORDINGS_DIR_SMB = "TheBox-recordings"
    /** The `.wake-<deviceId>` write (§5.3, §7.8). */
    const val WAKE_FILE_BYTES = 4_096
    /** `DeviceIdentity`: 12 lowercase hex digits (§3.5). */
    const val DEVICE_ID_HEX_CHARS = 12
    /** Another box's ring/test files go after this much **server** time past our `.wake` (§3.5, A-N2); 48 h. */
    const val FOREIGN_STALE_MS = 172_800_000L
    /** A location's name (U-N1); the Playback link value uses at most [LABEL_LINK_MAX_CHARS]. */
    const val LABEL_MAX_CHARS = 24
    const val LABEL_LINK_MAX_CHARS = 16

    // ---------------------------------------------------------------- limits per kind (§3.2, §3.4)
    /** The FAT32 file size limit (4 GiB − 1). */
    const val FAT32_MAX_FILE_BYTES = 4_294_967_295L
    /** The largest whole-block ring whose file (header included) stays ≤ [FAT32_MAX_FILE_BYTES]: 65,648 blocks. */
    const val VOLUME_MAX_RING_BYTES: Long =
        (FAT32_MAX_FILE_BYTES - TimeshiftConstants.RING_HEADER_BYTES) / TimeshiftConstants.RING_BLOCK_BYTES * TimeshiftConstants.RING_BLOCK_BYTES
    /** 512 MiB. */
    const val VOLUME_FREE_SPACE_RESERVE = 536_870_912L
    /** 32 GiB: 2 h of a 4K channel at 3.5 MB/s needs about 29 GiB (§3.4 "Why"). */
    const val NETWORK_MAX_RING_BYTES = 34_359_738_368L
    /** 2 GiB, flat (10 % of a 12 TB NAS would be 1.2 TB). */
    const val NETWORK_FREE_SPACE_RESERVE = 2_147_483_648L

    // ---------------------------------------------------------------- smbj and the link (§4.3, §4.4)
    const val SMB_CONNECT_TIMEOUT_MS = 3_000L
    /** Read/write/transact timeout with the 160-block pool: just inside the pool's ≈ 10.5 s at 1 MB/s (A-B4). */
    const val SMB_IO_TIMEOUT_MS = 8_000L
    /** The same with the 96-block pool (≈ 6.3 s). */
    const val SMB_IO_TIMEOUT_SMALL_POOL_MS = 4_000L
    /** Socket timeout; larger than [SMB_KEEPALIVE_MS]. */
    const val SMB_SO_TIMEOUT_MS = 45_000L
    /** A LINK_LOST reconnect gives up this long after the operation's first failure (never past its I/O timeout). */
    const val SMB_RECONNECT_BUDGET_MS = 6_000L
    /** At most one reconnect per this gap. */
    const val SMB_RECONNECT_MIN_GAP_MS = 2_000L
    /** `SmbConfig.withBufferSize`. */
    const val SMB_MAX_IO_BYTES = 262_144

    // ---------------------------------------------------------------- choosing the target, keeper (§5)
    /** The fallback switch deadline from the first `pwrite`, 160-block pool (§5.2). */
    const val SMB_OPEN_DEADLINE_MS = 6_000L
    /** The same, 96-block pool. */
    const val SMB_OPEN_DEADLINE_SMALL_POOL_MS = 3_000L
    /** Last-known-good counts this long (7 days, persisted across processes; §5.1). */
    const val SMB_LAST_GOOD_MS = 604_800_000L
    /** READY counts as fresh when the last good round trip is at most this old (§5.1). */
    const val SMB_FRESH_MS = 90_000L
    /** The keeper's cached free space is used while younger than this (§4.1, §5.1). */
    const val SMB_SPACE_MAX_AGE_MS = 120_000L
    /** The first connect waits for the first frame, or this long after launch, whichever is first (A-N6). */
    const val SMB_FIRST_CONNECT_DELAY_MS = 2_000L
    /** `getShareInformation()` while READY in the foreground (smbj has no echo). */
    const val SMB_KEEPALIVE_MS = 20_000L
    /** A hung smbj call is torn down after this (§13, facts §1.3 #875). */
    const val SMB_WATCHDOG_MS = 15_000L
    /** Keeper backoff steps; the last one repeats. Any trigger resets it (§5.3). */
    val SMB_BACKOFF_MS: List<Long> = listOf(5_000L, 15_000L, 60_000L, 300_000L)
    /** In the background the link disconnects after this, unless a file is open (A-S1). */
    const val SMB_BACKGROUND_DISCONNECT_MS = 30_000L
    /** A stop within this of the cycle's first successful write does not demote (§5.4). */
    const val SMB_FIRST_WRITE_GRACE_MS = 20_000L
    /** Demotion length, doubled for a repeat within [SMB_DEMOTE_REPEAT_WINDOW_MS], capped at [SMB_DEMOTE_MAX_MS]. */
    const val SMB_DEMOTE_MS = 120_000L
    const val SMB_DEMOTE_MAX_MS = 1_800_000L
    const val SMB_DEMOTE_REPEAT_WINDOW_MS = 600_000L
    /** SIGN_IN_NEEDED toasts in the first this-many processes after it starts, then never (U-S7). */
    const val SIGN_IN_TOAST_PROCESSES = 3

    // ---------------------------------------------------------------- folders (§7.6)
    const val SMB_LIST_MAX_ENTRIES = 2_000
    /** Folders the browser never shows. Compared case-insensitively. */
    val SMB_SKIP_FOLDERS: Set<String> = setOf("\$RECYCLE.BIN", "System Volume Information", "#recycle", "@eaDir", ".Trashes", ".TemporaryItems")

    // ---------------------------------------------------------------- Test (§7.8)
    /** The Test writes whole RING_BLOCK_BYTES blocks up to this (256 blocks = 16,748,544 bytes). */
    const val SMB_TEST_MAX_BYTES = 16_777_216L
    const val TEST_WRITE_MS = 6_000L
    const val TEST_READ_MS = 6_000L
    const val TEST_RTT_SAMPLES = 5
    const val TEST_RTT_SLOW_MS = 40L
    const val TEST_WAKE_NOTE_MS = 3_000L
    /** HD = the assumed stream rate (TS §8.2). */
    const val HD_BPS = TimeshiftConstants.ASSUMED_BYTES_PER_SEC
    const val UHD_BPS = 3_500_000L
    /** GOOD_* need rate ≥ this × the class rate; SLOW needs ≥ [TEST_SLOW_FACTOR] × [HD_BPS]. */
    const val TEST_GOOD_FACTOR = 3.0
    const val TEST_SLOW_FACTOR = 1.5
    /**
     * C-N7 (closure fix pass): the whole Test, from `TheBox-rewind/` to the free-space query, the first wake write
     * excluded (it is timed as `wakeMs`). Checked between round trips, so one round trip may overrun it by its I/O
     * timeout. The two rate phases take up to [TEST_WRITE_MS] + [TEST_READ_MS]; the rest leaves one I/O timeout for the
     * folder, the create, the RTT samples and the close. Past it: "Stopped: NAS took too long."
     */
    const val TEST_RUN_MAX_MS = TEST_WRITE_MS + TEST_READ_MS + SMB_IO_TIMEOUT_MS
    /** "Use for rewind" re-runs the Test when `lastTest` is older than this (30 days; §9.2). */
    const val TEST_FRESH_MS = 2_592_000_000L

    // ---------------------------------------------------------------- finding computers (§7.1)
    const val DISCOVERY_TOTAL_MS = 8_000L
    const val SWEEP_MAX_HOSTS = 254
    const val SWEEP_PARALLEL = 32
    const val SWEEP_CONNECT_TIMEOUT_MS = 400
    const val NBNS_PORT = 137
    const val NBNS_TIMEOUT_MS = 600L
    const val REVERSE_DNS_TIMEOUT_MS = 800L

}

/** Feature switches decided by the spikes (§0a). */
object StorageFlags {
    /** USB drives through `getExternalFilesDirs` (S-1; false if no target TV lists a stick). */
    const val VOLUME_ENABLED = true
}
