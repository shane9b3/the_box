package app.thebox.image

import app.thebox.ui.sports.LogoTrim
import coil3.decode.Decoder
import coil3.intercept.Interceptor
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.transformations
import coil3.svg.SvgDecoder

/**
 * (Security review 2026-11-09, "worth a look" 1) SVG decoding only for the score bug's team logos. The shared loader
 * has no SVG decoder, so a provider's `tvg-logo` served as `image/svg+xml` (or sniffed as SVG) never reaches
 * AndroidSVG, whose SAX path expands internal XML entities (billion laughs on pre-2.4 Expat, Android 7–11 / Fire OS).
 * The team logos are the only SVG the app needs (NHL `…_dark.svg`, docs/design/m6-score-bug.md §3, A-N8; ESPN's are
 * PNG). They are recognised by the [LogoTrim] transformation that only `coilLogoFetch` adds, and their URLs are
 * already confined to the league's logo base by the score sources (A-N2). The request gets the SVG decoder first in
 * its own registry; every other decoder still applies to it.
 */
internal class SportsLogoSvg(private val svg: Decoder.Factory = SvgDecoder.Factory()) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        if (!allowsSvg(request)) return chain.proceed()
        return chain.withRequest(request.newBuilder().decoderFactory(svg).build()).proceed()
    }

    companion object {
        /** True for a team-logo request that did not pick its own decoder. */
        fun allowsSvg(request: ImageRequest): Boolean = request.decoderFactory == null && request.transformations.any { it is LogoTrim }
    }
}
