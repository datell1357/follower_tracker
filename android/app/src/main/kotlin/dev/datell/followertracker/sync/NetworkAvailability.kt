package dev.datell.followertracker.sync

import android.content.Context
import android.net.ConnectivityManager

object NetworkAvailability {
    /** Unknown connectivity must not suppress a usable session or captive-portal connection. */
    fun isDefinitelyOffline(context: Context): Boolean = runCatching {
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
            ?.let { it.activeNetwork == null } ?: false
    }.getOrDefault(false)
}
