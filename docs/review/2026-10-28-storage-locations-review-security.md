# Save location for pause and rewind: security review (2026-10-28)

Scope: `git diff e8b1f7d^ HEAD` at `5d99f57`. Read-only review, no Gradle run. Findings already raised in
`docs/review/2026-10-24-storage-locations-review-core.md` / `-ui.md` are not repeated. C-N4 (`ManualAddressParser` accepts
`..` and control characters) and C-N5 (a submission dropped by another collector) are still open as deferred items
(design §"Implementation record — fix pass", lines 2094–2118); they are not re-reported here.

## Verdict

No blockers. The credential path holds: passwords are stored only in `SecureBlobStore` (`smb-<id>`), never in the
backed-up `storage` domain, never in a log line, a `toString`, the `/api/state` mirror or a toast. The phone endpoint is
behind the same Host / `X-TheBox` / Origin / pairing-cookie gate as M5 and is purpose-scoped. The 445 sweep cannot leave
the /24, is bounded and runs only while Find is collected. There are no debug hooks in `src/main`. What the earlier
reviews missed is mostly **hostile-LAN-host robustness** and **two broken credential promises**:
- a LAN host the viewer taps on Find can crash the app with one small srvsvc answer (S1), or exhaust memory or wedge
  `box-smb` with an endless directory listing (S2);
- the phone sign-in silently swaps the address the viewer picked for an unauthenticated discovered name, and breaks for
  names with spaces (S3);
- every TV-side sign-in leaves an un-zeroed copy of the password on the heap (S4).

All four fixes are small. I would fix S1–S4 before release; the Nice-to-haves can wait for M6 hardening.

## Blockers

None.

## Should-fix

### SEC-S1. A hostile SMB host crashes the app through the srvsvc share listing (uncaught `OutOfMemoryError`)

- **What.** dcerpc 0.12.13 sizes the share array from the server's `EntriesRead` before it reads any entry.
  `ShareInfoContainer.unmarshalEntity` does `readIndex("EntriesRead")` (accepts up to `Integer.MAX_VALUE`) and then
  `createBuffer(n)` = `new ShareInfo1[n]`. I verified this with `javap` on the cached jar.
- **Where.**
  - `SmbShareLister.kt:30` calls `getShares1()`. It catches `Exception` (`:31`) and `LinkageError` (`:35`), but not
    `OutOfMemoryError`.
  - The error then reaches `NetworkFoldersImpl.shares` (`NetworkFoldersImpl.kt:240-243`) and `StoragePages.ensureShares`
    (`StoragePages.kt:496-503`), which catches `Exception` only.
  - `StoragePages.scope` (`:159`) has no `CoroutineExceptionHandler`, so the error is uncaught and the process dies
    (ACRA crash).
- **How to trigger.**
  - A device on the LAN runs a small SMB server (impacket is enough) that accepts anonymous logon. Its srvsvc
    `NetrShareEnum` reply carries `EntriesRead = 0x7FFFFFF0`.
  - It advertises `_smb._tcp` as "NAS", or just listens on 445 so the sweep finds it.
  - The viewer opens Find and taps it. The app crashes at once, and again on every tap.
  - A value of about 50 M (200 MB of references) is enough on a 192 MB heap.
- **Fix (minimal).**
  - In `SmbShareLister.list`, add `catch (e: OutOfMemoryError) { return ShareListing(emptyList(), BrowseError(LIST_FAILED)) }`.
    Only the single huge array allocation failed, so the heap is intact.
  - Belt and braces: give `StoragePages.scope` a `CoroutineExceptionHandler` that logs the class name, so no browse job
    can kill the process.
  - Unverified: dcerpc's fragment reassembly may also buffer an unbounded number of fragments. The same catch covers it.

### SEC-S2. Directory listings from the server are unbounded; the keeper's sweep can wedge `box-smb`

- **What.** `SmbjShare.list(path, max)` calls smbj's `DiskShare.list(path)`. That is `Directory.list` → it iterates
  `QUERY_DIRECTORY` until `STATUS_NO_MORE_FILES` and keeps every entry in an `ArrayList` (verified with `javap`). Only
  then does `.take(max)` apply.
- **Where.**
  - `SmbjRemoteLink.kt:210-223`.
  - Callers:
    - browse `folders()`, `NetworkFoldersImpl.kt:255` and `:269`, with `Int.MAX_VALUE`;
    - the sweep, `SmbSweeper.kt:40` (`LIST_MAX` = 4,096, applied after the full read), which runs on `box-smb` from
      `onLinkUp` (`SmbSessionKeeper.kt:354`).
