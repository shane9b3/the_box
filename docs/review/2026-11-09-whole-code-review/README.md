# Whole-code review, 2026-11-09 (owner request: "code review, security and code optimizations")

Four reviewers read the whole app (about 115,000 lines): security, performance, correctness of core + data, and
correctness of player + ui + app. Their reports are in this folder. A fix pass followed, then an independent review of
the combined fix diff, whose low-severity findings were fixed in the same commit.

## Fixed in the fix pass
- Security: size limits on XMLTV fields, Xtream items and unpacked downloads (F1); per-address pairing and socket
  limits for phone setup, 30 wrong codes in total, 6 sockets with 4 per address (F2); backup and device-transfer
  exclusion rules (F3); debug entry points behind `DUMP`, no public debug APK artifact (F4); workflow actions pinned to
  commit SHAs with least-privilege permissions (F5); fetch URLs logged as host only, guide URLs masked in the log
  export (F6); the SVG decoder limited to sports logos.
- Correctness: U1–U5, P1, P2 (the Sources picker is hidden for catch-up), A1–A6, and core/data findings 2–7.
- Core/data finding 1 (restore drops guide matches on a new device), fixed after the pass: the restore creates
  placeholder guide rows the first refresh fills in (`docs/design/m5-restore-placeholder-guides.md`, reviewed in
  `docs/review/2026-11-09-restore-placeholder-guides-review.md`).
- Performance: H2, H4, M1 (guards), M3, M4, M7(a), M8, M10, L5 (ByteWatchdog), L6–L8.

## Not fixed, with the reason
- Core/data finding 1: now fixed (see the fixed list above).
- Performance H1 (decoders released on every zap), H5, M5, M6, M9, L1–L4: need measuring on a real Fire TV Stick.
- Performance H3 (baseline profile): generating it runs instrumented tests, which wipe the emulator's login.
- Performance M2 and the duplicate-identity ordering weakness (M1 code review, Later 7): need a design.
- Security F5 Gradle dependency verification; F7 (clock moved forward ends the parental lockout; needs Android
  Settings access, which also allows Clear data).
- P2 proper fix (rebuild the archive URL on the chosen base), M7(b), L9: need a core contract change.
- A setting changed while paused in the local rewind buffer applies only at the next re-prepare.
- Trade-offs (the first awaits the owner's word): a provider channel logo supplied as SVG no longer renders; a single run of more than 1 MiB of
  guide text without a tag fails that guide source with a reason.
