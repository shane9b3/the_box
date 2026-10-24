# Save location for pause and rewind: this device, USB drive, network folder (SMB)

Date: 2026-10-24.
- Proposer: Opus.
- Reviews: architecture/playback (`scratchpad/storage/review-arch.md`) and product/UX (`scratchpad/storage/review-ux.md`).
- Lead approved. Owner rulings fixed during the review.
- Design: `docs/design/m6-storage-locations.md`.

## Owner request (verbatim)

"I would like to add a feature to add a save location for the auto record for rewind and pause, make sure we can use smb as well and be able to find other computers on teh network"

## Owner rulings

1. Folder unreachable when a channel is tuned: rewind saves on this device, with one toast.
2. The scan of the home network (TCP 445 on the local /24) runs only while "Find computers" is open.
3. USB is in scope when the device lists the drive (`getExternalFilesDirs`).
4. Sign-in from the phone is in scope now.

## Decision

- **Scope.** The time-shift ring can live on this device (today's), a USB drive (app-specific directory, no SAF), or an SMB network folder (smbj 0.15.0). This pulls the plan's v2 "USB / SMB storage" forward for **pause and rewind only**. v2 recordings reuse the same `StorageLocation` model, pages and SMB backend.
- **Zap path.** The tune never waits on the network. `prepare` does no I/O.
  - SMB is chosen when the folder is ready, or was last reachable within 7 days with no failure in this process.
  - If the folder has not taken its first write within 6 s, that cycle switches to this device before anything was written to the folder.
- **SMB failures** reuse the existing time-shift stop table. The ring goes RAM-only for the cycle, live TV continues, and the folder is demoted outside a 20 s start-of-cycle grace.
  - Each remote ring has its own flusher thread.
  - The RAM pool (160 blocks, or 96 below a 192 MiB memory class) is the only stall budget.
  - A reconnect happens only on real transport errors.
  - A cycle that reconnected uses a fresh file next time.
- **Discovery:** NsdManager `_smb._tcp`, the 445 sweep (Wi-Fi/Ethernet only, never the VPN), and NetBIOS / reverse-DNS names, plus manual entry or paste. WS-Discovery is not used.
- **Share listing:** rapid7 dcerpc 0.12.13. Guava 33.3.1-android is already on the release classpath via Media3 1.11.1. New jars: dcerpc, commons-lang3, commons-io, smbj, bcprov, mbassador, asn-one. Spike S-2 records the APK delta. A typed share name always works.
- **Credentials.** Passwords live only in `SecureBlobStore` (Tink + Keystore) and are never backed up. They are registered with the export audit. Phone sign-in is a one-shot POST: the password is never mirrored or logged, and its buffers are zeroed.
- **Settings.** "Pause and rewind" becomes a link to its own sub-page (Rewind length, Save to). The simple-row total stays at 35.

## Why

- **Owners want to spare the box's small flash** and use the NAS they already have. A network folder also lifts the 8 GiB ring cap, for 4K channels.
- **Neither reviewer would accept any cost on the hard zap gate.** So every network step is lazy or happens in the background.
- **The early switch keeps owner ruling 1 honest** even on the first tune after launch.
- **SAF pickers are unreliable on TV images.** The app-specific directory needs no permission and reuses today's storage code unchanged.
- **WS-Discovery added a codec, a socket and an unverified Windows behaviour** for no PC the 445 sweep plus NetBIOS names would miss.

## Open

- **Owner:** offer a 4-hour rewind on network folders? Recommended: not now.
- **Spikes S-1 (USB on Fire TV / Onn) and S-2 (release build with R8 on API 25 / Fire OS 6)** run before commit 0.
