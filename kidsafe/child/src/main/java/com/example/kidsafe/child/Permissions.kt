package com.example.kidsafe.child

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat

object Permissions {
    fun hasForegroundLocation(c: Context) = granted(c, Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasBackgroundLocation(c: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            granted(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun hasNotifications(c: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            granted(c, Manifest.permission.POST_NOTIFICATIONS)

    /** "Usage access" is a special setting the child switches on in system settings. */
    @Suppress("DEPRECATION")
    fun hasUsageAccess(c: Context): Boolean {
        val appOps = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        } else {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Everything location sharing needs (used by the setup flow, the service and after reboot). */
    fun allGranted(c: Context) =
        hasForegroundLocation(c) && hasBackgroundLocation(c) && hasNotifications(c)

    private fun granted(c: Context, p: String) =
        ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED
}
