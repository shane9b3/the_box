package app.thebox.data

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.test.runner.AndroidJUnitRunner
import app.thebox.core.common.BoxLog
import dagger.hilt.android.testing.HiltTestApplication

/** Instrumentation runner for :data (M0 L7): every test process runs on [HiltTestApplication]. */
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, HiltTestApplication::class.java.name, context)

    override fun onCreate(arguments: Bundle?) {
        // :app installs the logcat sink in production; device test failures need the same diagnostics.
        BoxLog.minLevel = BoxLog.DEBUG
        BoxLog.sink = BoxLog.Sink { level, tag, message, error ->
            Log.println(level, tag, if (error == null) message else message + "\n" + Log.getStackTraceString(error))
        }
        super.onCreate(arguments)
    }
}