- **How to trigger.**
  - A hostile host (S1's setup), or a broken NAS, answers every `QUERY_DIRECTORY` with a new batch and never says
    NO_MORE_FILES.
  - Browse: memory grows until OOM. `folders()` runs on `box-smb-browse` and the OOM escapes the same way as S1.
  - Keeper: the sweep never returns, so `box-smb` is held: no reconnects, no posted CLOSEs, no keepalives. The watchdog
    cannot help, because `abortInFlight` only closes a connection while `SmbjShareOpener.open` runs (`inFlight`,
    `SmbjRemoteLink.kt:152-166`). Rewind then sits in fallback until the app restarts.
  - Each response is within the I/O timeout, so C-S7's "stop at the first SLOW" never fires.
- **Fix (minimal).**
  - Implement `list` with `disk.openDirectory(...)` and `Directory.iterator(FileIdBothDirectoryInformation::class.java, null)`.
  - Stop after `max` entries and close the directory. Pass `SMB_LIST_MAX_ENTRIES + 1` from `folders()` so truncation
    still shows.
  - Add a wall-clock bound, for example `2 × ioTimeoutMs` for the whole listing, so a trickling server cannot hold the
    thread.

### SEC-S3. The phone sign-in replaces the picked IP with the discovered (unauthenticated) name, and fails for names with spaces

- **What.**
  - For a computer picked on Find, the session holds `host = c.address` (the IP) and `computerName = c.name`
    (`StoragePages.kt:877`). The name comes from NSD, NetBIOS or reverse DNS: none of these are authenticated.
  - The phone context sends `PhoneContext(s.computerName ?: s.host, …)` (`StoragePages.kt:1252`).
  - `app.js` puts that name into the read-only Computer field (`app.js:292`) and posts it back as `host` (`app.js:393`).
  - `onPhone` sees `parsed.host != s.host`, so it clears `computerName`/`lastAddress` and **sets `s.host` to the name**
    (`StoragePages.kt:1266-1269`).
  - From then on the TV signs in by name, and later saves the location by name. The address the viewer picked on screen
    is gone.
- **Consequences.**
  - (a) Security: the NTLM exchange and the stored location go to whatever that name resolves to (DNS search domain, or
    later the `.local` NSD fallback, which any LAN host can answer). Neither is the address the viewer chose.
  - (b) Function: a name the server-side `splitFolder` rejects is refused with 400 "bad folder" (`PhoneApi.kt:213`,
    `HOST_OK`). Examples are the NSD names macOS uses, like "Shawn's MacBook Pro". The viewer has to find "Change" and
    retype the address.
  - The same applies to a stored location's re-sign-in: `computerName` is the user's label (`StoragePages.kt:760`, for
    example "Living room NAS"). `splitFolder` validates the host before `onPhone` would ignore it, so the phone gets 400.
- **How to trigger.** Find → pick a NetBIOS-named PC (for example `DESKTOP-7F3K2LQ` at 192.168.1.20) → "Sign in on your
  phone" → Send. The TV now connects to `DESKTOP-7F3K2LQ` by DNS, not to 192.168.1.20.
- **Fix (minimal).**
  - Send the address, not the label: `PhoneContext(host = s.lastAddress ?: s.host, …)`. Add a separate display-only
    `name` field if the page should show the name.
  - In `onPhone`, treat a host equal to `s.host`/`s.lastAddress` as unchanged.
  - For a stored session (`locationId != null`), skip host validation, or send the stored host.

### SEC-S4. Every TV-side sign-in and stored-location open leaves an un-zeroed password copy on the heap

- **What.** `BrowseSession.passwordCopy()` hands a fresh `CharArray` to `folders.open(...)`: `StoragePages.kt:767`
  (`openStored`) and `:1058` (`startConnect`). Its KDoc claims "those zero what they are given" (`:121`). But
  `NetworkFoldersImpl.open` documents and implements the opposite: "the caller's array stays the caller's"
  (`NetworkFoldersImpl.kt:121-122`). It zeroes only smbj's copy (`:234`) and its own stored-password copy (`:177`).
- **Result.** Nobody holds a reference to that copy, so it can never be zeroed. That is one cleartext copy per sign-in
  attempt, per Retry and per stored-location Test. This breaks the §7.3/§8 promise that the review's C-S6 fix was meant
  to restore. `saveSmb`/`updateSignIn` do zero their copies (`StorageLocationsImpl.kt:126`, `:143`).
- **Fix (minimal).** In both call sites, `val pw = s.passwordCopy(); try { folders.open(a, pw) } finally { pw?.fill('\u0000') }`.
  Correct the KDoc at `StoragePages.kt:121`.

## Nice-to-have

### SEC-N1. SMB user names are not registered for log redaction, and smbj's auth exception message contains the user name

- **What.**
  - smbj 0.15.0 throws `SMBApiException("Authentication failed for '<username>' using …")`
    (`SMBSessionBuilder`, verified with `javap`). `AuthenticationContext.toString()` prints the user name and domain too.
  - The storage code logs `faultName` only, so nothing leaks today.
  - But `BoxLog`'s sink runs stack traces through `Redact.text` (`TheBoxApplication.kt:81`), and SMB user names are
    never registered there. Only a phone submission's user is, as a transient (`PhoneApi.kt:223`). The export audit
    (`DiagnosticsImpl.kt:134`) covers the export file, not logcat.
  - So any future `BoxLog.w(TAG, "...", e)` on an auth path, or an `SmbIoException` cause reaching a throwable log
    (`TimeshiftSessionFactoryImpl.kt:538/545`, `PlaybackControllerImpl.kt:262`), would print the user name.
- **Fix.** In `StorageLocationsImpl.onPrefs`, `Redact.registerSecret(smb.user)` and `registerSecret(smb.domain)` for
  every SMB location (they are already read there). Optionally, add a `SmbFaultsTest` vector that `faultName` of an
  auth failure contains no user.

### SEC-N2. The phone-submitted password lives for the process lifetime in `Redact`'s transient set

- **What.** `Redact.registerTransient(password)` (`PhoneApi.kt:224`) keeps the password `String` in a 64-entry LRU that
  is rarely evicted (`Redact.kt:22`, `:29`). The careful buffer zeroing (`readJsonFixed`, `WipingBufferedInputStream`,
  `StreamBody`) then buys little: a cleartext copy is kept on purpose until 64 newer transients arrive. This is the same
  pattern M5 uses for the backup passphrase, so it is a known trade-off, not a regression.
- **Fix.** Either accept and document it in §8 next to N-3, or drop the password registration: it is never logged, and
  nothing on this path logs request bodies. Keep the user registration.

### SEC-N3. The keeper's and the browser's SMB sockets are not bound to the LAN network (VPN), unverified on device

- **What.** Discovery binds its sockets to the non-VPN Wi-Fi/Ethernet network (C-S5). `SmbjClients.config` uses a plain
  `ProxySocketFactory` (`SmbjRemoteLink.kt:78`), so the ring link and the browse/Test connections follow default
  routing.
- **Effect.** With a full-tunnel VPN that does not exempt RFC 1918 (common among IPTV users), the NTLMv2 exchange and the
  whole ring stream to `192.168.x.y` go into the tunnel. They reach whatever host has that address on the provider's
  side, or fail.
- **Fix.** When `lan.vpnActive()`, build the `SmbConfig` socket factory from the LAN `Network.socketFactory` (the same
  `networkOf` lookup as `AndroidFinderEnvironment`), or refuse to authenticate. Verify on a device with a full-tunnel
  VPN first.

### SEC-N4. A restored backup can point rewind at any host, unvalidated, with no confirmation

- **What.** The `storage` domain is backed up (`StorageContracts.kt:54`). A restored guest or anonymous SMB location
  chosen for TIMESHIFT makes the keeper connect, write `.wake-*` and stream the ring at the next start
  (`SmbSessionKeeper` `evaluate`/`connect`, `TheBoxApplication.kt:97-101`). No prompt appears, and
  `host`/`share`/`path` are not re-validated: there is no `splitFolder`-style check on restore.
- **Exploit.** This needs the viewer to restore someone else's encrypted backup with its passphrase, so the risk is low.
- **Fix.** On restore, validate each SMB address (host grammar, no `..` or control characters in share/path) and reset a
  chosen SMB location to this device until a Test passes. That is the A-N7 USB rule, extended.

### SEC-N5. The "Saved" badge on Find can be claimed by name

- **What.** `SavedHosts.matches(address, name)` matches a saved location's host *name* against any discovered name,
  NSD or NetBIOS included (`DiscoveryMerge.kt:95`). A LAN host that advertises "NAS" gets the Saved badge and is sorted
  first. The viewer may then type their NAS password into it.
- **Fix.** Badge by address only: the saved IP or `lastAddress`. Or badge a name match only when it also resolves to the
  saved `lastAddress`.

### SEC-N6. App-wide `-dontwarn javax.security.auth.**` and `-dontwarn java.rmi.**`

- **What.** `app/proguard-rules.pro:18`, `:31`. These silence missing-class warnings for **every** library, not only
  smbj and dcerpc. A future dependency that really needs one of these classes would build cleanly and fail at runtime.
  Nothing else in the rules is broad: there is no `-dontobfuscate`, no keep of Tink, and the global `-keepattributes`
  is harmless.
- **Fix.** Prefer targeted `-dontwarn` entries for the referencing classes, or keep the rules as they are with a
  release-variant smoke test (C-N6).

### SEC-N7. SMB signing is optional and encryption is not requested (accepted in the design)

- **What.** `withSigningRequired(false)` (`SmbjRemoteLink.kt:84`) and no encryption request, as decided in §4.3
  (design line 341). A LAN attacker in the path can read or alter ring data and directory listings. Altered ring data is
  then parsed by Media3/FFmpeg. The input is no less trusted than the provider's plain HTTP, so this is recorded for the
  threat model only.
- **Fix.** None needed now. Consider "require signing when the server offers it" later, as a per-location setting.

## Checked and fine

- **Phone endpoint gate.**
  - Host must equal `{ip}:{port}` (`PhoneApi.kt:101-102`).
  - Every POST needs `X-TheBox: 1` and a matching Origin when one is sent (`:103-107`).
  - `/api/network-folder` is `gated` (pairing cookie and the paired address, `:154-160`) and `onlyFor(NETWORK_FOLDER)`
    (`:125-126`).
  - `/api/state`, which carries the folder context and the shares, is gated (`:117`).
  - The form fallback (`app.html` `action=/api/network-folder`) has no `X-TheBox` header and gets 403.
  - Cross-origin requests need a preflight, which the server does not answer.
- **Phone payload handling.**
  - Body ≤ 64 KiB (`LanLimits.kt:16`, `PhoneApi.kt:272`), read into a fixed array and zeroed (`:270-286`).
  - The chunk buffer and the read-ahead buffer are wiped (`HttpParser.kt` `StreamBody`, `LanHttpServerImpl.kt:191-194`,
    `:244-249`).
  - Field lengths are capped (`:216-221`).
  - `JsonDecodingException`, whose message would echo the body, is caught and not logged (`:279-283`).
  - `splitFolder` refuses `..`, control characters, a path without a share and a bad host or port (`:483-512`).
  - One submission at a time, `PAIR_MAX_WRONG` sign-ins, then 410 (`:203-261`, `LanPhoneLink.kt:180-198`).
  - A stored location's re-sign-in ignores the phone's host (`StoragePages.kt:1264`), so a phone cannot redirect a
    stored password.
- **No HTML injection.** `app.js` writes server data (shares, message, received, host) only through `textContent` or
  `value` (`app.js:33-78`, `288-365`). No `innerHTML` anywhere. The CSP forbids inline script (`PhoneApi.kt` `CSP`).
- **Credential storage.**
  - `putPassword` and `readPassword` zero the UTF-8 copies (`StorageLocationsImpl.kt:186-200`, `281-287`).
  - `encode` and `decode` avoid intermediate Strings (`:311-324`).
  - Remove deletes the blob first (`:159`). Guest deletes the blob (`:137`).
  - smbj's context copy is zeroed after `authenticate` (`NetworkFoldersImpl.kt:234`, `SmbjRemoteLink.kt:155`).
  - The keeper zeroes the password it read (`SmbjRemoteLink.kt:377`).
  - The phone `CharArray` is zeroed after copy (`StoragePages.kt:1279-1280`) and on 503 (`PhoneApi.kt:391`).
  - `BrowseSession.zero()` runs at walk end (`StoragePages.kt:1154-1170`).
- **`toString` masking.**
  - Masked: `SmbAddress` (`StorageContracts.kt:25`), `PhoneSubmission.NetworkFolder` (`LanContracts.kt:30`),
    `PasswordRead.Ok` (`SmbjRemoteLink.kt:134`), `BrowseSession` (`StoragePages.kt:128`), `RowIntent.Edit`
    (`SettingsContracts.kt:157-159`).
  - `LinkKey` (a private data class holding `user`) is never logged (`SmbSessionKeeper.kt:133-136`).
- **Log lines.** Every `BoxLog` call added in the diff carries an id, a mode, a count, a status, `faultName` or
  `javaClass.simpleName`: no host user, password, share path or query. `SmbIoException`'s message is the fault name
  (`SmbFaults.kt:27`). smbj's slf4j stays unbound: only `slf4j-api` is in the Gradle cache, and no binding is declared.
- **Export and backup.**
  - The settings snapshot prints strings as `<set>` (`ExportBundle.kt:42`).
  - The audit adds SMB user, domain and password (`DiagnosticsImpl.kt:134`).
  - `smb-*` blobs are not in backups (`DataContracts.kt` `REF_SMB_PREFIX` KDoc; reviewer-verified in `BackupServiceImpl`).
  - `device-id` and `smb-last-good` live in `noBackupFilesDir` (`StorageModule.kt`, `DeviceIdentity.kt`).
- **NTLM.** `NtlmAuthenticator` uses `NtlmV2Functions` (`javap`). DFS is disabled (`SmbjRemoteLink.kt:85`).
- **Sweep and deletion rule.**
  - Only `<path>/TheBox-rewind` is listed (`SmbSessionKeeper.ringDir`).
  - The device id is exactly 12 hex characters from `SecureRandom` (`DeviceIdentity.kt:37-41`), so `ring-<id>-` cannot
    prefix-collide with another box.
  - Foreign `ring-`/`test-`/`.wake-` files are deleted only when they are more than 48 h older than our own `.wake`, by
    server time, through an exclusive delete-on-close open (`SmbSweeper.kt:59-80`).
  - Directories are skipped (`:56`). Any other name is never touched (`:70`).
  - A hostile server can only make us delete files on itself.
- **445 sweep.**
  - Targets are .1–.254 of the RFC 1918 /24 of the Wi-Fi/Ethernet address, minus our own (`DiscoveryMerge.kt:51-64`),
    re-checked per probe (`Port445Sweep.kt:111`).
  - At most 32 in parallel with a 400 ms connect timeout (`StorageConstants.kt:112-114`). Literal addresses, no bytes
    sent.
  - Sockets are bound to the non-VPN network, and the sweep is refused when a VPN is up and nothing is bindable
    (`ComputerFinderImpl.kt:216-240`, `Port445Sweep.kt:39-52`).
  - Total time is bounded at 8 s (`ComputerFinderImpl.kt:141`).
  - The sweep is reachable only from `StoragePages.findComputers` → `ensureFind` (`StoragePages.kt:394`, `436-453`) and
    stops on close or pause (`:456-461`, `:1216-1219`).
  - The only debug route that opens it, `findcomputers`, is in `app/src/debug` (`SettingsDebugProbe.kt:179-182`).
- **NSD, NBNS and DNS bounds.**
  - NSD: at most 128 services (`NsdFinder.kt:190`, `:249`).
  - NBNS: 1 KiB receive buffer, answers accepted only from the asked host with our transaction id (`NameLookup.kt:79-92`).
    The parser is bounds-checked, with no allocation sized by packet counts (`NbnsCodec.kt:85-122`).
  - Reverse DNS: a bounded pool and queue (`NameLookup.kt:101-104`).
  - Names are stripped of control characters and capped at 63 characters (`DiscoveryMerge.kt:171-175`).
  - A found computer is connected by IP, not by name (`StoragePages.kt:877`), except for the phone path in S3.
- **Debug hooks.** `StorageDebugEntryPoint` and the new probe routes are in `app/src/debug` only. The `thebox-debug`
  scheme is declared only in `app/src/debug/AndroidManifest.xml`. `benchmark` uses `initWith(release)` and does not get
  the debug source set. There is no new `BuildConfig.DEBUG` branch in `src/main`.
- **R8.** No `-dontobfuscate`, `-dontshrink` or blanket keep. Keeps are scoped to mbassador, smbj events, enums,
  `msfscc.fileinformation` and `NtlmAuthenticator`. Nothing touches Tink or Keystore classes (`app/proguard-rules.pro:6-35`).
- **Manifest.** The only new permissions are `ACCESS_WIFI_STATE` and `CHANGE_WIFI_MULTICAST_STATE`, both normal. The
  multicast lock is released in `finally` (`ComputerFinderImpl.kt:106-114`).
- **USB targets.** The directory always comes from the platform's mounted-volume list matched by UUID
  (`StorageTargetsImpl.kt:125-127`). A restored or crafted `volumeUuid` cannot name a path.
