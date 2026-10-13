package app.thebox.data.di

import javax.inject.Qualifier

/** OkHttp client with the API timeout profile (player_api.php, small JSON). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApiClient

/** OkHttp client with the large-file timeout profile (playlists, XMLTV, get_live_streams). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DownloadClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/** Process-lifetime scope (`SupervisorJob + Dispatchers.Default`), §3.10. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
