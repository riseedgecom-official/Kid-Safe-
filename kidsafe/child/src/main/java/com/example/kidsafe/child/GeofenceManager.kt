package com.example.kidsafe.child

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import org.json.JSONObject

data class PlaceDef(val id: String, val name: String, val lat: Double, val lng: Double, val radius: Int)

/** Registers the parent's safe places with Google's geofencing service. */
object GeofenceManager {

    private const val PREFS = "geofence_prefs"
    private const val KEY_NAMES = "names"   // JSON {placeId: name}
    private const val KEY_STATE = "state"   // JSON {placeId: "in" | "out"}

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun json(ctx: Context, key: String) = JSONObject(prefs(ctx).getString(key, "{}") ?: "{}")

    fun pendingIntent(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx,
            0,
            Intent(ctx, GeofenceReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

    @SuppressLint("MissingPermission")
    fun register(ctx: Context, places: List<PlaceDef>) {
        if (!Permissions.hasForegroundLocation(ctx) || !Permissions.hasBackgroundLocation(ctx)) return

        // Remember names (the geofence event only carries the id) and forget deleted places.
        val names = JSONObject()
        places.forEach { names.put(it.id, it.name) }
        val state = json(ctx, KEY_STATE)
        state.keys().asSequence().toList().forEach { if (!names.has(it)) state.remove(it) }
        prefs(ctx).edit()
            .putString(KEY_NAMES, names.toString())
            .putString(KEY_STATE, state.toString())
            .apply()

        val client = LocationServices.getGeofencingClient(ctx)
        val pi = pendingIntent(ctx)
        client.removeGeofences(pi).addOnCompleteListener {
            if (places.isEmpty()) return@addOnCompleteListener
            val fences = places.take(90).map { p ->
                Geofence.Builder()
                    .setRequestId(p.id)
                    .setCircularRegion(p.lat, p.lng, p.radius.toFloat())
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                    .build()
            }
            val request = GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .addGeofences(fences)
                .build()
            client.addGeofences(request, pi)
        }
    }

    fun nameOf(ctx: Context, id: String): String = json(ctx, KEY_NAMES).optString(id, "a saved place")

    /** Returns true only when the inside/outside state really changed (stops duplicate alerts). */
    fun shouldReport(ctx: Context, id: String, inside: Boolean): Boolean {
        val state = json(ctx, KEY_STATE)
        val now = if (inside) "in" else "out"
        if (state.optString(id, "") == now) return false
        state.put(id, now)
        prefs(ctx).edit().putString(KEY_STATE, state.toString()).apply()
        return true
    }
}
