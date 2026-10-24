// WP-S1 replaces this stub (docs/design/m6-storage-locations.md §7.2; storage locations commit 0). WP-S4 (manual entry)
// and WP-S6 (the phone page's mirror in app.js, same vectors) compile and test against this shape.
package app.thebox.core.storage

/**
 * "Computer name or address" (§7.2, U-S4). Accepted forms: `nas`, `nas.local`, `192.168.1.20`, `192.168.1.20:4445`,
 * `\\NAS\TV\Rewind`, `smb://nas/TV/Rewind`. [Parsed.path] is `/`-separated, without leading or trailing separators;
 * [Parsed.share] null = host only (land on `ComputerShares`).
 */
object ManualAddressParser {
    sealed interface Result {
        data class Parsed(val host: String, val port: Int = StorageConstants.SMB_DEFAULT_PORT, val share: String? = null, val path: String = "") : Result
        data class Invalid(val reason: Reason) : Result
    }

    /** Why a typed address was refused (the editor shows reason + next step). */
    enum class Reason { EMPTY, BAD_HOST, BAD_PORT, UNSUPPORTED_SCHEME }

    fun parse(input: String): Result = TODO("WP-S1")
}
