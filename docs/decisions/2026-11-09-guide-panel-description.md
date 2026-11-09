# Guide panel: top-aligned, no channel meta, full description that can scroll

Date: 2026-11-09.
- Owner request, implemented to the lead's decisions below.
- Design amended: `docs/design/m3-guide.md` §3.9.1 and §3.15 (panel row); `docs/design/m5-setup-settings.md` §3.3.6 (new row) and §3.3.13 (simple-row total 36). Each amendment is dated 2026-11-09.

## Owner request (verbatim)

"in the guide i would like the selected shows name and description that is beside the miny player to be justified to the top of its area, remove the channel number the channel name and the catch-up days. fille the area below the show title with the show description, right now I think it maxes out at two lines, show the whole thing and if it longer than the area available it should have an option in the settings to scroll up slowly so you can read the full description"

## Lead decisions

1. **Top alignment.** The panel sits at the top of the header column, directly under the header tabs, with a 16 px gap (§3.15 "gap 16"). The `Spacer(weight 1f)` that pushed it down is gone. The panel's Row aligns the poster to the top.
2. **Meta row.** The panel no longer draws the channel-number chip, the channel name or the catch-up days. It keeps the genre and the ON NOW pill. There is no leading dot when the genre comes first. An empty meta row (no genre, not on now) takes no space. The `PanelState` fields (`channelNumber`, `channelName`, `catchupDays`) stay, because other code and tests read them.
3. **Description.**
   - It fills the remaining height below the title and time line, down to the bottom of the header column (y 461, above the grid). There is no maxLines cap.
   - If it fits, it is shown whole and static.
   - If it does not fit, the new setting decides:
     - **Off:** the last visible line ends in an ellipsis.
     - **On:** a 3 s hold at the top, then a linear scroll up at 24 design px/s until the last line is visible, a 3 s hold, then a fade back to the top over 400 ms (200 out, jump, 200 in). This repeats while the same programme stays focused.
   - A change of focused programme (title, time line) or of description restarts it from the top.
   - While it can scroll, the viewport has soft top and bottom edges. These are a `DstIn` vertical-gradient mask on the description's own offscreen layer: no blur, and nothing on an ancestor of the `SurfaceView`. The header column is a sibling of `VideoLayer`, never an ancestor (`GuideScreen` KDoc, m3 §3.15), and that stays true.
4. **Setting.** "Scroll long descriptions" is in Settings → Interface, after Performance, next to the other guide display rows. It is a simple on/off row (not ADVANCED) and is **On by default**. Its description reads: "Long program descriptions in the guide scroll slowly so you can read them all."
   - It is stored as `GuidePrefs.scrollDescriptions` and mapped to `GuideSettings.scrollDescriptions` by `GuidePrefsMapping` (both ways). It reaches `AppRoot` → `GuideScreen` → `FocusedPanel`.
   - It is backed up and restored with the guide settings document, like `showLogos`. An older document has no field and reads On.
   - The simple-row budget becomes **36**: Interface goes from 3 to 4, and `SimpleRowCountTest` follows.
   - When the system animator scale is 0, the panel treats the setting as Off and uses the ellipsis.
5. **Performance.**
   - The scroll offset and fade alpha are primitive snapshot states (`mutableFloatStateOf`).
   - A `LaunchedEffect` writes these states from a `withFrameNanos` loop that uses a preallocated frame callback.
   - They are read only in `Modifier.offset { }`, in the viewport's `graphicsLayer { }` and in its `drawWithCache` mask. The brushes are cached per size.
   - The panel never recomposes per frame (`FocusedPanelTest` checks the I-12 counter).
   - The description is inside the header column, which has its own layer, so the grid never redraws because of it.
   - I-18 still holds: `guide-panel` never fires on grid-only frames. A scrolling description fires it on its own frames, which is expected. The note is in `LayerScopeTest`'s KDoc. The scroll starts only after a 3 s hold, so it never fires during that test's run of moves.
   - The text style is unchanged: `BoxTextStyles.Body`, 26 px.

## Note

The genre arrives with the description, after the 150 ms debounce. For a programme that is not on now, the meta row therefore appears at that moment and moves the title down by one row. The lead's rule "an empty meta row takes no space" accepts this.
