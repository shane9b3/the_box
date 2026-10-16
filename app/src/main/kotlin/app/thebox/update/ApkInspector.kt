package app.thebox.update

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import app.thebox.core.update.ArchiveFacts
import app.thebox.core.update.Sha256Counter
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * The platform half of the APK check (docs/design/m5-setup-settings.md §3.12.3, §9 F12): reads the downloaded archive
 * and the installed app with `GET_SIGNING_CERTIFICATES` (API 28+) or `GET_SIGNATURES` (API 25–27) and hands plain
 * [ArchiveFacts] to the pure `ApkVerifier`. A null signature list on API 25–27 is passed through as null (S7: the rule
 * skips the certificate check and the updater logs it).
 */
class ApkInspector @Inject constructor(@ApplicationContext private val context: Context) {

    fun archive(path: String): ArchiveFacts? {
        val info = archiveInfo(path) ?: return null
        return ArchiveFacts(info.packageName, versionCode(info), signers(info))
    }

    fun installedSigners(): List<String> = try {
        signers(installedInfo()).orEmpty()
    } catch (e: PackageManager.NameNotFoundException) {
        emptyList()
    }

    fun installedVersionCode(): Long = versionCode(context.packageManager.getPackageInfo(context.packageName, 0))

    @Suppress("DEPRECATION")
    private fun archiveInfo(path: String): PackageInfo? = try {
        val pm = context.packageManager
        when {
            Build.VERSION.SDK_INT >= 33 -> pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            Build.VERSION.SDK_INT >= 28 -> pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
            else -> pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)
        }
    } catch (e: RuntimeException) {
        null
    }

    @SuppressLint("PackageManagerGetSignatures")
    @Suppress("DEPRECATION")
    private fun installedInfo(): PackageInfo {
        val pm = context.packageManager
        return when {
            Build.VERSION.SDK_INT >= 33 -> pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            Build.VERSION.SDK_INT >= 28 -> pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            else -> pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): List<String>? {
        val sigs: Array<Signature>? = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.let { si -> if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory?.takeLast(1)?.toTypedArray() }
        } else {
            info.signatures
        }
        return sigs?.filterNotNull()?.map { Sha256Counter.of(it.toByteArray()) }
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
}
