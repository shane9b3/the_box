package app.thebox.bench

import android.app.Activity
import android.os.Bundle

/**
 * Benchmark-build seeding (docs/design/m3-guide.md §1.5, §5.7; owner WP-D): the benchmark build type does not include
 * `src/debug`. Commit-0 stub: finishes immediately.
 */
class BenchSeedActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TODO("WP-D"): seed the fake-panel playlist and open the guide.
        finish()
    }
}
