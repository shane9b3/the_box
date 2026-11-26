package app.thebox.player.controller

/**
 * (NFL hub, docs/design/m6-nfl-hub.md §4.5; lead ruling 4, arch m11) The one rendition cap for clip cycles (`Spec.Clip`:
 * NHL and ESPN highlights), applied in `PlaybackControllerImpl.applySwitches`:
 *
 * - Normal devices: **no cap** (adaptive selection decides; today's behaviour for NHL clips).
 * - Low-RAM devices (`EngineEnvironment.isLowRam`): at most [LOW_RAM_MAX_WIDTH] × [LOW_RAM_MAX_HEIGHT] at
 *   [LOW_RAM_MAX_FPS], so ESPN's 720p59.94 top variants (and anything larger) are never picked on a Fire TV Stick class
 *   device.
 * - Every other cycle (live, archive) clears both constraints explicitly: the track-selection parameters are rebuilt from
 *   the player's current value, so a clip's cap would otherwise persist into the resumed channel.
 *
 * The owner's stutter of NHL replays on a Sony Bravia 4K Google TV is **undiagnosed** (clips get no AFR by design, hub
 * §6.5). This file is where a cap for **all** devices goes once that is diagnosed, if it points at 60 fps content (owner
 * question Q3): a `MAX_FPS` here, applied to every clip cycle beside the low-RAM pair.
 */
internal object ClipRendition {
    const val LOW_RAM_MAX_WIDTH = 1280
    const val LOW_RAM_MAX_HEIGHT = 720
    const val LOW_RAM_MAX_FPS = 30
}
