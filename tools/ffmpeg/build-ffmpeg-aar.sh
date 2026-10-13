#!/usr/bin/env bash
#
# build-ffmpeg-aar.sh - reproducibly build the Media3 decoder_ffmpeg (audio-only,
# LGPL) extension AAR for The Box.
#
# Pipeline:
#   1. shallow-clone androidx/media at MEDIA3_TAG            -> $WORK_DIR/media
#   2. shallow-clone FFmpeg at FFMPEG_BRANCH                 -> $WORK_DIR/ffmpeg
#      and symlink it to libraries/decoder_ffmpeg/src/main/jni/ffmpeg
#      (the location the decoder_ffmpeg README and CMakeLists.txt expect)
#   3. run the module's own jni/build_ffmpeg.sh (filtered to the requested ABIs,
#      flags untouched) once per ABI
#   4. ./gradlew :lib-decoder-ffmpeg:assembleRelease with the repo's own Gradle
#   5. copy the AAR to $OUT_DIR with a SHA-256 file and NOTICE-ffmpeg.txt
#
# Idempotent / resumable: clones are reused when already at the right tag/branch,
# and an ABI's FFmpeg build is skipped when its stamp matches the current config.
#
# Every option can be given as an env var or a flag (flags win):
#   --media3-tag TAG        MEDIA3_TAG       (default 1.11.1)
#   --ffmpeg-branch BRANCH  FFMPEG_BRANCH    (default release/6.0, per the README at the tag)
#   --ffmpeg-repo URL       FFMPEG_REPO      (default https://github.com/FFmpeg/FFmpeg.git, mirror of git.ffmpeg.org)
#   --ndk PATH              ANDROID_NDK      (default: $ANDROID_HOME/ndk/$NDK_VERSION, else
#                                             $ANDROID_NDK_HOME, else newest under $ANDROID_HOME/ndk
#                                             or ~/Library/Android/sdk/ndk)
#   --ndk-version VER       NDK_VERSION      (preferred NDK, default 26.1.10909125 = r26b, the README's tested NDK)
#   --host PLATFORM         HOST_PLATFORM    (darwin-x86_64 | linux-x86_64; auto-detected)
#   --abis "A B"            ABIS             (default "armeabi-v7a arm64-v8a")
#   --decoders "d1 d2"      ENABLED_DECODERS (default: see DEFAULT_DECODERS below)
#   --api-level N           ANDROID_API      (FFmpeg toolchain API level; default 23 = Media3 minSdk,
#                                             must be <= the app's minSdk (25))
#   --work-dir DIR          WORK_DIR         (default tools/ffmpeg/work)
#   --out-dir DIR           OUT_DIR          (default tools/ffmpeg/out)
#   --clean                 CLEAN=1          (wipe WORK_DIR first)
#   -h | --help
#
# Requires: bash, git, make, curl-free (everything comes from git), a JDK 17+ in
# JAVA_HOME (or on PATH), an Android SDK (ANDROID_HOME / ANDROID_SDK_ROOT) with
# CMake >= 3.21, and an NDK.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

DEFAULT_DECODERS="ac3 eac3 dca mp3 mp2 aac aac_latm truehd vorbis opus flac alac pcm_mulaw pcm_alaw"

MEDIA3_TAG="${MEDIA3_TAG:-1.11.1}"
MEDIA3_REPO="${MEDIA3_REPO:-https://github.com/androidx/media.git}"
FFMPEG_BRANCH="${FFMPEG_BRANCH:-release/6.0}"
FFMPEG_REPO="${FFMPEG_REPO:-https://github.com/FFmpeg/FFmpeg.git}"
NDK_VERSION="${NDK_VERSION:-26.1.10909125}"
ANDROID_NDK="${ANDROID_NDK:-}"
HOST_PLATFORM="${HOST_PLATFORM:-}"
ABIS="${ABIS:-armeabi-v7a arm64-v8a}"
ENABLED_DECODERS="${ENABLED_DECODERS:-$DEFAULT_DECODERS}"
ANDROID_API="${ANDROID_API:-23}"
WORK_DIR="${WORK_DIR:-$SCRIPT_DIR/work}"
OUT_DIR="${OUT_DIR:-$SCRIPT_DIR/out}"
CLEAN="${CLEAN:-0}"

