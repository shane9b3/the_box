# The Box: security review (2026-11-09, main @ 2246f66)

Review only: nothing in the repo was edited. Threat model as briefed: a hostile or compromised provider, LAN peers, a hostile network path (cleartext is accepted and not reported), malicious backup/import files, other apps on the device, and readers of the public repo.

Overall: the high-value surfaces (self-updater, LAN phone setup, credential storage, backup crypto, release key handling) are carefully built, and I found **no Critical or High issue**. The findings below are hardening items, mostly denial of service from a hostile provider or a LAN peer.

---

## Findings

### F1. Medium: XMLTV and Xtream parsers read single text tokens with no size limit (hostile-provider memory DoS)

- **Where:** `core/src/main/kotlin/app/thebox/core/xmltv/XmltvParser.kt:358-380` (`readText` appends every TEXT event with no cap). It is called for `title` (:199), `sub-title` (:208), `display-name` (:121) and `category` (:225, where the 32-candidate limit counts items, not bytes). `desc` is capped only *after* it is read whole (:217). Attribute values (`icon src`, `channel`, `start`) are not capped either.
- **Same pattern, Xtream:** `core/src/main/kotlin/app/thebox/core/xtream/XtreamJson.kt:90` (`decodeToSequence` streams items, but each item becomes a full `JsonElement`).
- **Bounds that do exist:** compressed download ≤ 2 GB (`DataContracts.kt:51`), XZ dictionary ≤ 16 MiB, ≤ 3 compression layers. Nothing bounds the decompressed size or the size of one token.
- **Attack:** the provider (or anyone who can rewrite the plain-HTTP EPG) serves a few-KB gzip that inflates to `<tv><programme …><title>` followed by hundreds of MB of text. The pull parser (KXmlParser) buffers the whole TEXT token, `readText` copies it again, and the string is written to `epg.db` and later loaded for the guide and measured by the renderer. The import runs in the app process (WorkManager foreground service), so an OOM kills the app while the user is watching. The scheduled refresh repeats it. Programmes are also never length-limited before insert, so a merely large title (a few MB) reaches the guide's text layout.
- **Confidence:** that no limit exists is confirmed by reading the whole parse path. The OOM or ANR on a device is plausible but was not reproduced.
- **Fix:**
  - Wrap the decompressed stream in a counting `InputStream` with a ceiling (for example 4 GB, or 20× the compressed size). Over the limit, fail with `Unsupported("guide larger than …")`.
  - In `readText`, stop appending past a per-field cap (title/sub-title/display-name/category 512 chars, desc `maxDescriptionChars`) and skip the rest of the element.
  - Cap attribute values the same way before use.
  - For Xtream items, reject any single item whose byte span exceeds about 1 MB (count bytes in `ObjectValuesAsArrayStream` or in a wrapper around the array stream).
  - Add a JVM test with a gzip bomb fixture.

### F2. Low: any LAN host can end or stall a phone-setup session before the phone pairs

- **Where:** `core/src/main/kotlin/app/thebox/core/setup/lan/PairingSession.kt:75-97`, and `LanHttpServerImpl.kt:111-117` with `LanLimits.HTTP_MAX_SOCKETS = 4`.
- **Attack (wrong codes):** before pairing, wrong codes are counted from *any* IP. A device on the same Wi-Fi scans port 47100 and sends 10 × `POST /pair` with `Host: <ip>:47100`, `X-TheBox: 1` and a random code. The first 5 rotate the code, the next 5 end the session with `TOO_MANY_WRONG_CODES`. The attacker can repeat this every time the user reopens the screen.
- **Attack (sockets):** holding 4 idle sockets, each renewed within the 15 s watchdog, keeps the real phone out.
- **Brute force is not possible:** 10 guesses against 10^6 codes per session, and the 128-bit QR token is never counted.
- **Confidence:** confirmed by reading.
- **Fix (pick one or both):**
  - Count wrong codes per source IP: the IP that hits 5 is blocked, and the session ends only after N distinct IPs fail.
  - Make the global exhaustion rotate the code rather than end the session, keeping the absolute lifetime as the hard stop.
  - Cap concurrent sockets *per remote IP* (for example 2) so one host cannot take all 4.

### F3. Low: `dataExtractionRules` is missing, so device-to-device transfer still copies app data on API 31+

