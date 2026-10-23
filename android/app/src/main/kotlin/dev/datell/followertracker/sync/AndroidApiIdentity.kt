package dev.datell.followertracker.sync

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/** Supports Google's Android application restriction. No private signing key is read. */
@Suppress("DEPRECATION")
fun androidApiIdentity(context: Context): Pair<String, String> {
    val signature = if (Build.VERSION.SDK_INT >= 28) context.packageManager
        .getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.firstOrNull()
    else context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
    checkNotNull(signature)
    val fingerprint = MessageDigest.getInstance("SHA-1").digest(signature.toByteArray()).joinToString("") { "%02X".format(it) }
    return context.packageName to fingerprint
}
