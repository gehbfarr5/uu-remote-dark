package io.github.uuremotedark.uu

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.FileInputStream
import java.security.MessageDigest

data class CompatibilityResult(
    val supported: Boolean,
    val versionCode: Long,
    val versionName: String,
    val baseApkSha256: String,
    val signingCertSha256: String,
    val reason: String,
)

object CompatibilityGate {
    fun inspect(context: Context): CompatibilityResult {
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val packageInfo = context.packageManager.getPackageInfo(TargetInfo.PACKAGE_NAME, flags)
        val versionCode = if (Build.VERSION.SDK_INT >= 28) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        val versionName = packageInfo.versionName.orEmpty()
        val baseApkSha256 = sha256File(packageInfo.applicationInfo?.sourceDir)
        val signingCertSha256 = signingCertificateSha256(packageInfo)
        val versionMatches = versionCode == TargetInfo.SUPPORTED_VERSION_CODE
        val apkMatches = baseApkSha256 == TargetInfo.SUPPORTED_BASE_APK_SHA256
        val certificateMatches = signingCertSha256 == TargetInfo.SUPPORTED_SIGNING_CERT_SHA256
        val supported = versionMatches && apkMatches && certificateMatches
        return CompatibilityResult(
            supported = supported,
            versionCode = versionCode,
            versionName = versionName,
            baseApkSha256 = baseApkSha256,
            signingCertSha256 = signingCertSha256,
            reason = if (supported) {
                "known UU adapter ${TargetInfo.SUPPORTED_VERSION_NAME} " +
                    "apk=$baseApkSha256 cert=$signingCertSha256"
            } else {
                buildString {
                    append("adapter mismatch versionCode=")
                    append(versionCode)
                    append(" versionName=")
                    append(versionName)
                    append(" apk=")
                    append(baseApkSha256)
                    append(" cert=")
                    append(signingCertSha256)
                    append(" expectedVersion=")
                    append(TargetInfo.SUPPORTED_VERSION_CODE)
                    append(" expectedApk=")
                    append(TargetInfo.SUPPORTED_BASE_APK_SHA256)
                    append(" expectedCert=")
                    append(TargetInfo.SUPPORTED_SIGNING_CERT_SHA256)
                }
            },
        )
    }

    private fun sha256File(path: String?): String {
        if (path.isNullOrBlank()) return "missing"
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(path).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().toHex()
        }.getOrElse { "unreadable" }
    }

    private fun signingCertificateSha256(
        packageInfo: android.content.pm.PackageInfo,
    ): String {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            packageInfo.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures
        }
        val signature = signatures?.firstOrNull() ?: return "missing"
        return runCatching {
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .toHex()
        }.getOrElse { "unreadable" }
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
}