- **Where:** `app/src/main/AndroidManifest.xml:51-62`. It has `android:allowBackup="false"` and no `android:dataExtractionRules`.
- **Why it matters:** from targetSdk 31, `allowBackup=false` turns off cloud backup but **not** device-to-device migration. Migration copies `library.db` (playlist names, hosts, favourites, reminders), the settings store, `parental_attempts` and the Tink keyset prefs. The Keystore-wrapped keyset is useless on the new device. But where Tink fell back to a **cleartext keyset** (`AeadProvider.kt:20`, Fire OS Keystore flakiness), the provider credentials migrate in decryptable form.
- **Who can exploit it:** whoever performs or receives a D2D transfer. This is mostly phones and tablets, which the manifest allows.
- **Confidence:** the manifest gap is confirmed. Whether TV devices actually perform D2D is unverified.
- **Fix:** add `android:dataExtractionRules="@xml/data_extraction_rules"` that excludes every domain (`<cloud-backup>` and `<device-transfer>` each with `<exclude domain="root"/>`, `file`, `database`, `sharedpref`, `external`). Keep `allowBackup="false"` and `fullBackupContent="false"` for API ≤ 30.

### F4. Low: debug builds export deep links any installed app can fire; CI publishes those debug APKs on the public repo

- **Where:** `app/src/debug/AndroidManifest.xml:5-39` (exported `PlaybackDebugActivity` and `GuideDebugReceiver`; hosts `wipe`, `keystore-reset`, `update`, `phone`, `settings`, `clock`, `guide?seed=…&user=&pass=`) and `.github/workflows/android.yml:44-49` (uploads `debug-apks`; artifacts on a public repo can be downloaded by any signed-in GitHub user).
- **Attack:** a user who sideloads a CI debug APK (it looks like "the latest build") can be hit by any app on the box with `thebox-debug://wipe`, `keystore-reset`, playlist seeding, or the update-endpoint override. No `BROWSABLE` category, so web pages cannot reach these links. The debug updater still requires the installed signer, so it cannot install a foreign APK.
- **Confidence:** confirmed by reading. The impact depends on users running debug builds.
- **Fix:**
  - Stop uploading debug APKs as artifacts, or mark them clearly and keep them short-lived (`retention-days: 3`).
  - Gate the debug receivers behind a signature-level permission (`android:permission` with `protectionLevel="signature"`) so only adb (`am start` from shell) or same-signer apps reach them.

### F5. Low: supply-chain hygiene in CI and the build

- **Where:**
  - `.github/workflows/android.yml:20-51` and `ffmpeg.yml`: actions pinned by tag (`actions/checkout@v7`, `gradle/actions/setup-gradle@v6`, `android-actions/setup-android@v3`, `softprops/action-gh-release@v2`), not by commit SHA.
  - `ffmpeg.yml` release job: grants `contents: write` to the third-party `softprops/action-gh-release`.
  - No `gradle/verification-metadata.xml`, so dependencies are not checksum-verified.
- **Already sound:** top-level `permissions: contents: read`, `pull_request` (not `pull_request_target`), the Gradle cache read-only off `main`, and the wrapper pinned with `distributionSha256Sum`.
- **Attack:** a compromised action tag or a substituted Maven artifact runs in CI or in the release build on the Mac. Release APKs are built locally, so a poisoned Maven/Google artifact is the realistic path.
- **Fix:**
  - Pin actions to full SHAs, with Dependabot for updates.
  - Generate `verification-metadata.xml` (`./gradlew --write-verification-metadata sha256 help`) and commit it.
  - Replace `softprops/action-gh-release` with `gh release create` from the runner.

### F6. Low: playlist/EPG fetch URLs are logged whole; path-embedded tokens are not masked

- **Where:** `data/src/main/kotlin/app/thebox/data/net/OkHttpSourceFetcher.kt:71`, `:102` and `:111` log `${request.url}`. `Redact.text` (`core/.../common/Redact.kt:50-69`) masks only userinfo, query strings, header-like `key=value`, and `/live|movie|series|timeshift/u/p/`.
- **Gap:** URLs with a token in the path (`http://host/playlist/<token>/m3u_plus`, `/get/<user>/<pass>/…`, the Xtream short form `/<user>/<pass>/<id>` behind a user-added EPG URL) go to logcat and to `filesDir/logs/current.log` unmasked.
  - The export partly compensates: `DiagnosticsImpl.inputs` (`:121-130`) adds every **playlist** secret URL to the audit list.
  - It does not add standalone **EPG source** URLs, so those reach an exported bundle in clear.
