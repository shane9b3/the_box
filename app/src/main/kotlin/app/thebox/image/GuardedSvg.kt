package app.thebox.image

import coil3.ImageLoader
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.svg.SvgDecoder
import coil3.svg.isSvg
import okio.Buffer
import okio.BufferedSource
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8

/**
 * SVG decoding for every image (provider channel logos and the score bug's team logos), behind a check (security
 * review 2026-11-09, "worth a look" 1; owner 2026-11-09: allow provider SVG logos unless there is a reason to block).
 * The reason to block was AndroidSVG's XML parser expanding internal entities (billion laughs on the Expat of
 * Android 7–11 / Fire OS). So an SVG is decoded only when it is at most [MAX_SVG_BYTES], declares no entity, and has
 * no zero byte (a UTF-16 file would hide the declaration from the byte check). Any other SVG fails to decode and the
 * logo's place stays empty, like any broken image. The ServiceLoader is off in [ImageModule], so this is the only
 * route to the SVG decoder.
 */
internal class GuardedSvg(private val svg: Decoder.Factory = SvgDecoder.Factory()) : Decoder.Factory {
    override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
        val source = result.source.source()
        if (result.mimeType != SVG_MIME && !DecodeUtils.isSvg(source)) return null
        if (!isSafe(source)) return Decoder { throw IllegalStateException("SVG refused: too large or declares entities") }
        return svg.create(result, options, imageLoader)
    }

    companion object {
        const val MAX_SVG_BYTES = 512L * 1024
        private const val SVG_MIME = "image/svg+xml"
        private val ENTITY: ByteString = "<!ENTITY".encodeUtf8()
        private const val CHUNK = 8_192L

        /** Reads ahead without consuming [source]. */
        fun isSafe(source: BufferedSource): Boolean {
            val peek = source.peek()
            val head = Buffer()
            while (head.size <= MAX_SVG_BYTES && peek.read(head, CHUNK) != -1L) Unit
            if (head.size > MAX_SVG_BYTES) return false
            val bytes = head.readByteString()
            return bytes.indexOf(ENTITY) < 0 && bytes.indexOf(ByteString.of(0)) < 0
        }
    }
}
