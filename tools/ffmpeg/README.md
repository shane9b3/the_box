# FFmpeg audio decoder AAR (`decoder_ffmpeg`)

This directory builds the Media3 **`decoder_ffmpeg`** extension, the `FfmpegAudioRenderer`, as an
AAR that the `:ffmpeg` Gradle module ships. It is **audio-only**: Media3's FFmpeg module has no
video renderer. Google doesn't publish this AAR to Maven because of FFmpeg licensing, so we build
it ourselves, reproducibly, from pinned sources.

Why we need it: MP2 (DVB-sourced IPTV) and AAC-LATM often have no MediaCodec decoder on TV SoCs.
AC-3, E-AC-3 and DTS need a software fallback when passthrough isn't available. See
`docs/00-master-plan.md` §3 (Decoders row) and `docs/research/android-tv-architecture.md` §2.5.

## Pins

| What | Value | Source |
|---|---|---|
| Media3 | tag **`1.11.1`** (commit `8c6678b657ede1e7883fc164ef73ed483c7796c3`) | `git ls-remote --tags https://github.com/androidx/media` (checked 2026-10-13) |
| FFmpeg | branch **`release/6.0`** (GitHub mirror `FFmpeg/FFmpeg`, same commits as `git.ffmpeg.org`) | `libraries/decoder_ffmpeg/README.md` at tag 1.11.1: "We currently recommend version 6.0". The `release` branch README is identical. |
| NDK | **r26b = `26.1.10909125`** | Same README: "tested on NDK r26b (r23c if ANDROID_ABI is less than 21)" |
| FFmpeg API level (`ANDROID_ABI` arg) | **23** | Media3 1.11.1 `minSdkVersion`. It must be ≤ the app's minSdk (25). |
| CMake | ≥ 3.21 (SDK `cmake;3.22.1`) | `decoder_ffmpeg/src/main/jni/CMakeLists.txt` |
| JDK | 17+ | Media3 1.11.1 uses AGP 9.0.1 and Gradle 9.1.0 |
| ABIs | `armeabi-v7a arm64-v8a` | Master plan, Platform row |
| Decoders | `ac3 eac3 dca mp3 mp2 aac aac_latm truehd vorbis opus flac alac pcm_mulaw pcm_alaw` | Master plan, Decoders row / research §2.5 |

The local NDKs 25.2.9519653 and 23.1.7779620 are **not** the tested version. r23c is only for
API < 21, and r25 is untested by Media3. The script prefers `ndk;26.1.10909125` and prints a
warning if it falls back to another NDK. Install it with:

```sh
~/Library/Android/sdk/cmdline-tools/latest/bin/sdkmanager "ndk;26.1.10909125"
```

## What the script does

`tools/ffmpeg/build-ffmpeg-aar.sh` follows the `decoder_ffmpeg` README step by step:

1. Shallow-clones `androidx/media` at `MEDIA3_TAG` into `work/media`. If the clone is already at
   that tag, it is reused.
2. Shallow-clones FFmpeg at `FFMPEG_BRANCH` into `work/ffmpeg`. It then symlinks that clone to
   `libraries/decoder_ffmpeg/src/main/jni/ffmpeg`, the README's `${FFMPEG_MODULE_PATH}/jni/ffmpeg`
   location, which `CMakeLists.txt` and `build.gradle.kts` look for. FFmpeg is kept outside the
   Media3 tree so CI can cache it on its own.
3. Runs the module's own `jni/build_ffmpeg.sh <module> <ndk> <host> <api> <decoders…>`.
   - The upstream script always builds all four ABIs, and the README says to edit it for other
     ABI sets. Instead of editing it, the script generates `build_ffmpeg.<abi>.sh` for each ABI.
   - Each generated script keeps the upstream header unchanged: `COMMON_OPTIONS`,
     `--disable-everything`, `--enable-decoder=…` and the toolchain checks. It contains only that
     ABI's `./configure … make clean` block.
   - The script never adds configure flags of its own.
   - After each ABI, the script checks FFmpeg's generated `config.h`. It fails unless
     `CONFIG_GPL 0`, `CONFIG_NONFREE 0` and `FFMPEG_LICENSE "LGPL version 2.1 or later"` are all
     set. It also refuses to run if the upstream script ever contains `--enable-gpl` or
     `--enable-nonfree`.
4. Runs `./gradlew :lib-decoder-ffmpeg:assembleRelease` in the Media3 checkout. It passes a
   generated `--init-script` that sets `ndkPath` to the same NDK and sets `abiFilters` to the built
   ABIs, so no Media3 file is edited.
