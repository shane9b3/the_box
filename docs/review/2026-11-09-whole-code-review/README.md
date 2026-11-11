# Whole-code review, 2026-11-09 (owner request: "code review, security and code optimizations")

Four reviewers read the whole app (about 115,000 lines): security, performance, correctness of core + data, and
correctness of player + ui + app. Their reports are in this folder. A fix pass followed, then an independent review of
the combined fix diff, whose low-severity findings were fixed in the same commit.

## Fixed in the fix pass
- Security: size limits on XMLTV fields, Xtream items and unpacked downloads (F1); per-address pairing and socket
  limits for phone setup, 30 wrong codes in total, 6 sockets with 4 per address (F2); backup and device-transfer
  exclusion rules (F3); debug entry points behind `DUMP`, no public debug APK artifact (F4); workflow actions pinned to
  commit SHAs with least-privilege permissions (F5); fetch URLs logged as host only, guide URLs masked in the log
  export (F6); SVG images decoded only when small and free of entity declarations (`GuardedSvg`).
- Correctness: U1–U5, P1, P2 (the Sources picker is hidden for catch-up), A1–A6, and core/data findings 2–7.
- Core/data finding 1 (restore drops guide matches on a new device), fixed after the pass: the restore creates
  placeholder guide rows the first refresh fills in (`docs/design/m5-restore-placeholder-guides.md`, reviewed in
  `docs/review/2026-11-09-restore-placeholder-guides-review.md`).
- Performance: H2, H4, M1 (guards), M3, M4, M7(a), M8, M10, L5 (ByteWatchdog), L6–L8.

## Not fixed, with the reason
- Core/data finding 1: now fixed (see the fixed list above).
- Performance H1 (decoders released on every zap), M5, L1, L2, L4: need measuring on a real Fire TV Stick.
- Performance H3 (baseline profile): generating it runs instrumented tests, which wipe the emulator's login.
- Performance M2 and the duplicate-identity ordering weakness (M1 code review, Later 7): need a design.
- Security F5 Gradle dependency verification; F7 (clock moved forward ends the parental lockout; needs Android
  Settings access, which also allows Clear data).
- P2 proper fix (rebuild the archive URL on the chosen base): needs a core contract change.
- Trade-offs: an SVG logo over 512 KiB or one that declares XML entities does not render; a single run of more than 1 MiB of
  guide text without a tag fails that guide source with a reason.

## Fixed after the pass (2026-11-10, deferred items)
- `e35dc82`: L3 (a rewind ring up to max(2 blocks, 1 %) smaller than the new sizing is reused; a larger one is still
  rebuilt), a REPREPARE setting changed while paused in the local rewind buffer now re-prepares the reader at the pin
  and stays paused, M7(b) (one channel row read per zap).
- `80740f2`: M9 (logo loads not requested for 60 draws are cancelled; one version bump per arrival), L9
  (`ChannelUpdate.renamed` replaces the per-update SELECT).
- `128a88a`: M6 (a byte-identical guide download counts as not modified; a NOT_MODIFIED `EPG_BUILD` row is logged
  for 304 and identical runs; a generation 20 h old or more is rebuilt from the cached files), H5
  (`INDEX_BEFORE_LOAD = true` after the JVM measurement in `EpgIndexMemoryTest`; I-5 on a Stick still confirms).
- Review of these fixes: `docs/review/2026-11-10-deferred-items-fix-review.md` (open: S1–S4 and later items).
- Fix pass of that review (2026-11-10): S1 (the skip also requires the generation's build-input fingerprint to match),
  S2 (app start counts a recent SUCCESS/NOT_MODIFIED guide run as fresh), the 20 h age rule replaced by a future-window
  rule (N1), S3 (replayed rows `touch` their logo; a cancel no longer bumps the version), S4 tests, N4 (the compare
  stops on cancel). Dispositions in that review's "Fix pass" section.
