package app.thebox.core.settings.fakes

import app.thebox.core.guide.GuideSource
import app.thebox.core.guide.GuideSourceEntry
import app.thebox.core.guide.SourceSection
import app.thebox.core.model.StableKey
import app.thebox.core.refresh.ChannelIdentity

/**
 * The shared M5 fixture library (docs/design/m5-setup-settings.md §1.9 item 8): two playlists, 40 groups of which two
 * adult, 2,000 channels, 30 hidden rows, three favourites lists, one reminder per playlist. Plain data, deterministic;
 * page builders, the parental predicate tables, the backup codec and the reachability proof all read the same library.
 */
object SettingsFixtures {
    data class Playlist(val id: Long, val uid: String, val name: String, val type: String, val displaySource: String)
    data class Group(val playlistId: Long, val groupKey: String, val name: String, val isAdult: Boolean, val channelCount: Int)
    data class Channel(
        val identity: ChannelIdentity,
        val name: String,
        val number: Int,
        val groupKey: String,
        val isAdult: Boolean,
        val hidden: Boolean,
    )
    data class FavouriteList(val id: Long, val key: String, val name: String, val members: List<ChannelIdentity>)
    data class Reminder(val identity: ChannelIdentity, val title: String, val startMin: Int, val endMin: Int)

    const val GROUPS_PER_PLAYLIST = 20
    const val CHANNELS_PER_GROUP = 50

    val playlists: List<Playlist> = listOf(
        Playlist(1, "0f1e2d3c4b5a69788796a5b4c3d2e1f0", "Provider A", "XTREAM", "panel.example.com:8080"),
        Playlist(2, "a1b2c3d4e5f60718293a4b5c6d7e8f90", "My list", "M3U", "lists.example.org"),
    )

    /** 20 groups per playlist; group 13 of each playlist is adult ("Adult 18+"), so two adult groups in all. */
    val groups: List<Group> = playlists.flatMap { p ->
        (0 until GROUPS_PER_PLAYLIST).map { g ->
            val adult = g == 13
            Group(p.id, "g:${p.id}-$g", if (adult) "Adult 18+" else "Group ${p.id}.$g", adult, CHANNELS_PER_GROUP)
        }
    }

    /** 2,000 channels (50 per group); the first 15 channels of group 2 in each playlist are hidden (30 rows). */
    val channels: List<Channel> = groups.flatMapIndexed { gi, g ->
        val local = gi % GROUPS_PER_PLAYLIST
        (0 until CHANNELS_PER_GROUP).map { c ->
            val n = local * CHANNELS_PER_GROUP + c + 1
            Channel(
                identity = ChannelIdentity(g.playlistId, StableKey("xc:${g.playlistId}-$n")),
                name = if (g.isAdult) "Late $n" else "Channel ${g.playlistId}.$n",
                number = n,
                groupKey = g.groupKey,
                isAdult = g.isAdult,
                hidden = local == 2 && c < 15,
            )
        }
    }

    val hidden: List<Channel> get() = channels.filter { it.hidden }

    val favouriteLists: List<FavouriteList> = listOf(
        FavouriteList(1, "fav-default", "Favourites", channels.filter { !it.isAdult && !it.hidden }.take(12).map { it.identity }),
        FavouriteList(2, "fav-news", "News", channels.filter { it.identity.playlistId == 2L && !it.isAdult }.take(5).map { it.identity }),
        FavouriteList(3, "fav-empty", "Empty", emptyList()),
    )

    val reminders: List<Reminder> = playlists.map { p ->
        val ch = channels.first { it.identity.playlistId == p.id }
        Reminder(ch.identity, "Evening news", startMin = 29_000_000, endMin = 29_000_030)
    }

    /** The Groups panel entries for [playlistId] as M3 lists them (adult groups omitted while locked). */
    fun sourceEntries(playlistId: Long, includeAdult: Boolean = false): List<GuideSourceEntry> =
        groups.filter { it.playlistId == playlistId && (includeAdult || !it.isAdult) }.map {
            GuideSourceEntry(GuideSource.Group(it.playlistId, it.groupKey), it.name, it.channelCount, SourceSection.GROUP, null, locked = it.isAdult)
        }
}
