package app.thebox.core.refresh

import app.thebox.core.text.NameNormalizer

/**
 * Mass-remap proposal (docs/design/m1-data-layer.md §3.3.6): index `added` by `forKey(name)`. For each
 * missing candidate take the added ones with the same normalised name; when there are several, keep those
 * whose `forKey(groupName)` is equal. Accept only when exactly one remains and it is not claimed yet (1:1).
 */
class RemapPlannerImpl : RemapPlanner {
    override fun plan(missing: List<RemapCandidate>, added: List<RemapCandidate>): List<KeyRemap> {
        val byName = HashMap<String, MutableList<RemapCandidate>>(added.size * 2)
        for (a in added) byName.getOrPut(NameNormalizer.forKey(a.name)) { ArrayList(1) } += a
        val claimed = HashSet<String>()
        val out = ArrayList<KeyRemap>()
        for (m in missing) {
            val candidates = byName[NameNormalizer.forKey(m.name)] ?: continue
            val mGroup = groupNorm(m.groupName)
            val pick: RemapCandidate = if (candidates.size == 1) {
                candidates[0]
            } else {
                candidates.singleOrNull { groupNorm(it.groupName) == mGroup } ?: continue
            }
            if (pick.stableKey == m.stableKey || !claimed.add(pick.stableKey)) continue
            out += KeyRemap(m.stableKey, pick.stableKey, sameGroup = groupNorm(pick.groupName) == mGroup)
        }
        return out
    }

    private fun groupNorm(g: String?): String = if (g.isNullOrBlank()) "" else NameNormalizer.forKey(g)
}
