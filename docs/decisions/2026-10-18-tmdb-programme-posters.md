# 2026-10-18 — Programme posters from TMDB: the app's own key, or the user's (owner ruling)

Owner ruling of 2026-10-18 ("yes, let's do the TMDB poster lookup with a setting for the user to enter their API
key"). Supersedes the "Not planned: TMDB enrichment" line of `docs/00-master-plan.md` §1.3 for this one use (poster
art beside the programme description) and the "owner decision" paragraph of
`docs/decisions/2026-10-18-guide-navigation-recents-and-artwork.md`.

## What ships (first round; the second round below changes the rows and the key model)
- **Settings › TV guide › Programme posters (TMDB key)** (`guide.posters`, advanced, LIVE): a text row that opens
  empty (the key is never shown back, like the backup passphrase). A v3 API key or a v4 read access token both work.
  Saving checks the key with `GET /3/configuration`: 200 stores it ("Key saved. Posters are on."), 401 does not
  ("TMDB rejected the key…"), anything else stores it unverified. Empty removes it. The row's value says On, Off ·
  no key, "TMDB rejected the key…" or "Key missing after a restore or reset…".
- **Where the key lives:** the secure blob store (Tink + Keystore) under `tmdb-api-key`; `GuidePrefs.posterKeyStored`
  only says one is there. The key is registered with `Redact`. A backup carries the flag, not the key: after a
  restore on another device the lookup reports KEY_MISSING and the row asks for it again.
- **Lookup** (`:data` `TmdbProgrammeArtwork`, contract `core/art/ProgrammeArtwork`): the focused panel asks after its
  150 ms debounce, only when the programme has no XMLTV `<icon>` of its own. Cache first (3000-entry LRU on disk,
  hits 30 days, misses 7 days), then `search/tv`, `search/movie` or `search/multi` by the EPG's hint (an episode
  label → series; a Movie/Film genre → film), the title's head ("Doctor Who: The Giggle" → "Doctor Who") when the
  full title matched nothing. An exact title match with a poster wins, else the first with one; people are skipped.
  Posters are `image.tmdb.org/t/p/w342`, through the app's Coil loader and disk cache.
- **Failure policy:** 401 → KEY_REJECTED, no more requests until a new key; 429 → 10 s back-off; 5xx or no
  connection → 30 s back-off; none of these is cached. One lookup at a time.
- **Privacy:** what leaves the device is the cleaned programme title ("New: ", "(2019)", "(R)", episode suffixes
  stripped) plus the app's generic User-Agent. No channel, playlist, device id or TMDB account. Off by default; while
  off nothing is answered, not even from the cache, and removing the key empties the cache (a list of focused
  titles). The row's description says so.
- **Attribution:** "This product uses the TMDB API but is not endorsed or certified by TMDB." in the row's
  description and as a Licences entry (`licenses/TMDB-attribution.txt`).

## Review fixes (same day, one reviewer)
- The panel kept `imageUrl` only on a new cell: a minute tick or a `Tuned` rebuild dropped the poster. Carried like
  the description now.
- A slow network queued one lookup per focused cell behind the lookup's mutex, each with the API client's 10 s + 60 s
  timeouts. The guide now cancels the previous lookup (its HTTP call with it, `enqueue` under
  `suspendCancellableCoroutine`), and every call has an 8 s call timeout; the key check too, with a "Checking the key
  with TMDB…" notice first and a timeout counting as "saved, unverified".
- The key state is loaded eagerly and reloaded whenever `posterKeyStored` changes (an in-process restore), so the
  row is right before any lookup. A 200 that is not JSON backs off like a failure. The cache is keyed by hint. A pasted
  `Bearer ` prefix is stripped.
- Left for the owner: TMDB's terms also ask for their logo next to the attribution; the Licences entry is text only.

## Rejected
- ~~A built-in key: the plan's no-telemetry stance and TMDB's terms both say the user's own key.~~ Reversed the
  same day by the owner (second round below): viewers must not have to register anywhere.
- Fuzzy matching on year or channel: the EPG has no year; the first hit with a poster is right often enough and a
  wrong poster costs nothing but a glance.
- A Room table for the cache: a JSON LRU file needs no migration and is a few hundred KB at most.

## Also in this change
- **Boot blank hardening (found while verifying on the emulator):** Settings opened by the debug route while the app
  was still booting into FULL left the boot blank on screen for good (no guide key ever ended `booting`, and the
  GUIDE → FULL crossing had been abandoned). Opening Settings or Search now ends the boot, and `AppRoot` caps the
  blank at `BOOT_BLANK_MAX_MS` (8 s) whatever happens.
- **Panel description re-arm (machine bug found by the new test):** `rebuildPanel` armed the 150 ms description timer
  before the new panel replaced the old one, so once a cell's description had loaded, the next cell's never did until a
  further move. The timer is now armed on the new panel.
- Group names larger (owner: "what happened to making the group names text larger"): guide Groups panel rows 28/600
  on 56 px rows (headers 22, title 24, counts 24, menu 26), the grid's group chip 24, the player's Groups column 28.

## Second round (same day): the app's own key, posters on by default, the "Ocean's 11" picker

