package app.thebox.core.xtream

import java.io.InputStream

internal object Fixtures {
    fun open(path: String): InputStream =
        Fixtures::class.java.getResourceAsStream("/$path") ?: error("fixture $path missing")

    fun <T> use(path: String, block: (InputStream) -> T): T = open(path).use(block)
}
