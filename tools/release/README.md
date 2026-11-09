# Releasing The Box

Releases are sideload builds on the public GitHub repo **`shane9b3/the_box`**, built and signed on the release Mac by
`tools/release/release.py`, not in CI. Installed copies find them through the in-app updater
(`docs/design/m5-setup-settings.md` §3.12): every day they fetch

```
https://github.com/shane9b3/the_box/releases/latest/download/update.json      (+ update.json.sig)
```

check the Ed25519 signature against the keys compiled into the app (`ReleaseUpdateConfig` in
`app/src/main/kotlin/app/thebox/update/UpdateEndpoints.kt`), then download the APK from
`releases/download/<tag>/TheBox-<abi>.apk` and check its SHA-256, package, versionCode and signing certificate before
the system installer opens. Redirects are followed only to `github.com`, `objects.githubusercontent.com` and
`release-assets.githubusercontent.com` (measured 2026-11-08: `github.com` → `github.com` → `release-assets…`). After
publishing, the script fetches the manifest and the universal APK the same way to prove it.

## Where the keys live

Everything is in `~/.thebox-release/` on the release Mac. None of it is in git.

| File | What it is |
|---|---|
| `release.jks` (alias `thebox`) | APK signing keystore. Certificate SHA-256 `22:40:93:19:E0:1C:49:12:6C:E4:C4:62:07:F1:EB:50:5E:2D:2B:FC:46:9E:01:A7:C0:EA:04:17:E3:3C:46:34` |
| `keystore.properties` | a copy of the repo-root `keystore.properties` (gitignored), which `app/build.gradle.kts` reads |
| `update-current.pem` | Ed25519 key that signs `update.json` today (public `dc0243f3…5ad7ba`) |
| `update-next.pem` | the next manifest key, already trusted by the app, kept for rotation (public `4b056d8a…0002d6`) |

**Back up `~/.thebox-release/` (and the keystore passwords) offline, in at least two places, e.g. an encrypted USB
stick and a password manager.** If the keystore is lost, no installed copy can ever update again: Android refuses an
APK signed by another key, so every user would have to uninstall (losing their data unless they made a backup) and
sideload again. If both manifest keys are lost, the updater stops finding releases for the same reason.

## One-time setup

1. JDK 21 and the Android SDK as in `docs/m0-toolchain.md` (the script sets `ANDROID_HOME=~/Library/Android/sdk` and
   the Homebrew JDK 21 `JAVA_HOME` when they are not set). `openssl` 3.x with Ed25519 (`openssl version`).
2. `keystore.properties` at the repo root pointing at `~/.thebox-release/release.jks` (already done).
3. A GitHub login with write access to the repo. Any one of:
   - `brew install gh && gh auth login` (the script calls `gh auth token`), or
   - `export GITHUB_TOKEN=…`: a classic token with `repo` + `workflow` scope, or a fine-grained token for
     `shane9b3/the_box` with Contents: read and write, or
   - a github.com credential in git's credential helper (the one `git push` already uses; the script asks
     `git credential fill`).
   The token is used only in the `Authorization` header and is never printed.

## Cutting a release

```sh
# Rehearsal: builds, signs and verifies into build/release/; changes no file in the tree, no git or network writes.
python3 -I tools/release/release.py --dry-run

# The release. The first line of the notes is the summary on the TV's update card.
python3 -I tools/release/release.py --notes "Fixes catch-up on Flussonic servers
- Faster zapping"
```

What it does, stopping at the first problem:

1. Preflight: on `main`, tree clean (the owner's `gradle.properties`, `gradle/gradle-daemon-jvm.properties` and
   `design/nfl/support.js`, `design/nfl/vendor/` are ignored), `main` equals `origin/main`, the signing key is one the
   new build **and** the last released build trust, the version is above every `v*` tag and the live manifest, the
   live manifest's signature is good (its `withdrawn` list is carried over).
2. Version: `version.properties` versionCode + 1 and PATCH + 1, unless `--version-code` / `--version-name`.
3. `./gradlew :app:assembleRelease`.
4. Each APK (`armeabi-v7a`, `arm64-v8a`, `universal`): `aapt2 dump badging` (package `app.thebox`, versionCode,
   versionName, minSdk) and `apksigner verify --print-certs` (exactly one signer, the release certificate).
5. `build/release/update.json` (`UpdateManifest`, schema 1) and `update.json.sig` (OpenSSL `pkeyutl -sign -rawin`, raw
   64 bytes), the signature re-verified against the app's compiled-in keys.
