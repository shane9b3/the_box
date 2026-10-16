package app.thebox.core.settings.tree

import app.thebox.core.settings.EffectTiming.LIVE
import app.thebox.core.settings.RowId
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.Visibility.ADVANCED

/**
 * WP-A: the Network section (docs/design/m5-setup-settings.md §3.3.11, §3.13), advanced only. `network.timeouts` is
 * two rows (connect, read); `network.custom_doh` (the https:// editor) shows while DNS is Custom.
 */
object NetworkRows : RowSource {
    val DNS = RowId("network.dns")
    val CUSTOM_DOH = RowId("network.custom_doh")
    val IPV4 = RowId("network.ipv4")
    val CONNECT_TIMEOUT = RowId("network.connect_timeout")
    val READ_TIMEOUT = RowId("network.read_timeout")

    override val rows: List<RowSpec> = listOf(DNS, CUSTOM_DOH, IPV4, CONNECT_TIMEOUT, READ_TIMEOUT).map {
        RowSpec(it, SectionId.NETWORK, ADVANCED, LIVE, "A")
    }
}
