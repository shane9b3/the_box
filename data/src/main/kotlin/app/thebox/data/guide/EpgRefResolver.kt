package app.thebox.data.guide

import app.thebox.core.epg.EpgChannelRef
import app.thebox.core.refresh.ChannelIdentity

/**
 * WP-B stub (docs/design/m3-guide.md §1.3, §3.2.2): the M1 §2.2 resolution rule for a set of identities, shared with
 * `EpgRepository.resolvedRefs` (which WP-B refactors to call it).
 */
internal class EpgRefResolver {
    suspend fun resolve(identities: List<ChannelIdentity>): Map<ChannelIdentity, EpgChannelRef?> = TODO("WP-B")
}
