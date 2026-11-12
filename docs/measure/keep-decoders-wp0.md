# Keep decoders across zaps, WP-0: measuring decoder cost on the Fire TV Stick

Purpose: decide whether keeping decoders across channel changes (performance H1, `docs/design/m6-keep-decoders-across-zaps.md`) is worth building. The current build records what each zap spends on decoders. Nothing behaves differently.

## What is recorded

For each channel change (a "zap": tune, CH+/CH−, guide OK, previous channel), from the start of the change to its first picture (or first sound for radio):

| Figure | Meaning |
|---|---|
| video kind | The video MIME without `video/` (`avc` = H.264, `hevc` = H.265, `mpeg2`), the height (576, 720, 1080, 2160) and the frame rate when the stream gives one. This is how SD, HD and HEVC zaps are told apart. |
| audio kind | The audio MIME without `audio/` (`mp4a-latm` or `mp4a` = AAC, `mpeg-L2` = MP2, `ac3`, `eac3`) and the channel count. |
| video decoder name and init ms | Media3's `onVideoDecoderInitialized(…, initializationDurationMs)`: codec create, configure and start. If the zap initialised more than one decoder (a ladder repair, a refused reuse), the times are added and the count is shown, e.g. `(2 inits)`. |
| audio decoder name and init ms | The same for audio. Passthrough audio (HDMI bitstream) has no decoder, so audio shows `-`. FFmpeg audio shows the FFmpeg decoder name. |
| init total | Video init + audio init. It is `-` when either is missing (passthrough, radio, or not seen): a missing figure is never counted as 0 ms. |
| first frame after prepare | From the zap's last `prepare()` to its first rendered frame. |
| stop to prepare | From stopping the old channel to the new channel's first `prepare()`: the resolve and connection time between them. |
| old video release | From stopping the old channel to Media3 reporting its video decoder released. This is an upper bound: it includes the wait on the playback thread. `-` after a radio channel. |

No URL, host, channel name, login or token is in any of these.

## Where to read the numbers

**On the TV.** Settings → About and diagnostics → Diagnostics. Under "Recent channel changes" there is one row per change, the last 10, newest first. Each row has two lines, for example:

```
TS · ready 420 ms · first frame 650 ms · ok
Init: video avc 720 38 ms · audio mp4a-latm 4 ms · frame 512 ms after prepare
```

The first line's "first frame" is from the start of the change; the second line's "frame … after prepare" is from the `prepare()`. The TV shows the kind and the times only; decoder names, frame rate, channel count, the init total, stop to prepare and the release are in the export. The page is built when you open it: leave it and open it again to see new changes.

**In the log export.** Settings → About and diagnostics → Export logs, then "To your phone" (download it on a phone on the same network) or "To Downloads" (`Download/TheBox` on the TV). In the file:
- the "Zaps (last 50)" section has one line per zap, with the decoder figures after `|`:
  `tune=12 TS resolve=120 lease=10 firstByte=300 ready=500 firstFrame=700 ok | video=avc 576 25fps OMX.MTK.VIDEO.DECODER.AVC 38 ms, audio=mp4a-latm 2ch c2.android.aac.decoder 4 ms, init total 42 ms, first frame 612 ms after prepare, stop to prepare 210 ms, old video release <=22 ms`
- the "Logs" section has the same figures as one `zap decoders:` line per zap:
  `zap decoders: tune=12 video=avc 576 25fps OMX.MTK.VIDEO.DECODER.AVC 38 ms, audio=mp4a-latm 2ch c2.android.aac.decoder 4 ms, init total 42 ms, first frame 612 ms after prepare, stop to prepare 210 ms, old video release <=22 ms`

**With adb** (optional): `adb logcat -s PlaybackController | grep "zap decoders"`.

**Export after every ~30 zaps.** The zap history keeps 50 entries, and the log ring is shared with everything else the app logs, so a long session can push early zaps out of both. Export after each batch of about 30 changes and keep every file.

## How to run it

Use a build that contains WP-0, with default settings: Pause and rewind on (AUTO), tunnelling off, AFR at the default. That is the configuration the feature would mostly run in (review B2/S1).

1. Open a channel and wait until it plays. The first tune after a cold start is warm-up: ignore it.
2. Zap with CH+/CH− at a normal pace, about one change every 5 s. Let each channel show its picture before the next press. A zap that is pressed over (superseded) has no first frame and does not count.
3. Do at least **30 zaps per channel kind**, about 120 in total, across:
   - SD H.264 (`avc 576` or `avc 480`);
   - HD H.264 (`avc 720`, `avc 1080`);
   - HEVC (`hevc …`, any HEVC channel the provider has);
   - different audio: AAC, MP2 (common on European DVB re-streams), AC-3/E-AC-3 (once with passthrough to a receiver or soundbar if you have one, once decoded);
   - one radio channel (audio only; video shows `-`).
   Mix the order so that each kind is zapped *to* from the other kinds as well as from itself: SD → HD and H.264 → HEVC are the pairs where a kept decoder could not be reused. The kind of the previous zap is the line before.
4. Export the logs after every ~30 zaps (above) and send all the files.

## Decision rule (lead ruling, 2026-11-11)

Per zap, read `init total` (video init + audio init). Take the median over the successful zaps (outcome `ok`) that have a total, and also per channel kind.

- Leave out zaps that show `(2 inits)` or more: a ladder repair inside the zap added a second init, which keeping decoders would not save. Count how many there were.
- Zaps with `init total -` (passthrough audio, radio) are left out of the median. For passthrough, read the video init alone.
- **Median video + audio init under about 80 ms:** close H1 as "measured, not worth it". Keeping decoders cannot save more than that, and the design review expects a real gain well below it.
- **Otherwise:** the design is revised for the review's blockers (B1, B2) and should-fix items, then built and shipped switched off for a trial.
- **A median between about 70 and 100 ms is not decisive** (see Limits: the first-output delay is not measured). Bring it to the lead with the per-kind medians.

The "first frame after prepare" and "stop to prepare" figures show how much of the zap the decoder init is. The "old video release" figure shows the cost that keeping a decoder would move onto the next channel's first frame when Media3 refuses the reuse (review S1).

## Limits

- The init time covers codec create, configure and start only. It does not include the `AudioTrack`, which is rebuilt on every zap whatever we do.
- **Not measured: the codec's first-output delay.** A freshly configured hardware decoder may need more input before its first frame than a flushed, kept one. That difference is part of "first frame after prepare", mixed with network and extractor time, and cannot be separated here. So a result near the 80 ms bar does not decide the question on its own.
- Ladder repairs inside a zap (a stall, a decoder fallback) add a second init to the sum; the count shows it.
- On some Fire OS versions the platform audio decoder is software and very cheap. A long video init with a short audio init is the expected pattern.
- The init times are Media3's own, measured on the playback thread. The other figures are taken on the app's main thread when the events arrive, so they can be a few ms late. None of them is a frame-accurate camera measurement.