5. Copies the AAR to `out/media3-decoder-ffmpeg-<tag>-<abis joined by _>.aar` and writes
   `<aar>.sha256`. It checks that `jni/<abi>/libffmpegJNI.so` exists for every requested ABI.
   It then writes `out/NOTICE-ffmpeg.txt`, which contains:
   - the FFmpeg repo, branch and exact commit
   - the Media3 tag and commit
   - the NDK and API level
   - the enabled decoders
   - the exact `./configure` line for each ABI, taken from `FFMPEG_CONFIGURATION`
   - the full LGPL v2.1 text from FFmpeg's `COPYING.LGPLv2.1`

The script is idempotent and resumable. Each ABI's static libs are stamped with the Media3 tag,
FFmpeg commit, NDK, API level, host, decoders and upstream script hash. A re-run skips any ABI
whose stamp matches, and if a run is interrupted, the next one cleans the FFmpeg tree with
`git clean`, keeping `android-libs/`. Pass `--clean` to start from scratch.

### Usage

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home
export ANDROID_HOME=$HOME/Library/Android/sdk
tools/ffmpeg/build-ffmpeg-aar.sh                       # both ABIs, all pinned defaults
ABIS="arm64-v8a" tools/ffmpeg/build-ffmpeg-aar.sh      # one ABI
tools/ffmpeg/build-ffmpeg-aar.sh --help                # all flags / env vars
```

| Env var | Flag | Default |
|---|---|---|
| `MEDIA3_TAG` | `--media3-tag` | `1.11.1` |
| `FFMPEG_BRANCH` | `--ffmpeg-branch` | `release/6.0` |
| `FFMPEG_REPO` | `--ffmpeg-repo` | `https://github.com/FFmpeg/FFmpeg.git` |
| `ANDROID_NDK` | `--ndk` | `$ANDROID_HOME/ndk/$NDK_VERSION`, else `$ANDROID_NDK_HOME`, else newest under `$ANDROID_HOME/ndk` / `~/Library/Android/sdk/ndk` |
| `NDK_VERSION` | `--ndk-version` | `26.1.10909125` |
| `HOST_PLATFORM` | `--host` | auto: `darwin-x86_64` / `linux-x86_64` (the NDK's darwin toolchain is universal, so this also works on Apple Silicon) |
| `ABIS` | `--abis` | `armeabi-v7a arm64-v8a` |
| `ENABLED_DECODERS` | `--decoders` | the list above |
| `ANDROID_API` | `--api-level` | `23` |
| `WORK_DIR` / `OUT_DIR` | `--work-dir` / `--out-dir` | `tools/ffmpeg/work` / `tools/ffmpeg/out` |

Host tools needed: `git`, `make`, `bash`, `unzip`, and `shasum` or `sha256sum`. `nasm`,
`pkg-config` and autotools are **not** needed, because the ARM builds use clang's integrated
assembler and FFmpeg's hand-written `configure`.

### CI

`.github/workflows/ffmpeg.yml` runs on `workflow_dispatch` (with inputs for the tag, branch and
ABIs) and on tags matching `ffmpeg-*`. It runs on `ubuntu-latest` and:

1. Installs JDK 17, `ndk;26.1.10909125` and `cmake;3.22.1` through `sdkmanager`.
2. Restores the cached `tools/ffmpeg/work/ffmpeg` (the clone plus the stamped per-ABI libs).
3. Runs the script.
4. Uploads the AAR, the `.sha256` and `NOTICE-ffmpeg.txt` as artifacts.
5. On `ffmpeg-*` tags only, also attaches them to a GitHub Release.

## How `:ffmpeg` consumes it

This part is wired up by whoever owns the Gradle project, so it is only described here. The
`:ffmpeg` module is a thin wrapper around the prebuilt AAR:

- Put `media3-decoder-ffmpeg-1.11.1-armeabi-v7a_arm64-v8a.aar` (from CI or a local build) in
  `ffmpeg/libs/`. Either check the AAR's `.sha256` before building, or let the build check it.
- The module depends on the file (for example `api(files("libs/…aar"))`), or uses a
  `flatDir`-style wrapper. It must also bring in the AAR's own Maven dependencies, because a file
  dependency carries no POM: `androidx.media3:media3-decoder:1.11.1`,
  `androidx.media3:media3-exoplayer:1.11.1` and `androidx.annotation`.
- The AAR's `consumer-proguard` rules keep the JNI entry points.
- `:player` depends on `:ffmpeg` and uses `DefaultRenderersFactory` with
  `EXTENSION_RENDERER_MODE_ON` (PREFER only for MP2/LATM when no MediaCodec exists).
- The app's ABI splits (`armeabi-v7a`, `arm64-v8a`) pick the matching `libffmpegJNI.so`.
- `NOTICE-ffmpeg.txt` must be shipped in the APK (for example as an asset shown on the
  Settings → About → Licences screen).

## Verifying an AAR

```sh
unzip -l tools/ffmpeg/out/media3-decoder-ffmpeg-1.11.1-armeabi-v7a_arm64-v8a.aar | grep jni/
#   ... jni/arm64-v8a/libffmpegJNI.so
#   ... jni/armeabi-v7a/libffmpegJNI.so
(cd tools/ffmpeg/out && shasum -a 256 -c media3-decoder-ffmpeg-*.aar.sha256)
```

There must be exactly one `libffmpegJNI.so` per ABI. FFmpeg is linked statically into it, so
there is no separate `libavcodec.so`.

## Licensing notes (LGPL v2.1+)

Media3 code is Apache 2.0. The FFmpeg code inside `libffmpegJNI.so` is **LGPL v2.1 or later**,
because it is configured without `--enable-gpl`, `--enable-nonfree` or `--enable-version3`. The
script enforces this by checking `config.h`. Our obligations when shipping:

1. **Ship the notice.** Include `NOTICE-ffmpeg.txt` (LGPL text, decoders, configure flags) in the
   app's licence screen and with every distributed APK or AAR.
2. **Make the corresponding source available.** This means the exact FFmpeg commit (recorded in
   the NOTICE), the Media3 tag, this script and the configure flags. Linking to upstream commits
   is common practice. Mirroring a source tarball next to each GitHub Release is safer, and is a
   possible follow-up.
3. **Dynamic linking only.** FFmpeg must stay inside a separate, user-replaceable shared library
   (`libffmpegJNI.so`, loaded by `FfmpegLibrary`). Never link FFmpeg statically into other app
   code, and don't obfuscate or forbid reverse-engineering that would prevent relinking. The app's
   licence terms must not contradict the LGPL.
4. **No GPL components.** Don't add GPL-only parts (for example `--enable-gpl`, libx264,
   postproc) or non-free parts (for example libfdk-aac). Any change to `ENABLED_DECODERS` must
   stay within FFmpeg's LGPL decoders.

