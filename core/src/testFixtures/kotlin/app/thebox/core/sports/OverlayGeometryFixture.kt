package app.thebox.core.sports

/**
 * The values of `PlayerUi.overlayGeometry()` (docs/design/m6-score-bug.md §4.4, A-B2), shared by `:core`'s
 * `ScoreBugPlacementTest` and `:ui`'s `ScoreBugBoundsTest` (which checks `PlayerUi` agrees), so the numbers live once.
 */
object OverlayGeometryFixture {
    val DESIGN = OverlayGeometry(
        channelList = Box(48f, 48f, 668f, 1032f),
        stats = Box(64f, 56f, 664f, 564f),
        groupCard = Box(64f, 56f, 784f, 126f),
        picker = Box(1376f, 48f, 1872f, 928f),
        numberEntry = Box(1520f, 72f, 1840f, 232f),
        infoBarTop = 820f,
        quickBarTop = 952f,
    )
}
