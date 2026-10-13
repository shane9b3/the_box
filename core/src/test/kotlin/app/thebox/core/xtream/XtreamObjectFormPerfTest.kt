package app.thebox.core.xtream

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Review S5: a 20k-stream `get_live_streams` in the object form `{"0":{…},…}` must stream (one item alive) under
 * the `perfTest` 64 MB heap cap. The whole tree would be on the order of 100 MB of JsonElements.
 */
@Tag("perf")
class XtreamObjectFormPerfTest {
    @TempDir
    lateinit var dir: File

    private fun generate(objectForm: Boolean, n: Int): File {
        val f = File(dir, if (objectForm) "object.json" else "array.json")
        f.bufferedWriter().use { w ->
            w.write(if (objectForm) "{" else "[")
            for (i in 0 until n) {
                if (i > 0) w.write(",")
                if (objectForm) w.write("\"$i\":")
                w.write(
                    """{"num":"${i + 1}","name":"Channel $i HD | Some long provider prefix","stream_type":"live","stream_id":"${1000 + i}",""" +
                        """"stream_icon":"http://logos.example.com/very/long/path/to/logo/$i.png","epg_channel_id":"channel$i.example",""" +
                        """"added":"1700000000","is_adult":"0","category_id":"${i % 40}","category_ids":[${i % 40},${(i + 1) % 40}],""" +
                        """"custom_sid":"","tv_archive":${i % 2},"direct_source":"","tv_archive_duration":"${i % 8}","thumbnail":""}""",
                )
            }
            w.write(if (objectForm) "}" else "]")
        }
        return f
    }

    @Test
    fun objectForm20k() {
        val f = generate(objectForm = true, n = 20_000)
        val cats = (0 until 40).map { XtreamCategory(it.toString(), "Group $it", null) }
        val count = f.inputStream().use { XtreamCatalogDecoderImpl().liveChannels(it, cats).count() }
        assertEquals(20_000, count)
    }

    @Test
    fun arrayForm20k() {
        val f = generate(objectForm = false, n = 20_000)
        val count = f.inputStream().use { XtreamCatalogDecoderImpl().liveChannels(it, emptyList()).count() }
        assertEquals(20_000, count)
    }
}
