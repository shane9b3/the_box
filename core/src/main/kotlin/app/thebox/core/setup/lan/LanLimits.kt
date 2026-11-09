package app.thebox.core.setup.lan

/** Named constants of the phone page's server (docs/design/m5-setup-settings.md §3.5, Appendix B). */
object LanLimits {
    const val PHONE_PORT_PREFERRED = 47100
    /** Random ports tried after [PHONE_PORT_PREFERRED] is taken (§3.5.2). */
    const val PORT_RANDOM_TRIES = 10
    const val PORT_RANDOM_FROM = 49152
    const val PORT_RANDOM_TO = 65535
    /** Wrong codes one address may send before the code rotates; twice this blocks the address (F2). */
    const val PAIR_MAX_WRONG = 5
    /** Wrong codes from all addresses that end the session: worst-case guess chance 30 / 10^6 (F2, 2026-11-09). */
    const val PAIR_MAX_WRONG_TOTAL = 30
    const val PAIR_IDLE_MS = 600_000L
    const val PAIR_MAX_MS = 1_800_000L
    const val NO_REQUEST_HINT_MS = 90_000L
    const val HTTP_MAX_HEADER_BYTES = 8_192
    const val HTTP_MAX_HEADERS = 40
    const val HTTP_MAX_JSON_BYTES = 65_536
    const val PHONE_UPLOAD_MAX_BYTES = 268_435_456L
    const val HTTP_SOCKET_TIMEOUT_MS = 15_000
    const val HTTP_MAX_SOCKETS = 6
    /** Concurrent connections one remote address may hold (F2): one host can never take every socket. */
    const val HTTP_MAX_SOCKETS_PER_ADDRESS = 4
    const val UPLOAD_CHUNK_BYTES = 65_536
    /** After a successful SETUP submission the server stays up this long so the phone can read the final state. */
    const val DONE_GRACE_MS = 5_000L
}