usage() { sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed '$d' | sed 's/^# \{0,1\}//'; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --media3-tag)    MEDIA3_TAG="$2"; shift 2 ;;
    --ffmpeg-branch) FFMPEG_BRANCH="$2"; shift 2 ;;
    --ffmpeg-repo)   FFMPEG_REPO="$2"; shift 2 ;;
    --ndk)           ANDROID_NDK="$2"; shift 2 ;;
    --ndk-version)   NDK_VERSION="$2"; shift 2 ;;
    --host)          HOST_PLATFORM="$2"; shift 2 ;;
    --abis)          ABIS="$2"; shift 2 ;;
    --decoders)      ENABLED_DECODERS="$2"; shift 2 ;;
    --api-level)     ANDROID_API="$2"; shift 2 ;;
    --work-dir)      WORK_DIR="$2"; shift 2 ;;
    --out-dir)       OUT_DIR="$2"; shift 2 ;;
    --clean)         CLEAN=1; shift ;;
    -h|--help)       usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

log()  { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33mWARN: %s\033[0m\n' "$*" >&2; }
die()  { printf '\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"; else shasum -a 256 "$@"; fi
}

# ---------------------------------------------------------------- validation
read -r -a ABI_LIST <<<"$ABIS"
read -r -a DECODER_LIST <<<"$ENABLED_DECODERS"
[[ ${#ABI_LIST[@]} -gt 0 ]] || die "ABIS is empty"
[[ ${#DECODER_LIST[@]} -gt 0 ]] || die "ENABLED_DECODERS is empty"
for abi in "${ABI_LIST[@]}"; do
  case "$abi" in armeabi-v7a|arm64-v8a|x86|x86_64) ;; *) die "Unsupported ABI '$abi'";; esac
done
for d in "${DECODER_LIST[@]}"; do
  [[ "$d" =~ ^[a-z0-9_]+$ ]] || die "Bad decoder name '$d'"
done
# LGPL guard: nothing we pass may switch on GPL / nonfree / version3 code.
case " $ENABLED_DECODERS " in *gpl*|*nonfree*|*version3*) die "GPL/nonfree tokens in ENABLED_DECODERS";; esac

if [[ -z "$HOST_PLATFORM" ]]; then
  case "$(uname -s)" in
    Darwin) HOST_PLATFORM="darwin-x86_64" ;;  # NDK ships universal binaries under darwin-x86_64
    Linux)  HOST_PLATFORM="linux-x86_64" ;;
    *) die "Cannot detect HOST_PLATFORM on $(uname -s)" ;;
  esac
fi

SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK_DIR" && -d "$HOME/Library/Android/sdk" ]]; then SDK_DIR="$HOME/Library/Android/sdk"; fi
[[ -n "$SDK_DIR" && -d "$SDK_DIR" ]] || die "Set ANDROID_HOME to an Android SDK"
export ANDROID_HOME="$SDK_DIR"

newest_ndk() {
  local base
  for base in "$SDK_DIR/ndk" "$HOME/Library/Android/sdk/ndk"; do
    if [[ -d "$base" ]]; then
      # shellcheck disable=SC2012
      local v; v="$(ls -1 "$base" 2>/dev/null | sort -t. -k1,1n -k2,2n -k3,3n | tail -1)"
      if [[ -n "$v" ]]; then echo "$base/$v"; return 0; fi
    fi
  done
  return 1
}
if [[ -z "$ANDROID_NDK" ]]; then
  if [[ -d "$SDK_DIR/ndk/$NDK_VERSION" ]]; then
    ANDROID_NDK="$SDK_DIR/ndk/$NDK_VERSION"
  elif [[ -n "${ANDROID_NDK_HOME:-}" && -d "${ANDROID_NDK_HOME}" ]]; then
    ANDROID_NDK="$ANDROID_NDK_HOME"
  else
    ANDROID_NDK="$(newest_ndk)" || die "No NDK found; install with: sdkmanager \"ndk;$NDK_VERSION\""
  fi
fi
[[ -f "$ANDROID_NDK/source.properties" ]] || die "ANDROID_NDK=$ANDROID_NDK is not an NDK"
NDK_REVISION="$(sed -n 's/^Pkg.Revision *= *//p' "$ANDROID_NDK/source.properties" | tr -d '\r')"
if [[ "$NDK_REVISION" != "$NDK_VERSION" ]]; then
  warn "Using NDK $NDK_REVISION but the decoder_ffmpeg README at $MEDIA3_TAG is tested on r26b ($NDK_VERSION)."
  warn "Install it with: sdkmanager \"ndk;$NDK_VERSION\""
fi
[[ -d "$ANDROID_NDK/toolchains/llvm/prebuilt/$HOST_PLATFORM/bin" ]] \
  || die "NDK has no toolchain for HOST_PLATFORM=$HOST_PLATFORM"

if [[ -n "${JAVA_HOME:-}" ]]; then export JAVA_HOME; fi
command -v git >/dev/null || die "git is required"
command -v make >/dev/null || die "make is required"

mkdir -p "$WORK_DIR" "$OUT_DIR"
WORK_DIR="$(cd "$WORK_DIR" && pwd)"
OUT_DIR="$(cd "$OUT_DIR" && pwd)"
if [[ "$CLEAN" == "1" ]]; then
  log "Cleaning $WORK_DIR"
  rm -rf "${WORK_DIR:?}/media" "${WORK_DIR:?}/ffmpeg" "${WORK_DIR:?}/gradle-init.gradle"
fi

MEDIA_DIR="$WORK_DIR/media"
FFMPEG_DIR="$WORK_DIR/ffmpeg"
MODULE_PATH="$MEDIA_DIR/libraries/decoder_ffmpeg/src/main"   # README's FFMPEG_MODULE_PATH

log "Config"
cat <<CFG
  MEDIA3_TAG       = $MEDIA3_TAG
  FFMPEG_BRANCH    = $FFMPEG_BRANCH ($FFMPEG_REPO)
  ANDROID_NDK      = $ANDROID_NDK ($NDK_REVISION)
  HOST_PLATFORM    = $HOST_PLATFORM
  ANDROID_API      = $ANDROID_API
  ABIS             = ${ABI_LIST[*]}
  ENABLED_DECODERS = ${DECODER_LIST[*]}
  WORK_DIR         = $WORK_DIR
  OUT_DIR          = $OUT_DIR
  JAVA_HOME        = ${JAVA_HOME:-<java on PATH>}
CFG

# ---------------------------------------------------------------- 1. Media3
log "Media3 checkout @ $MEDIA3_TAG"
if [[ -d "$MEDIA_DIR/.git" ]] && [[ "$(git -C "$MEDIA_DIR" describe --tags --exact-match 2>/dev/null || true)" == "$MEDIA3_TAG" ]]; then
  echo "Reusing $MEDIA_DIR (already at $MEDIA3_TAG)"
else
  if ! git ls-remote --exit-code --tags "$MEDIA3_REPO" "refs/tags/$MEDIA3_TAG" >/dev/null; then
    die "Tag $MEDIA3_TAG not found in $MEDIA3_REPO"
  fi
  rm -rf "$MEDIA_DIR"
  git -c advice.detachedHead=false clone --depth 1 --branch "$MEDIA3_TAG" "$MEDIA3_REPO" "$MEDIA_DIR"
fi
MEDIA3_COMMIT="$(git -C "$MEDIA_DIR" rev-parse HEAD)"
UPSTREAM_SCRIPT="$MODULE_PATH/jni/build_ffmpeg.sh"
[[ -f "$UPSTREAM_SCRIPT" ]] || die "build_ffmpeg.sh not found at $UPSTREAM_SCRIPT"
if grep -Eq -- '--enable-(gpl|nonfree|version3)' "$UPSTREAM_SCRIPT"; then
  die "Upstream build_ffmpeg.sh enables GPL/nonfree/version3 code; refusing to build"
fi
README_BRANCH="$(grep -Eo -- '--branch=release/[0-9.]+' "$MEDIA_DIR/libraries/decoder_ffmpeg/README.md" | head -1 | cut -d= -f2 || true)"
if [[ -n "$README_BRANCH" && "$README_BRANCH" != "$FFMPEG_BRANCH" ]]; then
  warn "decoder_ffmpeg README at $MEDIA3_TAG recommends FFmpeg $README_BRANCH, building $FFMPEG_BRANCH"
fi

# ---------------------------------------------------------------- 2. FFmpeg
log "FFmpeg checkout @ $FFMPEG_BRANCH"
ffmpeg_ok=0
if [[ -d "$FFMPEG_DIR/.git" ]]; then
  cur_branch="$(git -C "$FFMPEG_DIR" rev-parse --abbrev-ref HEAD 2>/dev/null || true)"
  if [[ "$cur_branch" == "$FFMPEG_BRANCH" ]]; then ffmpeg_ok=1; fi
fi
if [[ "$ffmpeg_ok" == "1" ]]; then
  echo "Reusing $FFMPEG_DIR (branch $FFMPEG_BRANCH @ $(git -C "$FFMPEG_DIR" rev-parse --short HEAD))"
else
  rm -rf "$FFMPEG_DIR"
  git clone --depth 1 --branch "$FFMPEG_BRANCH" "$FFMPEG_REPO" "$FFMPEG_DIR"
fi
FFMPEG_COMMIT="$(git -C "$FFMPEG_DIR" rev-parse HEAD)"
LINK="$MODULE_PATH/jni/ffmpeg"
if [[ -L "$LINK" ]]; then
  [[ "$(readlink "$LINK")" == "$FFMPEG_DIR" ]] || { rm -f "$LINK"; ln -s "$FFMPEG_DIR" "$LINK"; }
elif [[ -e "$LINK" ]]; then
  die "$LINK exists and is not a symlink; remove it or run with --clean"
else
  ln -s "$FFMPEG_DIR" "$LINK"
fi

# ---------------------------------------------------------------- 3. FFmpeg build
# The upstream script hard-codes armeabi-v7a, arm64-v8a, x86, x86_64 (the README
# says to edit it for other ABI sets). We generate a per-ABI copy that keeps the
# header (COMMON_OPTIONS, decoder flags, toolchain checks) verbatim and only the
# ./configure ... make clean block for that ABI.
filter_script() { # $1 = abi, $2 = output path
  awk -v abi="$1" '
    BEGIN { inblock = 0; keep = 0; header = 1 }
    /^\.\/configure/ { header = 0; inblock = 1; keep = 0; buf = "" }
    header { print; next }
    inblock {
      buf = buf $0 "\n"
      if ($0 ~ ("--libdir=android-libs/" abi "( |\\\\|$)")) keep = 1
      if ($0 ~ /^make clean/) { if (keep) printf "%s", buf; inblock = 0 }
      next
    }
    { print }
  ' "$UPSTREAM_SCRIPT" > "$2"
  chmod +x "$2"
  [[ "$(grep -c '^\./configure' "$2")" == "1" ]] || die "Could not isolate the $1 block in build_ffmpeg.sh"
}

CONFIG_KEY="media3=$MEDIA3_TAG ffmpeg=$FFMPEG_COMMIT ndk=$NDK_REVISION api=$ANDROID_API host=$HOST_PLATFORM decoders=${DECODER_LIST[*]} script=$(sha256 "$UPSTREAM_SCRIPT" | cut -d' ' -f1)"

for abi in "${ABI_LIST[@]}"; do
  libdir="$FFMPEG_DIR/android-libs/$abi"
  stamp="$libdir/.thebox-stamp"
  if [[ -f "$stamp" && "$(cat "$stamp")" == "$CONFIG_KEY" && -f "$libdir/libavcodec.a" \
        && -f "$libdir/libswresample.a" && -f "$libdir/libavutil.a" ]]; then
    log "FFmpeg $abi: up to date, skipping"
    continue
  fi
  log "FFmpeg $abi: configure + make"
  rm -rf "$libdir"
  # Start from a pristine tree (a previous run may have died mid-make), keeping other ABIs' output.
  git -C "$FFMPEG_DIR" clean -fdxq -e android-libs
  per_abi_script="$MODULE_PATH/jni/build_ffmpeg.$abi.sh"
  filter_script "$abi" "$per_abi_script"
  ( cd "$MODULE_PATH/jni" && \
    ./"build_ffmpeg.$abi.sh" "$MODULE_PATH" "$ANDROID_NDK" "$HOST_PLATFORM" "$ANDROID_API" "${DECODER_LIST[@]}" )
  # config.h survives `make clean`; it records the exact configure line and licence switches.
  conf_line="$(sed -n 's/^#define FFMPEG_CONFIGURATION "\(.*\)"$/\1/p' "$FFMPEG_DIR/config.h")"
  grep -q '^#define CONFIG_GPL 0' "$FFMPEG_DIR/config.h"     || die "$abi: CONFIG_GPL is not 0"
  grep -q '^#define CONFIG_NONFREE 0' "$FFMPEG_DIR/config.h" || die "$abi: CONFIG_NONFREE is not 0"
  # version3 would make the build LGPLv3 (the NOTICE and docs promise LGPL v2.1+).
  grep -q '^#define CONFIG_VERSION3 0' "$FFMPEG_DIR/config.h" || die "$abi: CONFIG_VERSION3 is not 0"
  license="$(sed -n 's/^#define FFMPEG_LICENSE "\(.*\)"$/\1/p' "$FFMPEG_DIR/config.h")"
  [[ "$license" == "LGPL version 2.1 or later" ]] || die "$abi: FFmpeg licence is '$license', expected LGPL version 2.1 or later"
  for lib in avcodec swresample avutil; do
    [[ -f "$libdir/lib$lib.a" ]] || die "$abi: lib$lib.a missing after build"
  done
  printf '%s\n' "$conf_line" > "$libdir/configure-flags.txt"
  printf '%s\n' "$license" > "$libdir/license.txt"
  printf '%s' "$CONFIG_KEY" > "$stamp"
done

# ---------------------------------------------------------------- 4. Gradle AAR
log "Gradle :lib-decoder-ffmpeg:assembleRelease"
INIT_SCRIPT="$WORK_DIR/gradle-init.gradle"
cat > "$INIT_SCRIPT" <<'GRADLE'
// Generated by tools/ffmpeg/build-ffmpeg-aar.sh: pin the NDK and restrict the
// native build to the ABIs we built FFmpeg for, without editing Media3 sources.
def abis = System.getenv('THEBOX_ABIS').trim().split(/\s+/) as List
def ndk = System.getenv('THEBOX_NDK')
def ndkVersion = System.getenv('THEBOX_NDK_VERSION')
allprojects { p ->
  p.plugins.withId('com.android.library') {
    if (p.path == ':lib-decoder-ffmpeg') {
      // AGP requires ndkVersion to match the NDK at ndkPath (CXX1100).
      p.android.ndkVersion = ndkVersion
      p.android.ndkPath = ndk
      p.android.defaultConfig.ndk.abiFilters.clear()
      p.android.defaultConfig.ndk.abiFilters.addAll(abis)
    }
  }
}
GRADLE
printf 'sdk.dir=%s\n' "$SDK_DIR" > "$MEDIA_DIR/local.properties"
AAR_BUILD_DIR="$MEDIA_DIR/libraries/decoder_ffmpeg/buildout"
rm -rf "$AAR_BUILD_DIR/outputs/aar"
( cd "$MEDIA_DIR" && \
  THEBOX_ABIS="${ABI_LIST[*]}" THEBOX_NDK="$ANDROID_NDK" THEBOX_NDK_VERSION="$NDK_REVISION" \
  ./gradlew --no-daemon --console=plain --init-script "$INIT_SCRIPT" \
    :lib-decoder-ffmpeg:assembleRelease )

AAR_SRC="$(find "$MEDIA_DIR/libraries/decoder_ffmpeg" -path '*/outputs/aar/*release*.aar' -newer "$INIT_SCRIPT" | head -1)"
[[ -n "$AAR_SRC" && -f "$AAR_SRC" ]] || die "No release AAR produced"

# ---------------------------------------------------------------- 5. Outputs
ABI_TAG="$(IFS=_; echo "${ABI_LIST[*]}")"
AAR_NAME="media3-decoder-ffmpeg-$MEDIA3_TAG-$ABI_TAG.aar"
AAR_OUT="$OUT_DIR/$AAR_NAME"
cp "$AAR_SRC" "$AAR_OUT"

log "Verifying $AAR_NAME"
listing="$(unzip -l "$AAR_OUT")"
for abi in "${ABI_LIST[@]}"; do
  grep -q "jni/$abi/libffmpegJNI.so" <<<"$listing" || die "AAR lacks jni/$abi/libffmpegJNI.so"
done
grep 'jni/' <<<"$listing"

( cd "$OUT_DIR" && sha256 "$AAR_NAME" > "$AAR_NAME.sha256" )
cat "$OUT_DIR/$AAR_NAME.sha256"

NOTICE="$OUT_DIR/NOTICE-ffmpeg.txt"
{
  echo "The Box - FFmpeg notice"
  echo "======================="
  echo
  echo "This application includes the Media3 FFmpeg audio decoder extension"
  echo "(androidx.media3 decoder_ffmpeg, Apache License 2.0), which statically"
  echo "links a subset of FFmpeg (libavcodec, libswresample, libavutil) into"
  echo "libffmpegJNI.so. FFmpeg is licensed under the GNU Lesser General Public"
  echo "License version 2.1 or later. No GPL or non-free FFmpeg components are"
  echo "enabled (configured without --enable-gpl, --enable-nonfree, --enable-version3)."
  echo "libffmpegJNI.so is a separate shared library loaded at runtime and can be"
  echo "replaced by the user with one built from modified FFmpeg sources."
  echo
  echo "FFmpeg source:   $FFMPEG_REPO"
  echo "                 branch $FFMPEG_BRANCH, commit $FFMPEG_COMMIT"
  echo "                 https://github.com/FFmpeg/FFmpeg/tree/$FFMPEG_COMMIT"
  echo "Media3 source:   $MEDIA3_REPO tag $MEDIA3_TAG, commit $MEDIA3_COMMIT"
  echo "Build script:    libraries/decoder_ffmpeg/src/main/jni/build_ffmpeg.sh at that tag,"
  echo "                 driven by tools/ffmpeg/build-ffmpeg-aar.sh in The Box repository"
  echo "Android NDK:     $NDK_REVISION, host $HOST_PLATFORM, API level $ANDROID_API"
  echo "Artifact:        $AAR_NAME"
  echo "                 sha256 $(cut -d' ' -f1 "$OUT_DIR/$AAR_NAME.sha256")"
  echo
  echo "(\$NDK_PATH below = Android NDK $NDK_REVISION root.)"
  echo
  echo "Enabled FFmpeg decoders:"
  for d in "${DECODER_LIST[@]}"; do echo "  - $d"; done
  echo
  echo "Exact FFmpeg configure flags per ABI:"
  for abi in "${ABI_LIST[@]}"; do
    echo
    echo "[$abi] licence: $(cat "$FFMPEG_DIR/android-libs/$abi/license.txt")"
    # Replace the local NDK path so the notice is machine-independent.
    echo "./configure $(sed "s|$ANDROID_NDK|\$NDK_PATH|g" "$FFMPEG_DIR/android-libs/$abi/configure-flags.txt")"
  done
  echo
  echo "To rebuild: tools/ffmpeg/build-ffmpeg-aar.sh --media3-tag $MEDIA3_TAG \\"
  echo "  --ffmpeg-branch $FFMPEG_BRANCH --api-level $ANDROID_API --abis \"${ABI_LIST[*]}\" \\"
  echo "  --decoders \"${DECODER_LIST[*]}\""
  echo
  echo "----------------------------------------------------------------------"
  echo "GNU LESSER GENERAL PUBLIC LICENSE, Version 2.1 (FFmpeg COPYING.LGPLv2.1)"
  echo "----------------------------------------------------------------------"
  echo
  cat "$FFMPEG_DIR/COPYING.LGPLv2.1"
} > "$NOTICE"

log "Done"
ls -l "$AAR_OUT" "$AAR_OUT.sha256" "$NOTICE"