- **Who can read them:** logcat and `filesDir` are not readable by other apps, so the exposure is to whoever receives an exported log bundle (or adb).
- **Confidence:** confirmed for the log lines and the regexes. EPG URLs missing from `extraSecrets` was confirmed by reading `inputs()`.
- **Fix:**
  - Log `Redact.host(request.url)` plus the source id instead of the URL (matching the updater's "hosts only" rule).
  - Add EPG source URLs and their credentials to `extraSecrets` in `DiagnosticsImpl.inputs`.

### F7. Low: after a reboot, the parental lockout uses the wall clock, so setting the clock forward ends the wait

- **Where:** `core/src/main/kotlin/app/thebox/core/parental/LockoutPolicy.kt:51-63`. On another boot, `passed = now.wallMs - lockedAtWallMs`. Moving the clock *back* is capped, moving it *forward* is not.
- **Attack:** a child with access to Android Settings reboots, sets the date ahead, and gets 3 more guesses per cycle. That makes 10^4 PINs feasible in hours instead of weeks.
- **Context:** the same person can also use Settings → Apps → Clear data, which wipes the PIN outright (inherent to Android without device-owner). So this is a small extra route, not a new class.
- **Confidence:** confirmed by reading.
- **Fix (cheap):** when the boot differs, restart the full wait from the new boot's elapsed time rather than crediting wall-clock time. Store `lockedAtBoot` and `lockedAtElapsed` again on the first check after a reboot. Document "Clear data" as the known, accepted bypass.

---

## Worth a look (not verified end to end)

1. **SVG logos can trigger XML entity expansion.**
   - What is confirmed: `app/.../image/ImageModule.kt:46` registers Coil's `SvgDecoder` for **all** images, including provider `tvg-logo` URLs. Coil applies it on `Content-Type: image/svg+xml` or a sniff, both under provider control. AndroidSVG 1.4 (bytecode checked in the Gradle cache) switches to a SAX parser when it sees `<!ENTITY`. External entities are disabled, but internal entities are expanded by the platform's Expat.
   - What is unverified: Expat before 2.4 (the Android 7–11 Fire OS generations) has no billion-laughs amplification limit, so one hostile logo could pin a decoder thread or exhaust memory every time the guide shows that row. Not reproduced.
   - Fix: register `SvgDecoder` only on the request that loads NHL/ESPN team logos (a dedicated `ImageLoader`, or per-request `decoderFactory`), never on the shared loader that serves provider logos.
2. **XMLTV DOCTYPE on Android:** `XmltvParser.kt:49` turns `FEATURE_PROCESS_DOCDECL` off. In libcore's KXmlParser that should stop internal-subset entities from being defined (no billion laughs), but the JVM tests use kxml2, which ignores DTDs. Add an instrumented test with a billion-laughs XMLTV fixture to pin the Android behaviour.
3. **SMB signing not required:** `SmbjRemoteLink.kt:86-87` (`withSigningRequired(false)`). A LAN MITM can tamper with time-shift data on the share or relay NTLM. This is a compatibility trade-off; consider "require signing" when the server offers it, and never fall back to SMB1.
4. **Restore trusts per-playlist columns and backed-up domains:** a crafted `.thebox` restored by the user can set `ignoreTlsErrors` on a playlist, or add a guest SMB storage location pointing at an attacker share on the LAN (time-shift data would go there). It needs user action and is by design, but consider resetting `ignoreTlsErrors` on restore and requiring a test/confirm before a restored network location becomes the time-shift target.
5. **Updater freeze:** the manifest has no expiry. Anyone who can serve an older genuine `update.json` (it needs a TLS MITM of GitHub; the app trusts **user CAs** app-wide, `res/xml/network_security_config.xml`) can hide newer releases from a device that has not yet seen them. Low value. Optional: an `expiresAt` field, or a separate network-security domain-config for `github.com` with system CAs only.
6. **Tink cleartext-keyset fallback** (`AeadProvider.kt:20`): on devices where the Keystore fails, credentials are protected only by the app sandbox. This is already surfaced in diagnostics. Consider a one-time notice, and make sure F3's rules exclude the prefs file.

---

## Checked and found sound

- **Self-updater** (`app/.../update/*`, `core/.../update/*`):
  - Ed25519 is verified over the exact bytes that are then parsed (`ManifestVerifier.kt:68-72`). The size is checked before parsing.
  - `packageName`, `schema` and the replay `sequence` are checked; `versionCode > installed` (no downgrade); withdrawn and skip lists apply.
  - Redirects are followed manually, each hop checked as https plus allowlisted host (the APK URL included).
  - The APK is streamed with SHA-256 and a size ceiling to app-private `filesDir/update`, then checked for package, versionCode, signer in the manifest **and** signer equal to the installed app (API 28+). API 25–27 skips the cert check, but the platform enforces the signer at install.
  - No TOCTOU: only the app can write the file, and the same file is streamed into the `PackageInstaller` session.
  - Install-status receiver is registered `RECEIVER_NOT_EXPORTED`. The mutable `PendingIntent` is package-scoped. FileProvider is unexported and covers `update/` only.
  - Key rotation logic (current + next) is coherent. `release.py` checks that the signing key is trusted by both the new build and the last tag.
- **Release tooling:**
  - The private keys stay in `~/.thebox-release`, are never in the repo, and are used only through `openssl pkeyutl`.
  - The token is never printed.
  - Before publishing, the script verifies the signature and the APK signer, and checks the live manifest against the app's allowlist.
  - Minor: `IGNORED_DIRTY` lets an uncommitted `gradle.properties` change (currently `org.gradle.tooling.parallel=true`, harmless) into a release build.
- **Repo hygiene:**
  - No keys, keystores, `.pem`, `local.properties` or tokens in tracked files or in history (`git log --diff-filter=A`).
  - The TMDB key comes from `local.properties`/env (it is embedded in the shipped APK, which is normal for a TMDB v3 key).
  - The committed Ed25519 values are public keys only.
- **LAN phone setup**, apart from F2:
  - Binds to a single RFC1918 IPv4 address, never 0.0.0.0.
  - Exact `Host` match (DNS rebinding blocked). POST needs `X-TheBox: 1` and a matching `Origin` (CSRF blocked; the custom header forces a preflight the server never grants).
  - 128-bit QR token and session cookie (`HttpOnly`, `SameSite=Lax`, bound to IP). Comparisons are constant-time.
  - Head ≤ 8 KB / 40 headers; no chunked bodies; JSON ≤ 64 KB; uploads ≤ 256 MB with a 2× free-space check.
  - No path traversal: routes are fixed strings, download names go through `safeName`, and offers are single-use.
  - Strict CSP, `nosniff` and `frame-ancestors 'none'`. Header-injection guard in `writeResponse`.
  - Idle (10 min) and absolute (30 min) lifetimes. The spool is wiped on stop. Logs use path templates.
- **Credentials:**
  - Tink AEAD with AD = ref. Credentials are registered with `Redact` on read.
  - `BoxLog.emit` redacts every message, and both sinks redact stack traces.
  - ACRA is local-only, with a whitelisted field set (no logcat, prefs or device id) and a redacted file.
  - The export bundle runs a second audit pass with every playlist secret.
  - `ResolvedStream.toString` is redacted. Xtream JSON parse errors strip the "JSON input" excerpt.
- **Backups:**
  - JSON Lines, not zip (no zip-slip). 64 MB file / 1 MB line limits.
  - Logins only inside an AES-256-GCM `Secrets` record (PBKDF2-HMAC-SHA256, AD = header line). KDF iterations are capped at 500k, so a crafted file cannot hang the TV.
  - Device-bound domains (`update`, `backup`, …) are skipped on import (`RoomSettingsStore.kt:194`), so a crafted backup cannot freeze the updater's `highestSequence`.
  - The PIN is never backed up. `displaySource` is the host only.
- **Parental**, apart from F7:
  - The PIN verifier is PBKDF2 inside the Keystore AEAD, compared in constant time, with checks serialised.
  - The lockout persists with `commit()`.
  - An unknown PIN state is published as locked. A Keystore reset leaves content filtered.
  - The Parental page (including Remove PIN) is behind the PIN. With "Lock settings" on, the whole settings tree is gated.
- **Parsers:**
  - M3U: 64 KB line cap, skipping without buffering.
  - Xtream small/EPG bodies capped at 2/16 MB (`XtreamClient.kt:339-353`).
  - Sports bodies capped at 512 KB / 1 MB. XZ memory capped; compression layers ≤ 3.
- **Android surface:**
  - Only `MainActivity` (launcher) is exported, plus library components behind system permissions (`BIND_JOB_SERVICE`, `DUMP`).
  - No WebView, no notification `PendingIntent`s, no exported receivers. Runtime receivers are `NOT_EXPORTED` or protected system broadcasts.
  - The external-player hand-off uses an explicit package chosen by the user and never logs the URL.
  - Trust-all TLS is a per-playlist opt-in on separate OkHttp clients (`PlaybackHttpClients.kt`), never shared with the updater or the probes.
- **SMB:**
  - Passwords are in `SecureBlobStore` and zeroed after `authenticate`. User and domain are registered for redaction.
  - Folder paths go through `ManualAddressParser` (refuses `..` and control characters) with length caps.

## Not covered

- Media3 data sources in depth (`LiveTsDataSource` redirect handling, cross-host redirects carrying headers).
- Coil/OkHttp logo fetches (whether playlist headers or credentials reach logo hosts).
- SAF tree and USB-volume path handling in the time-shift writer.
- NSD discovery spoofing in "Find computers".
- Every `BoxLog` call site in `:ui`.
- The NFL/NHL design prototypes under `design/`.
- No tests were run, and nothing was exercised on a device.
