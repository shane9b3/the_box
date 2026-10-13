# :ffmpeg

Wraps the Media3 `decoder_ffmpeg` extension (audio only: AC-3, E-AC-3, MP2, LATM/AAC; LGPL),
built in CI from the Media3 1.11.1 tag against FFmpeg `release/6.0` for armeabi-v7a and arm64-v8a.

- **CI** (`.github/workflows/ffmpeg.yml`, `tools/ffmpeg/`) builds the AAR and drops it into
  `ffmpeg/libs/`. This module picks up any `*.aar` there through `fileTree("libs")`.
- **Local builds** have an empty `libs/`: the module compiles with no native code and
  `FfmpegAvailability.isAvailable` returns `false`, so the player uses platform/passthrough
  audio only.
- Do not commit AARs into `libs/` (only `.gitkeep`).

Caveat: AGP refuses direct local `.aar` dependencies when *bundling this library as an AAR*
(`:ffmpeg:bundleReleaseAar`); building the app is fine. If that becomes a problem, publish
the CI AAR to a local Maven repo (`build/ffmpeg-repo`) and depend on its coordinates instead.
