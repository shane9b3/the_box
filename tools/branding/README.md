# Branding assets

Regenerate everything from `design/logo/The_Box.png` (black on white, 1158 x 480):

    tools/branding/make-assets.sh [preview-dir]

Needs Python 3 with Pillow, NumPy and scikit-image. If `python3` lacks them the script creates a venv in
`$BOX_BRANDING_VENV` (default `$TMPDIR/thebox-branding-venv`) and pip-installs them. No potrace, ImageMagick or
rsvg-convert. Outputs are committed; this is not part of the Gradle build. Never edit the generated XML by hand.

## Produced (under `app/src/main/res/`, except the wordmark)
- `ui/src/main/res/drawable/wordmark.xml`: white monochrome vector of the full logo, viewport 1158 x 480 (nominal 96 x 40 dp), tint at use site. It lives in `:ui` because the guide header draws it (§3.17); `:app` sees `:ui` resources.
- `drawable/ic_launcher_foreground.xml`: frame + "Box" only, 58 dp wide on a 108 dp canvas (inside the 66 dp safe circle).
- `drawable/ic_launcher_background.xml`: `#0A0D13`. `mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml` (adaptive, with a monochrome layer).
- `drawable-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/banner.png`: 320x180, 480x270, 640x360, 960x540, 1280x720 dp-scaled; white wordmark on `#0A0D13`, 24 dp padding, no gradient.
- `mipmap-{mdpi..xxxhdpi}/ic_launcher.png` and `ic_launcher_round.png`: 48, 72, 96, 144, 192 px (rounded square / circle on `#0A0D13`, frame + "Box").
- The manifest uses `@mipmap/ic_launcher`, `@mipmap/ic_launcher_round`, `@drawable/banner`. The M0 placeholder `drawable/banner.xml` and `drawable/ic_launcher.xml` were deleted (they would collide).

## Deviations
- Tracing is built in (skimage marching squares + Douglas-Peucker, even-odd polygons, tolerance 0.55 px), not `potrace`, because potrace is not installed. Paths are polylines, indistinguishable at TV sizes.
- Banner is rasterised from the PNG with Lanczos (alpha derived from luminance), not from the SVG with `rsvg-convert`; five densities instead of only xhdpi.
- Foreground is derived by dropping contours left of x = 148 (the rotated "The"), which is the frame bbox and "Box".
