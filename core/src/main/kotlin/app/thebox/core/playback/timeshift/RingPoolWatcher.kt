// C-B1 (docs/review/2026-10-24-storage-locations-review-core.md): the RAM pool is the stall budget of a remote ring.
package app.thebox.core.playback.timeshift

/**
 * (C-B1) A [RingStorage] that wants to see the ring's RAM pool: `FallbackRingStorage` switches to this device before the
 * pool runs out, whatever the stream rate. [TsRingImpl] attaches itself at construction. [unflushedBytes] is read on the
 * flusher, without the ring's lock (two volatiles, approximate by design); it must not block.
 */
interface RingPoolWatcher {
    fun attachPool(poolBytes: Long, unflushedBytes: () -> Long)
}
