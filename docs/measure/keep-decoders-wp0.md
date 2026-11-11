# Keep decoders across zaps, WP-0: measuring decoder cost on the Fire TV Stick

Purpose: decide whether keeping decoders across channel changes (performance H1, `docs/design/m6-keep-decoders-across-zaps.md`) is worth building. The current build records what each zap spends on decoders. Nothing behaves differently.

## What is recorded

For each channel change (a "zap": tune, CH+/CH−, guide OK, previous channel), from the start of the change to its first picture (or first sound for radio):

| Figure | Meaning |
|---|---|
| video decoder name and init ms | Media3's `onVideoDecoderInitialized(…, initializationDurationMs)`: codec create, configure and start. If the zap initialised more than one decoder (a ladder repair, a refused reuse), the times are added and the count is shown, e.g. `(2 inits)`. |
| audio decoder name and init ms | The same for audio. `passthrough` audio (HDMI bitstream) has no decoder, so audio shows `-`. FFmpeg audio shows the FFmpeg decoder name. |
| first frame after prepare | From the zap's last `prepare()` to its first rendered frame. |
| stop to prepare | From stopping the old channel to the new channel's first `prepare()`: the resolve and connection time between them. |
| old video release | From stopping the old channel to Media3 reporting its video decoder released. This is an upper bound: it includes the wait on the playback thread. |

No URL, host, login or token is in any of these.

## Where to read the numbers

**On the TV.** Settings → About and diagnostics → Diagnostics → "Recent channel changes". It shows the last 10 changes, newest first, for example:

`TS · ready 420 ms · first frame 650 ms · ok · decoder init: video 38 ms, audio 4 ms · prepare to first frame 512 ms`

The page is built when you open it. Leave it and open it again to see new zaps. It shows times only; the decoder names are in the export.

**In the log export.** Settings → About and diagnostics → Export logs, then "To your phone" (download it on a phone on the same network) or "To Downloads" (`Download/TheBox` on the TV). In the file:
- the "Zaps (last 50)" section has one line per zap, with the decoder figures after `|`:
  `tune=12 TS resolve=120 lease=10 firstByte=300 ready=500 firstFrame=700 ok | video=OMX.MTK.VIDEO.DECODER.AVC 38 ms, audio=c2.android.aac.decoder 4 ms, first frame 512 ms after prepare, stop to prepare 210 ms, old video release <=22 ms`
- the "Logs" section has the same figures as one line per zap, which is not limited to 50:
  `zap decoders: tune=12 video=OMX.MTK.VIDEO.DECODER.AVC 38 ms, audio=c2.android.aac.decoder 4 ms, first frame 512 ms after prepare, stop to prepare 210 ms, old video release <=22 ms`

**With adb** (optional): `adb logcat -s PlaybackController | grep "zap decoders"`.

The zap history keeps 50 entries. For more than 50 zaps, export after every 50 or use the log lines.

## How to run it

Use a build that contains WP-0, with default settings: Pause and rewind on (AUTO), tunnelling off, AFR at the default. That is the configuration the feature would mostly run in (review B2/S1).

1. Open a channel and wait until it plays. The first tune after a cold start is warm-up: ignore it.
2. Zap with CH+/CH− at a normal pace, about one change every 5 s. Let each channel show its picture before the next press. A zap that is pressed over (superseded) has no first frame and does not count.
3. Do at least **30 zaps per channel kind**, about 120 in total, across:
   - SD H.264 (576i or 480i);
   - HD H.264 (720p and 1080i);
   - HEVC (any HEVC channel the provider has, 1080p or 4K);
   - different audio: AAC, MP2 (common on European DVB re-streams), AC-3/E-AC-3 (once with passthrough to a receiver or soundbar if you have one, once decoded);
   - one radio channel (audio only; video should show `-`).
   Mix the order so that each kind is zapped *to* from the other kinds as well as from itself: SD → HD and H.264 → HEVC are the pairs where a kept decoder could not be reused.
4. Export the logs and send the file.

## Decision rule (lead ruling, 2026-11-11)

Per zap, add video init + audio init. Take the median over the successful zaps (outcome `ok`), and look at it per channel kind as well.

- **Median video + audio init under about 80 ms:** close H1 as "measured, not worth it". Keeping decoders cannot save more than that, and the design review expects a real gain well below it.
- **Otherwise:** the design is revised for the review's blockers (B1, B2) and should-fix items, then built and shipped switched off for a trial.

The "first frame after prepare" and "stop to prepare" figures show how much of the zap the decoder init is. The "old video release" figure shows the cost that keeping a decoder would move onto the next channel's first frame when Media3 refuses the reuse (review S1).

## Limits

- The init time covers codec create, configure and start only. It does not include the `AudioTrack`, which is rebuilt on every zap whatever we do.
- On some Fire OS versions the platform audio decoder is software and very cheap. A long video init with a short audio init is the expected pattern.
- The init times are Media3's own, measured on the playback thread. The other figures are taken on the app's main thread when the events arrive, so they can be a few ms late. None of them is a frame-accurate camera measurement.
