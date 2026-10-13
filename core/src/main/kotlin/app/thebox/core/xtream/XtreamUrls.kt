package app.thebox.core.xtream

/** Xtream URL building with the §3.7.5 encoding rules. Commit-0 stub (public signatures fixed by §1.2.8); WP-C owns this file. */
@Suppress("unused", "UNUSED_PARAMETER")
class XtreamUrls(base: String, username: String, password: String) {
    fun playerApi(action: String?, params: Map<String, String> = emptyMap()): String = TODO("WP-C")
    fun getPhp(type: String = "m3u_plus", output: String = "ts"): String = TODO("WP-C")
    fun xmltv(): String = TODO("WP-C")
    fun live(streamId: Long, ext: String): String = TODO("WP-C")
    fun movie(streamId: Long, ext: String): String = TODO("WP-C")
    fun episode(episodeId: Long, ext: String): String = TODO("WP-C")
}
