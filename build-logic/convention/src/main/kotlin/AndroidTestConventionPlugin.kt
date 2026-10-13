import com.android.build.api.dsl.TestExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import thebox.buildlogic.configureKotlinAndroid
import thebox.buildlogic.intVersion
import thebox.buildlogic.libs

/** com.android.test modules (:benchmark, :baselineprofile) that instrument :app. */
class AndroidTestConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.test")
        extensions.configure<TestExtension> {
            configureKotlinAndroid(this)
            defaultConfig.targetSdk = libs.intVersion("targetSdk")
            defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            targetProjectPath = ":app"
        }
    }
}
