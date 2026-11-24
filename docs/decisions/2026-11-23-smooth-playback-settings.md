# Settings against micro stutter on older boxes (2026-11-23)

Owner request: live hockey shows micro stutter on an older device with tunneled playback; add settings for the options
found. No such device was available, so every row below defaults to the behaviour before it existed and none of them was
measured on hardware.

## What was added

Settings › Playback (all three shown with the other playback rows):

| Row | Values | Default | Takes effect | What it does |
|---|---|---|---|---|
| Keep up with live | On / Off | On | leaving Settings | Off gives an HLS live source the speed range 1.0 to 1.0 instead of 0.97 to 1.03, so Media3's live speed control never changes the speed. TS channels never had a speed range. |
| Decoder hand-off | Auto / In the background / Direct | Auto | next app start | `DefaultRenderersFactory.forceEnable…` / `forceDisableMediaCodecAsynchronousQueueing()`. Auto is Media3's own choice (asynchronous from Android 12). |
| Audio buffer | Auto / Large | Auto | next app start | Large sets the PCM buffer range to 500 ms to 1.5 s (Media3's default is 250 to 750 ms) through the output provider's buffer-size provider. Passthrough is untouched. |

Channel options (per channel, read at the channel's next tune):

| Row | Values | Stored as |
|---|---|---|
| Tunneled playback | Default / Off for this channel | the existing `tunnelingOff` bit of `channel_prefs.playback_flags` (also set by the error ladder; Default clears it) |
| Match frame rate | Default / Always for this channel | a new bit, `AFR_ALWAYS_BIT` (0b10000), in the same column: no schema change |

A channel with "Always" behaves as if the global "Match frame rate" were Always while it is tuned (live only); tuning away
gives the display mode back, as turning the global setting off does.

## Why these

- 59.94 fps hockey on a 60.00 Hz output repeats a frame about every 17 s; only a mode switch fixes it, and a global
  "Always" blanks the screen on every zap. Hence the per-channel row.
- A live speed other than 1.0 breaks the frame cadence, and tunneled or passthrough audio cannot follow it.
- Tunneling on old chipsets handles live TS timestamp jumps badly; the app only turned it off for a channel when the
  decoder produced nothing.
- The two engine rows are read when the player is built (the renderers factory and the audio sink), so they wait for a
  restart, like the FFmpeg row, but without a "Restart now" row.

## Not done

- A score-bug option (opaque, or hidden while playing): only worth adding if hiding the bug is shown to stop the stutter
  on the device.
- Nothing for interlaced sources: deinterlacing is the device's.