Owner, after trying to get viewers a key: "the api keys are now only for developers" (the key offered was a TheTVDB
v4 key; TheTVDB's model is a developer key plus a paid subscriber PIN per viewer, which is worse for the viewer than
TMDB's free registration, and not what the owner wants anyway). Ruling: the app ships with its own TMDB key so posters
work with no setup; the user's key stays as an override.

- **The app's key:** `thebox.tmdbKey=…` in the root `local.properties` (gitignored) or `THEBOX_TMDB_KEY` (CI) →
  `:data` `BuildConfig.TMDB_KEY` → `BuiltInKeys` (Hilt) → `TmdbProgrammeArtwork`. A build without one behaves as
  before (posters need the user's key; the toggle's description says so). The owner registers the key at
  themoviedb.org › Settings › API ("Developer" there is the use type, not a restriction) and never commits it.
- **Rows (both advanced, the §3.3.13 budget is full at 35):** `guide.posters` is now a **toggle** "Programme posters"
  (`GuidePrefs.posters`, default on: sending the cleaned title to TMDB is the feature, and the privacy line stays in
  the description); `guide.posters_key` is the former text row, "Your TMDB key (optional)", whose value says whose key
  is in use (App's key / Your key / None) or what went wrong (rejected: yours, or the app's → "Update The Box, or
  enter your own key"; missing after a restore).
- **Key state:** the user's key, when stored, takes precedence; removing it (empty) goes back to the app's key
  ("Your key is removed. Posters use the app's key."). A user key missing from the secure store after a restore
  falls back to the app's key and clears `posterKeyStored` so the row stops claiming it (KEY_MISSING only in a build
  without a key). A 401 on the app's key is `APP_KEY_REJECTED`, on the user's `KEY_REJECTED`. Switching posters off
  stops lookups and empties the cache; removing the user's key keeps it when the app's key carries on.
- **Picker fix (owner: "Ocean's 11 brings up an Asian poster"):** the cached poster was the Takarazuka Revue stage
  recording "Ocean's 11" (2019), the only TMDB entry with that exact title (both real films are "Ocean's Eleven"),
  and the first-round rule let any exact match beat the first result. Now an exact match wins only with at least a
  tenth of the first result's popularity (`EXACT_MIN_POPULARITY_SHARE`); otherwise the first result with a poster,
  which TMDB orders by popularity. Every search also sends `language=<device language tag>` so TMDB returns the
  localised poster when it has one. The cache file is `tmdb-cache-2.json`; the first-round file is deleted on start so
  the wrong answers go.
- **Privacy wording:** what leaves the device is now the cleaned title, the device language tag and the generic
  User-Agent. Default on is an owner decision (the toggle is one key press away, under Advanced).
- Tests: `TmdbProgrammeArtworkTest` (+8: app key, blank app key, toggle, override and back, 401 on the app key,
  missing user key fallback, legacy cache dropped, fringe exact match), `WpaPagesTest` (toggle + key rows).

## Third round (owner ruling 2026-10-19): built-in key only; the user-key row removed

Owner ruling: programme posters use the app's built-in TMDB key only. The "Your TMDB key (optional)" row and every
piece of the user-key path are gone; the built-in key path and the on/off toggle stay. The first two rounds above are
kept as history: anything they say about the user's key, `guide.posters_key`, `posterKeyStored`, `tmdb-api-key`,
`KEY_REJECTED` or `KEY_MISSING` no longer applies.

- **Contract** (`core/art/ProgrammeArtwork`): `status` and `poster()` only. `setKey`, `clearKey`, `hasBuiltInKey` and
  `ArtworkKeyResult` are removed; `ArtworkStatus` is `OFF`, `ON`, `APP_KEY_REJECTED`.
- **Lookup** (`TmdbProgrammeArtwork`): no secure-blob key (`SecureBlobStore.REF_TMDB_KEY` removed, nothing written to
  or read from the blob store), no `Redact` registration beyond the built-in key. The key state is: posters off → OFF;
  the build carries a key → ON; else OFF (a build without a key makes no request at all). A 401 is always
  `APP_KEY_REJECTED`. Cache, picker, `language`, back-offs and the call timeout are unchanged. A `tmdb-api-key` blob
  left on a device by the second-round build is no longer read; it is not deleted (it is a few bytes in the encrypted
  store and deleting it would mean keeping the ref name alive).
- **Settings:** `guide.posters` is the only poster row (advanced, LIVE). Its description is the privacy line when the
  lookup is ON or posters are off, "Needs a TMDB key: this build has none." when posters are on and the lookup is OFF
  (a build without a key), and "TMDB rejected the app's key. Update The Box." after a 401. `ProgrammeArtwork` no longer
  says whether the build has a key, so the page reads it from the lookup's status. The texts for the key row
  (`GUIDE_POSTERS_KEY*`, `POSTERS_KEY_*`, rejected/missing/saved/removed/checking/store-failed notices) and the
  `TextHint.API_KEY` editor hint with its `ERR_API_KEY` validation are removed.
- **Settings document and backups:** `GuidePrefs.posterKeyStored` is removed. Older settings documents and backups that
  carry it still read: the settings and backup JSON are `ignoreUnknownKeys`, so the field is ignored.
- Tests: `TmdbProgrammeArtworkTest` keeps the built-in-key, toggle, picker, cache and back-off tests, drops the user-key
  ones, and adds "a build without a key is OFF and makes no request"; `WpaPagesTest` checks the toggle alone and its
  description per status.

Lead amendment (2026-10-19, after the removal): the toggle's "this build has none" description comes from
`WpaPageEnv.hasTmdbKey` (set in `:app` from `BuiltInKeys`), a build-time fact, not from the lookup's status, so it
cannot flicker while the lookup starts and it reads the same whether posters are on or off. TMDB rejecting the app's
key still wins (`ArtworkStatus.APP_KEY_REJECTED`).
