package com.fakerun.app.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest
import java.util.Locale

object SigningIdentity {
    @Suppress("DEPRECATION")
    fun sha1Fingerprints(context: Context): List<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo!!.apkContentsSigners
        } else {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures!!
        }
        // 使用当前 APK 的签名，不能把旧签名历史当成百度 AK 应绑定的证书。
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-1").digest(signature.toByteArray())
                .joinToString(":") { String.format(Locale.US, "%02X", it.toInt() and 0xff) }
        }
    }
}
