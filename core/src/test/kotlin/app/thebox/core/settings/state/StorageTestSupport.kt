package app.thebox.core.settings.state

import app.thebox.core.storage.ManualAddressParser

/** A fixed [StoragePageEnv] for JVM tests. */
class FakeStoragePageEnv(var keyboard: Boolean = true, var smallPool: Boolean = false) : StoragePageEnv {
    override fun hasKeyboard() = keyboard
    override fun smallRemotePool() = smallPool
}

/**
 * Stands in for `ManualAddressParser` (WP-S1's body is a stub while WP-S4 is built): `nas`, `192.168.1.20:4445`,
 * `\\NAS\TV\Rewind`, `smb://nas/TV/Rewind`.
 */
fun stubParse(input: String): ManualAddressParser.Result {
    var t = input.trim()
    if (t.isEmpty()) return ManualAddressParser.Result.Invalid(ManualAddressParser.Reason.EMPTY)
    t = t.removePrefix("smb://").removePrefix("\\\\").replace('\\', '/')
    val parts = t.split('/').filter { it.isNotEmpty() }
    val hostPort = parts.first()
    val host = hostPort.substringBefore(':')
    val port = hostPort.substringAfter(':', "").toIntOrNull() ?: 445
    if (host.isBlank() || host.contains(' ')) return ManualAddressParser.Result.Invalid(ManualAddressParser.Reason.BAD_HOST)
    return ManualAddressParser.Result.Parsed(host, port, parts.getOrNull(1), parts.drop(2).joinToString("/"))
}
