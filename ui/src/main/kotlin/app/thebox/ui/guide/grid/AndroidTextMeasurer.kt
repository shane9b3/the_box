package app.thebox.ui.guide.grid

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import app.thebox.core.guide.GuideTextMeasurer
import app.thebox.core.guide.GuideTextStyle
import app.thebox.core.guide.render.RescalableTextMeasurer
import app.thebox.ui.R

/**
 * `GuideTextMeasurer` over `android.graphics.Paint` with the bundled typefaces (docs/design/m3-guide.md §0.7,
 * §4.3). One instance per thread (Paint is not thread-safe): the renderer's on main, the pager's on
 * `box-guide-epg`. [rescale] re-sizes the paints when the window scale changes.
 */
class AndroidTextMeasurer(context: Context, scale: Float) : RescalableTextMeasurer {
    private val paints: Array<Paint> = GuidePaints.textPaints(context, scale)
    private val metrics = Paint.FontMetrics()
    private val ascents = FloatArray(paints.size)
    private val descents = FloatArray(paints.size)

    override var scale: Float = scale
        private set

    init { refreshMetrics() }

    override fun rescale(scale: Float) {
        if (scale == this.scale) return
        this.scale = scale
        GuidePaints.applyScale(paints, scale)
        refreshMetrics()
    }

    override fun width(text: String, start: Int, end: Int, style: GuideTextStyle): Float =
        if (end <= start) 0f else paints[style.ordinal].measureText(text, start, end)

    override fun fit(text: String, start: Int, end: Int, style: GuideTextStyle, maxWidth: Float): Int =
        if (end <= start || maxWidth <= 0f) 0 else paints[style.ordinal].breakText(text, start, end, true, maxWidth, null)

    override fun ascent(style: GuideTextStyle): Float = ascents[style.ordinal]
    override fun descent(style: GuideTextStyle): Float = descents[style.ordinal]

    private fun refreshMetrics() {
        for (i in paints.indices) {
            paints[i].getFontMetrics(metrics)
            ascents[i] = -metrics.ascent
            descents[i] = metrics.descent
        }
    }
}

/** Paint factory shared by [AndroidTextMeasurer] and [AndroidGuideCanvas] so both measure and draw identically. */
internal object GuidePaints {
    private val STYLES = GuideTextStyle.entries.toTypedArray()

    fun textPaints(context: Context, scale: Float): Array<Paint> {
        val faces = Typefaces.get(context)
        return Array(STYLES.size) { i ->
            val st = STYLES[i]
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                typeface = faces.of(st)
                textSize = st.sizePx * scale
                letterSpacing = st.letterSpacingEm
                fontFeatureSettings = "tnum"
            }
        }
    }

    fun applyScale(paints: Array<Paint>, scale: Float) {
        for (i in paints.indices) paints[i].textSize = STYLES[i].sizePx * scale
    }

    /** The bundled faces (`ui/src/main/res/font`, `BoxFonts`), loaded once per process. */
    class Typefaces(
        private val sans400: Typeface, private val sans500: Typeface, private val sans600: Typeface, private val sans700: Typeface,
        private val mono400: Typeface, private val mono500: Typeface,
    ) {
        fun of(st: GuideTextStyle): Typeface = if (st.mono) {
            if (st.weight >= 500) mono500 else mono400
        } else when {
            st.weight >= 700 -> sans700
            st.weight >= 600 -> sans600
            st.weight >= 500 -> sans500
            else -> sans400
        }

        companion object {
            @Volatile private var cached: Typefaces? = null

            fun get(context: Context): Typefaces = cached ?: synchronized(this) {
                cached ?: load(context.applicationContext ?: context).also { cached = it }
            }

            private fun load(c: Context): Typefaces {
                fun font(id: Int, fallback: Typeface): Typeface = runCatching { ResourcesCompat.getFont(c, id) }.getOrNull() ?: fallback
                val sans = Typeface.SANS_SERIF
                val mono = Typeface.MONOSPACE
                return Typefaces(
                    font(R.font.instrument_sans_regular, sans), font(R.font.instrument_sans_medium, sans),
                    font(R.font.instrument_sans_semibold, Typeface.create(sans, Typeface.BOLD)), font(R.font.instrument_sans_bold, Typeface.create(sans, Typeface.BOLD)),
                    font(R.font.jetbrains_mono_regular, mono), font(R.font.jetbrains_mono_medium, mono),
                )
            }
        }
    }
}
