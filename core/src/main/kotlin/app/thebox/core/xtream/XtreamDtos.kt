package app.thebox.core.xtream

/*
 * Plain Kotlin views of Xtream payloads (docs/design/m1-data-layer.md §1.5). No @Serializable: every field is
 * read through LenientJson by XtreamDecoders. Fields that panels omit or send as junk are null.
 */

data class XtreamLiveStream(
    val streamId: Long,
    val num: Int?,
    val name: String?,
    val streamType: String?,
    val streamIcon: String?,
    val epgChannelId: String?,
    val added: String?,
    val isAdult: Boolean,
    /** `category_id`; `"none"` when null (§3.7.2). */
    val categoryId: String,
    /** XUI `category_ids` (may repeat [categoryId]). */
    val categoryIds: List<String>,
    val customSid: String?,
    val tvArchive: Boolean?,
    val tvArchiveDuration: Int?,
    val directSource: String?,
    val thumbnail: String?,
)

data class XtreamVodStream(
    val streamId: Long,
    val num: Int?,
    val name: String?,
    val title: String?,
    val year: String?,
    val streamIcon: String?,
    val rating: Double?,
    val rating5: Double?,
    val added: String?,
    val categoryId: String,
    val categoryIds: List<String>,
    val containerExtension: String?,
    val customSid: String?,
    val directSource: String?,
    val tmdb: String?,
    val isAdult: Boolean,
)

data class XtreamSeries(
    val seriesId: Long,
    val num: Int?,
    val name: String?,
    val cover: String?,
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    /** `releaseDate ?: release_date`. */
    val releaseDate: String?,
    val lastModified: String?,
    val rating: Double?,
    val rating5: Double?,
    val backdropPaths: List<String>,
    val youtubeTrailer: String?,
    val episodeRunTime: String?,
    val categoryId: String,
    val categoryIds: List<String>,
)

data class XtreamSeriesInfo(
    val name: String?,
    val cover: String?,
    val plot: String?,
    val backdropPaths: List<String>,
    /** Sorted by number. Derived from the episode keys when the panel sends an empty `seasons`. */
    val seasons: List<Season>,
    /** Season number → episodes in panel order. */
    val episodes: Map<Int, List<Episode>>,
) {
    data class Season(val number: Int, val name: String?, val episodeCount: Int?, val airDate: String?, val cover: String?)

    data class Episode(
        /** Stream id for `/series/u/p/{id}.{ext}`. */
        val id: Long,
        val episodeNum: Int?,
        val title: String?,
        val containerExtension: String?,
        val season: Int,
        val added: String?,
        val customSid: String?,
        val directSource: String?,
        val plot: String?,
        val image: String?,
        val releaseDate: String?,
        val rating: Double?,
        val durationSecs: Long?,
    )
}

data class XtreamVodInfo(
    val streamId: Long?,
    val name: String?,
    val originalName: String?,
    val tmdbId: String?,
    val image: String?,
    val backdropPaths: List<String>,
    val youtubeTrailer: String?,
    val genre: String?,
    val plot: String?,
    val cast: String?,
    val director: String?,
    val releaseDate: String?,
    /** `duration_secs`, else `duration` parsed as `HH:MM:SS`. */
    val durationSecs: Long?,
    val rating: Double?,
    val country: String?,
    val containerExtension: String?,
    val categoryId: String?,
    val directSource: String?,
)
