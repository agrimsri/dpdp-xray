package com.dpdpxray.app.control

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.VpnService
import android.provider.Settings
import com.dpdpxray.app.consent.ConsentAccessibilityService

data class Preflight(
    val vpnReady: Boolean,
    val accessibilityOn: Boolean,
    val shizukuRunning: Boolean,
    val shizukuGranted: Boolean,
    val privateDnsActive: Boolean,
    val modelFound: Boolean,
    val modelLoaded: Boolean,
) {
    /** Minimum for a real (non-sample) audit. */
    val canAudit: Boolean get() = vpnReady && accessibilityOn
}

data class AuditableApp(val packageName: String, val label: String, val icon: Drawable?, val isSystem: Boolean)

object DeviceChecks {
    fun vpnReady(context: Context): Boolean = VpnService.prepare(context) == null

    fun accessibilityOn(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val me = ComponentName(context, ConsentAccessibilityService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) } || ConsentAccessibilityService.instance != null
    }

    /** True when the device uses DNS-over-TLS (Private DNS), which can hide lookups from the audit. */
    fun privateDnsActive(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return runCatching { cm.getLinkProperties(cm.activeNetwork)?.isPrivateDnsActive == true }.getOrDefault(false)
    }

    fun openAccessibilitySettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openPrivateDnsSettings(context: Context) {
        val intent = Intent("android.settings.WIRELESS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun openAppInfo(context: Context, pkg: String) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$pkg"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun launchableApps(context: Context): List<AuditableApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
            .map { ai ->
                AuditableApp(
                    packageName = ai.packageName,
                    label = pm.getApplicationLabel(ai).toString(),
                    icon = runCatching { pm.getApplicationIcon(ai) }.getOrNull(),
                    isSystem = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                )
            }
            .sortedWith(compareBy({ it.isSystem }, { it.label.lowercase() }))
    }

    fun launch(context: Context, pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    fun bringXrayToFront(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching { context.startActivity(intent) }
    }
}
