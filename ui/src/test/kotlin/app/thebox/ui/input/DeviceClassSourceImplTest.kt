package app.thebox.ui.input

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeviceClassSourceImplTest {
    @Test
    fun returnsSaneValuesOnceAndCaches() {
        val source = DeviceClassSourceImpl(ApplicationProvider.getApplicationContext<Context>())
        val dc = source.get()
        assertTrue("totalMem ${dc.totalMemBytes}", dc.totalMemBytes >= 0)
        assertTrue("memoryClass ${dc.memoryClassMb}", dc.memoryClassMb > 0)
        assertSame("computed once", dc, source.get())
    }
}
