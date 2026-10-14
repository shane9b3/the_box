package app.thebox.core.guide.fakes

import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideSearch
import app.thebox.core.guide.ProgrammeHit
import app.thebox.core.guide.SearchResults
import app.thebox.core.input.BoxAction
import app.thebox.core.input.KeyBinding
import app.thebox.core.input.KeyCapabilities
import app.thebox.core.input.KeyContext
import app.thebox.core.input.KeyGesture
import app.thebox.core.input.KeyGestureDetector
import app.thebox.core.input.KeyInput
import app.thebox.core.input.KeyMap
import app.thebox.core.input.Keys
import app.thebox.core.text.ChannelNamePrefix
import app.thebox.core.text.NamePrefixRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Case-insensitive substring search over [channels] and [programmes]; under 2 letters/digits returns nothing (S9). */
class FakeGuideSearch(
    var channels: List<GuideChannel> = emptyList(),
    var programmes: List<ProgrammeHit> = emptyList(),
) : GuideSearch {
    val queries = ArrayList<String>()
    override suspend fun search(query: String, nowMs: Long, unlocked: Boolean): SearchResults {
        queries += query
        if (query.count { it.isLetterOrDigit() } < 2) return SearchResults(query, emptyList(), emptyList())
        val q = query.trim().lowercase()
        return SearchResults(
            query,
            channels.filter { q in it.displayName.lowercase() },
            programmes.filter { q in it.info.title.lowercase() },
        )
    }
}

/** Strips literal prefixes only (Custom literals, or [autoPrefixes] for Auto). Not the §3.12.1 rule. */
class FakeChannelNamePrefix(private val autoPrefixes: List<String> = listOf("UK: ", "[DE] ", "FR | ")) : ChannelNamePrefix {
    override fun strip(name: String, groupName: String?, rule: NamePrefixRule): String? {
        val prefixes = when (rule) {
            NamePrefixRule.Off -> return null
            NamePrefixRule.Auto -> autoPrefixes
            is NamePrefixRule.Custom -> rule.literals
        }
        val p = prefixes.sortedByDescending { it.length }.firstOrNull { name.startsWith(it, ignoreCase = true) } ?: return null
        return name.substring(p.length).takeIf { it.isNotBlank() }
    }
}

/** Every DOWN is a [KeyGesture.Press] (with its repeat count); no long presses unless [longOnTimer]. */
class FakeKeyGestureDetector(var longOnTimer: Boolean = false) : KeyGestureDetector {
    val inputs = ArrayList<KeyInput>()
    var resets = 0
    override fun onKey(input: KeyInput, hasLong: Boolean, out: MutableList<KeyGesture>) {
        inputs += input
        if (input.isDown) out += KeyGesture.Press(input.keyCode, input.repeatCount)
        else if (input.repeatCount > 0) out += KeyGesture.ReleaseAfterRepeat(input.keyCode)
    }
    override fun onTimer(keyCode: Int, nowMs: Long, out: MutableList<KeyGesture>) { if (longOnTimer) out += KeyGesture.LongPress(keyCode) }
    override fun reset() { resets++ }
}

/** Map-backed [KeyMap]; [default] binds the D-pad, OK, Back, Menu, CH±, digits (same in both contexts). */
class FakeKeyMap(
    private val shortMap: Map<Int, BoxAction> = DEFAULT_SHORT,
    private val longMap: Map<Int, BoxAction> = mapOf(Keys.DPAD_CENTER to BoxAction.CONTEXT_MENU),
) : KeyMap {
    override fun short(context: KeyContext, keyCode: Int): BoxAction? = shortMap[keyCode] ?: if (digit(keyCode) >= 0) BoxAction.DIGIT else null
    override fun long(context: KeyContext, keyCode: Int): BoxAction? = longMap[keyCode]
    override fun digit(keyCode: Int): Int = when (keyCode) {
        in Keys.DIGIT_0..Keys.DIGIT_0 + 9 -> keyCode - Keys.DIGIT_0
        in Keys.NUMPAD_0..Keys.NUMPAD_0 + 9 -> keyCode - Keys.NUMPAD_0
        else -> -1
    }
    override fun bindings(context: KeyContext): List<KeyBinding> =
        (shortMap.keys + longMap.keys).distinct().sorted().map { KeyBinding(it, shortMap[it], longMap[it]) }

    companion object {
        val DEFAULT_SHORT = mapOf(
            Keys.DPAD_UP to BoxAction.UP, Keys.DPAD_DOWN to BoxAction.DOWN, Keys.DPAD_LEFT to BoxAction.LEFT,
            Keys.DPAD_RIGHT to BoxAction.RIGHT, Keys.DPAD_CENTER to BoxAction.SELECT, Keys.ENTER to BoxAction.SELECT,
            Keys.BACK to BoxAction.BACK, Keys.MENU to BoxAction.CONTEXT_MENU, Keys.CHANNEL_UP to BoxAction.ZAP_UP,
            Keys.CHANNEL_DOWN to BoxAction.ZAP_DOWN, Keys.LAST_CHANNEL to BoxAction.PREVIOUS_CHANNEL,
        )
    }
}

class FakeKeyCapabilities : KeyCapabilities {
    private val state = MutableStateFlow<Set<Int>>(emptySet())
    var writes = 0
    override val seen: StateFlow<Set<Int>> = state.asStateFlow()
    override fun record(keyCode: Int) {
        if (keyCode in state.value) return
        writes++
        state.update { it + keyCode }
    }
}
