#!/usr/bin/env bash
# Regenerates every branding asset from design/logo/The_Box.png (docs/design/m3-guide.md section 3.17).
# Outputs are committed; this is not part of the Gradle build.
#
# Requires Python 3 with Pillow, NumPy and scikit-image (potrace / ImageMagick / rsvg-convert are NOT needed).
# If the current python3 lacks them, a throwaway venv is created in $BOX_BRANDING_VENV
# (default: ${TMPDIR:-/tmp}/thebox-branding-venv) and the packages are pip-installed into it.
#
# Usage: tools/branding/make-assets.sh [preview-dir]
#   preview-dir (optional): also writes banner/foreground preview PNGs there.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
py=python3
if ! "$py" -c 'import PIL, numpy, skimage' 2>/dev/null; then
  venv="${BOX_BRANDING_VENV:-${TMPDIR:-/tmp}/thebox-branding-venv}"
  if [ ! -x "$venv/bin/python" ]; then
    echo "Creating venv $venv (requires Pillow, numpy, scikit-image from PyPI)" >&2
    python3 -m venv "$venv"
    "$venv/bin/pip" install -q pillow numpy scikit-image
  fi
  py="$venv/bin/python"
fi
exec "$py" "$here/make_assets.py" "$root" "${1:-}"
