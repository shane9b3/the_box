package app.thebox.core.sports

import java.io.File
import java.io.IOException

/**
 * (CHL integration review L4) The one debug-build check and read of the sports endpoint override, shared by the data
 * sources (`SportsEndpoints`, `EspnEndpoints`, `HockeyTechEndpoints` in `:data`) and the CHL logo check (`ChlLogoCheck` in
 * `:ui`), so the two modules cannot drift apart.
 *
 * "Debug build" is the **installed app's** `ApplicationInfo.FLAG_DEBUGGABLE`, read from `context.applicationInfo.flags` by
 * the caller: true for the debug build only; false for release and for the benchmark build (profileable, not debuggable).
 * `:core` has no Android types, so the flag's value is pinned here and checked against the platform constant by a `:ui` test.
 */
object SportsDebugOverride {
    /** `android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE` (API 1; `ChlLogoTest` pins it). */
    const val FLAG_DEBUGGABLE = 0x2

    /** Under `filesDir`; one line, an origin such as `http://10.0.2.2:8080` (tools/fakepanel/README.md). */
    const val FILE = "debug/sports_base"

    /** True when the installed app is debuggable ([appFlags] = `context.applicationInfo.flags`). */
    fun isDebuggable(appFlags: Int): Boolean = (appFlags and FLAG_DEBUGGABLE) != 0

    /**
     * The override file's text, or null: always null unless [debuggable] (the file is then **never opened**), and null when
     * the file is absent or unreadable. Call off the main thread.
     */
    fun text(filesDir: File, debuggable: Boolean): String? {
        if (!debuggable) return null
        return try {
            File(filesDir, FILE).takeIf { it.isFile }?.readText()
        } catch (e: IOException) {
            null
        }
    }
}
