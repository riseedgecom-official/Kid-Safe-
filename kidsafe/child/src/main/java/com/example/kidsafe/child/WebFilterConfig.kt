package com.example.kidsafe.child

import android.content.Context
import android.content.Intent
import android.net.VpnService

/** What the parent asked for (on/off + extra blocked sites), cached on the phone. */
object WebFilterConfig {

    private const val PREFS = "webfilter_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_DOMAINS = "domains"

    @Volatile private var domainsCache: Set<String>? = null

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_ENABLED, false)

    fun save(c: Context, enabled: Boolean, domains: List<String>) {
        val clean = domains.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        prefs(c).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_DOMAINS, clean.joinToString("\n"))
            .apply()
        domainsCache = clean.toSet()
    }

    private fun domains(c: Context): Set<String> =
        domainsCache ?: (prefs(c).getString(KEY_DOMAINS, "") ?: "")
            .split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            .also { domainsCache = it }

    /** True if [host] is one of the parent's extra blocked sites (or a subdomain of one). */
    fun matchesCustom(c: Context, host: String): Boolean =
        domains(c).any { host == it || host.endsWith(".$it") }

    /** Parent wants the filter, but the child has not yet allowed the Android VPN prompt. */
    fun needsConsent(c: Context): Boolean = isEnabled(c) && VpnService.prepare(c) != null

    /** Starts or stops the filter to match what the parent asked for. */
    fun apply(c: Context) {
        if (isEnabled(c)) {
            if (VpnService.prepare(c) == null) {
                c.startService(Intent(c, WebFilterService::class.java))
            }
        } else {
            c.stopService(Intent(c, WebFilterService::class.java))
        }
    }
}