**Patents are unverified.** AC-3 patents have expired. E-AC-3 (Dolby Digital Plus), DTS (`dca`)
and TrueHD may still be covered by patents in some jurisdictions. The LGPL doesn't grant a patent
licence, and shipping software decoders for these formats is **the owner's call** and has not been
legally reviewed. If needed, the decoder list can drop `eac3`/`dca`/`truehd` and rely on
passthrough only.

## Local build status

**Succeeded on 2026-10-13 for both ABIs.** The build ran on the owner's Mac: Intel x86_64,
8 cores, macOS 15. It used JDK 17.0.8, `ndk;26.1.10909125` (installed with sdkmanager for this
build) and SDK `cmake;3.22.1`. No Homebrew or MacPorts tools were needed.

| Step | Time |
|---|---|
| FFmpeg configure + make, arm64-v8a | about 6 min |
| FFmpeg configure + make, armeabi-v7a | about 6 min |
| First Gradle run (downloads, build-logic) | about 5.5 min |
| Re-run with everything stamped | under 1 min (Gradle up-to-date) |

- **Output:** `out/media3-decoder-ffmpeg-1.11.1-armeabi-v7a_arm64-v8a.aar`, 1,651,357 bytes.
  - `jni/arm64-v8a/libffmpegJNI.so`: 1,618,056 bytes
  - `jni/armeabi-v7a/libffmpegJNI.so`: 1,508,616 bytes
  - `classes.jar`, `proguard.txt`
- **Pinned sources:** FFmpeg `release/6.0` @ `b37bf81a7a9782438e4fe06a249329e279694494`.
- **Licence checks:** `config.h` reports `LGPL version 2.1 or later` for both ABIs, with
  `CONFIG_GPL 0` and `CONFIG_NONFREE 0`.
- **Disk:** `work/` uses about 1.2 GB (Media3 checkout plus Gradle output).

One issue was found and fixed. AGP 9.0.1 rejects an `ndkPath` whose version differs from its
default `ndkVersion` (28.2.13676358), with error CXX1100. The Gradle init script therefore sets
both `ndkVersion` and `ndkPath`.

FFmpeg's configure prints a harmless warning: `aarch64-linux-android23-pkg-config not found`.
No external libraries are used.

The AAR's bytes are not guaranteed to be identical between rebuilds. Always compare against the
`.sha256` published with each CI artifact.
