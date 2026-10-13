package app.thebox.core.catchup

import app.thebox.core.model.TimeBase
import app.thebox.core.source.PlaylistType
import app.thebox.core.xtream.XtreamAccountInfo

/** Panel zone, skew and template zone (docs/design/m1-data-layer.md §3.8.3). Commit-0 stub; WP-C owns this file. */
class TimeBaseFactoryImpl : TimeBaseFactory {
    override fun fromAccount(type: PlaylistType, info: XtreamAccountInfo, settings: TimeBaseSettings): TimeBase = TODO("WP-C")
    override fun forM3u(settings: TimeBaseSettings): TimeBase = TODO("WP-C")
}