6. Commits `version.properties` as Shawn Johnson (noreply address), tags `vX.Y.Z`, pushes both.
7. Creates a **draft** GitHub release, uploads `TheBox-armeabi-v7a.apk`, `TheBox-arm64-v8a.apk`,
   `TheBox-universal.apk`, `update.json`, `update.json.sig`, then publishes it (so `latest` flips only when every asset
   is there), then re-fetches the public URLs.

Options: `--withdraw CODE` (repeatable) lists a bad versionCode in `withdrawn`; `--rollout N` sets `rolloutPercent`;
`--signing-key PATH` picks another manifest key (rotation).

### Versioning

`version.properties` (repo root) holds `versionCode` and `versionName`; `app/build.gradle.kts` reads it, and
`-Pthebox.versionCode=` / `-Pthebox.versionName=` override it for a single build. versionCode strictly increases with
every release and is never reused; versionName is `MAJOR.MINOR.PATCH` and the tag is `v<versionName>`. The manifest
`sequence` **is** the versionCode: it already only goes up, it needs no trusted clock, and the app refuses a manifest
with a lower sequence than it has seen (replay protection) while accepting an equal one (the daily re-check). A fix to
a published release is therefore a new release, never a re-signed manifest for the same versionCode. For the first
public release pick the name: `--version-name 0.1.0`.

### Rolling back

Android never installs a lower versionCode over a higher one. Publish N+1 (the fix, or the old code with a new
versionCode) with `--withdraw N`; TVs still below N skip N. Users who want the old build by hand: Settings → Backup and
restore → back up, uninstall, install the older APK, restore.

## Rotating the manifest key

The app trusts two keys: `update-current` (signs today) and `update-next` (held back). Installed TVs run the
**previous** release, so a manifest must be signed by a key that build trusts; the script refuses otherwise.

1. Make a new spare key: `openssl genpkey -algorithm ed25519 -out ~/.thebox-release/update-next2.pem` and print its
   public hex: `openssl pkey -in ~/.thebox-release/update-next2.pem -pubout -outform DER | tail -c 32 | xxd -p -c 32`.
2. In `ReleaseUpdateConfig.UPDATE_PUBLIC_KEYS_HEX` list `[next, next2]` (drop the old current key), commit, and cut a
   release signed with `--signing-key ~/.thebox-release/update-next.pem` (trusted by the installed build, which lists
   current + next).
3. Rename: the old `update-current.pem` goes to the offline archive, `update-next.pem` becomes `update-current.pem`,
   `update-next2.pem` becomes `update-next.pem`. Back up the folder again.

If the current key leaks, do this at once: TVs that installed the rotated release no longer trust the leaked key.
TVs that skip that release keep trusting it, so keep the rotation release in place (never withdraw it).

## Moving a TV from the debug build to the release build

They are different apps (`app.thebox.debug` and `app.thebox`) with separate data, so both can stay installed while you
move. Settings travel through a backup:

1. In the debug app: Settings → Backup and restore → **Back up now** → **To your phone** (scan the code, download the
   `.thebox` file), and say **yes to "Include logins?"** with a passphrase, or the playlists arrive without their logins.
   Avoid "To Downloads": on Android 11+ (most Fire OS 8 and Google TV boxes) one app cannot list another app's files
   in `Download/`, so the release app will not see it.
2. Sideload the release build: `https://github.com/shane9b3/the_box/releases/latest/download/TheBox-universal.apk`
   (Downloader, or `adb install`).
3. In the release app: Settings → Backup and restore → **Restore** → **From your phone** (upload the file, enter the
   passphrase), choose **Replace** on a fresh install. Playlists refresh on their own.
4. Check playback, then uninstall `app.thebox.debug`.

The backup (`docs/design/m5-setup-settings.md` §3.10.2) covers: the guide, player, playback, data, network, parental
policy, remote (button mapping), storage-location and sports settings; playlists with every user-set option; their
logins (only with a passphrase); EPG sources and per-source overrides; group and channel settings (hidden, locked,
names, numbers, logos, sort, guide shift, aspect, audio/subtitle language, decoder, buffer, last watched); favourite
lists; manual EPG mappings; hide rules; reminders still to come.

It does **not** cover: the parental PIN (set it again), the stored backup passphrase, network-share (SMB) passwords
(re-enter them under the storage location), the followed sports game, update and setup state, the EPG data and image
caches (rebuilt by the first refresh), or anything in the time-shift buffer.

## Tests

`python3 -I tools/release/test_release.py` (manifest builder: field names, SHA-256, sequence, signature round trip,
OpenSSL ↔ Python agreement, the fixture). `ReleaseManifestFixtureTest` in `:data` feeds the same fixture, signed with
a throwaway test key, to the app's real `ManifestVerifier` with Tink. After changing the manifest format, run
`python3 -I tools/release/test_release.py --regen` and both tests again.
