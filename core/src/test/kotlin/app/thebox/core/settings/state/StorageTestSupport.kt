package app.thebox.core.settings.state

/**
 * A fixed [StoragePageEnv] for JVM tests. [deferMain] (U-N9): `runOnMain` queues instead of running inline, as a post to
 * the main thread does; [runMain] runs the queue in order.
 */
class FakeStoragePageEnv(var keyboard: Boolean = true, var smallPool: Boolean = false) : StoragePageEnv {
    var deferMain = false
    private val mainQueue = ArrayDeque<() -> Unit>()
    override fun hasKeyboard() = keyboard
    override fun smallRemotePool() = smallPool
    val warms = java.util.concurrent.atomic.AtomicInteger()
    override fun warmSaveLocation() { warms.incrementAndGet() }
    override fun runOnMain(block: () -> Unit) { if (deferMain) mainQueue.addLast(block) else block() }
    fun runMain() { while (mainQueue.isNotEmpty()) mainQueue.removeFirst().invoke() }
}

