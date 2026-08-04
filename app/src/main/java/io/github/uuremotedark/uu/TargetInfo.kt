package io.github.uuremotedark.uu

object TargetInfo {
    const val PACKAGE_NAME = "com.netease.uuremote"
    const val SUPPORTED_VERSION_CODE = 435000L
    const val SUPPORTED_VERSION_NAME = "4.35.0"
    /**
     * The adapter is intentionally bound to the exact APK analysed for this
     * release.  A version number alone is not enough because regional builds
     * and repackaged APKs can keep the same version code while changing the
     * resource table or obfuscated method layout.
     */
    const val SUPPORTED_BASE_APK_SHA256 =
        "8705578563201bda7a679f03da18dabbd570d7c51f5f4686b3a1ed2fc4c87409"
    const val SUPPORTED_SIGNING_CERT_SHA256 =
        "bd6a3a04791d5ca2e08fb9d7bd82265893d25aa1cb0b6a205b5a2bb154874924"
    const val CONFIG_GROUP = "uu_remote_dark"
}
