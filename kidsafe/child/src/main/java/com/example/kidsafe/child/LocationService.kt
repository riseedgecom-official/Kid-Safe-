package com.example.kidsafe.child

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.BatteryManager
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.kidsafe.core.BatteryAlert
import com.example.kidsafe.core.Col
import com.example.kidsafe.core.EventType
import com.example.kidsafe.core.Fix
import com.example.kidsafe.core.LocateRequest
import com.example.kidsafe.core.SettingsDoc
import com.example.kidsafe.core.TrackingMode
import com.example.kidsafe.core.TrackingPolicy
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query

/**
 * Foreground service that shares location with the parent and listens for what the parent sets.
 * A persistent notification is always visible, so the child always knows it is on.
 *
 * How location is shared ([TrackingPolicy] decides, reading by reading):
 *  - still:  one low-power request (moves of 50 m+ wake it) plus a 10-minute heartbeat, so the
 *            parent still sees "last seen" fresh and the history still shows that the child stayed.
 *  - moving: a precise request every ~30-60 s for a steady trail, back to low power once still.
 * Battery % is sent with every update, so if the phone dies the parent still sees the last
 * place and battery level.
 *
 * Also here (all visible to the child, nothing is hidden):
 *  - "Locate now" from the parent: the phone shows a notice and shares a fresh location.
 *  - low-battery alert to the parent (once per discharge).
 *  - a notification when the parent sends a chat message.
 */
class LocationService : Service() {

    private lateinit var client: FusedLocationProviderClient
    private val policy = TrackingPolicy()
    private var mode = TrackingMode.STATIONARY

    private var placesRegistration: ListenerRegistration? = null
    private var lastPlacesSignature: String? = null
    private var webFilterRegistration: ListenerRegistration? = null
    private var screenTimeRegistration: ListenerRegistration? = null
    private var locateRegistration: ListenerRegistration? = null
    private var messagesRegistration: ListenerRegistration? = null

    private val trailCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.lastOrNull()?.let { onLocation(it, forced = false) }
        }
    }

    private val heartbeatCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.lastOrNull()?.let { onLocation(it, forced = false) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        client = LocationServices.getFusedLocationProviderClient(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Location sharing", NotificationManager.IMPORTANCE_LOW),
        )
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } catch (e: Exception) {
            // Location permission missing/revoked: Android 14+ refuses a location-type service.
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Permissions.hasForegroundLocation(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        requestTrail()
        requestHeartbeat()
        listenForPlaces()
        listenForWebFilter()
        listenForScreenTimeGoal()
        listenForLocateRequests()
        listenForParentMessages()
        return START_STICKY
    }

    override fun onDestroy() {
        placesRegistration?.remove()
        webFilterRegistration?.remove()
        screenTimeRegistration?.remove()
        locateRegistration?.remove()
        messagesRegistration?.remove()
        client.removeLocationUpdates(trailCallback)
        client.removeLocationUpdates(heartbeatCallback)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------------------------------
    // Location requests
    // ---------------------------------------------------------------------------------------

    /** The movement-sensitive request. Its settings depend on [mode]. */
    @SuppressLint("MissingPermission")
    private fun requestTrail() {
        if (!Permissions.hasForegroundLocation(this)) return
        val request = when (mode) {
            TrackingMode.STATIONARY ->
                LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, STILL_INTERVAL_MS)
                    .setMinUpdateIntervalMillis(STILL_MIN_INTERVAL_MS)
                    .setMinUpdateDistanceMeters(STILL_MIN_DISTANCE_M)
                    .build()
            TrackingMode.MOVING ->
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, MOVING_INTERVAL_MS)
                    .setMinUpdateIntervalMillis(MOVING_MIN_INTERVAL_MS)
                    .setMinUpdateDistanceMeters(MOVING_MIN_DISTANCE_M)
                    .build()
        }
        client.removeLocationUpdates(trailCallback)
        client.requestLocationUpdates(request, trailCallback, Looper.getMainLooper())
    }

    /** A slow request with no distance filter: proves the phone is alive even when nobody moves. */
    @SuppressLint("MissingPermission")
    private fun requestHeartbeat() {
        if (!Permissions.hasForegroundLocation(this)) return
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, HEARTBEAT_INTERVAL_MS)
            .setMinUpdateIntervalMillis(HEARTBEAT_MIN_INTERVAL_MS)
            .build()
        client.removeLocationUpdates(heartbeatCallback)
        client.requestLocationUpdates(request, heartbeatCallback, Looper.getMainLooper())
    }

    private fun onLocation(loc: Location, forced: Boolean) {
        val fix = Fix(loc.latitude, loc.longitude, loc.accuracy, System.currentTimeMillis())
        val decision = if (forced) policy.onForcedFix(fix) else policy.onFix(fix)
        if (!decision.accept) return
        if (decision.modeChanged) {
            mode = decision.mode
            requestTrail()
        }
        publish(loc, decision.saveHistory, decision.mode)
        checkBattery()
    }

    private fun publish(loc: Location, saveHistory: Boolean, mode: TrackingMode) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val data = hashMapOf<String, Any>(
            "lat" to loc.latitude,
            "lng" to loc.longitude,
            "accuracy" to loc.accuracy,
            "battery" to batteryPercent(),
            "mode" to (if (mode == TrackingMode.MOVING) "moving" else "still"),
            "ts" to FieldValue.serverTimestamp(),
        )
        if (loc.hasSpeed()) data["speed"] = loc.speed
        val ref = FirebaseFirestore.getInstance().collection(Col.CHILDREN).document(uid)
        if (saveHistory) ref.collection(Col.LOCATIONS).add(data) // history
        // "last known" for the parent + "last seen" (a heartbeat the parent uses to spot a silent phone)
        ref.update(mapOf("lastLocation" to data, "lastSeen" to FieldValue.serverTimestamp()))
    }

    private fun touchLastSeen() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseFirestore.getInstance().collection(Col.CHILDREN).document(uid)
            .update("lastSeen", FieldValue.serverTimestamp())
    }

    // ---------------------------------------------------------------------------------------
    // Battery
    // ---------------------------------------------------------------------------------------

    private fun batteryPercent(): Int =
        (getSystemService(BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    /** Tells the parent once when the battery gets low (and not while charging). */
    private fun checkBattery() {
        val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val level = batteryPercent()

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val result = BatteryAlert.evaluate(level, charging, prefs.getBoolean(KEY_BATTERY_ARMED, true))
        if (result.armed != prefs.getBoolean(KEY_BATTERY_ARMED, true)) {
            prefs.edit().putBoolean(KEY_BATTERY_ARMED, result.armed).apply()
        }
        if (result.alert) ChildEvents.post(EventType.BATTERY_LOW, mapOf("battery" to level))
    }

    // ---------------------------------------------------------------------------------------
    // Things the parent sets or asks for
    // ---------------------------------------------------------------------------------------

    private fun childDoc() =
        FirebaseAuth.getInstance().currentUser?.uid?.let {
            FirebaseFirestore.getInstance().collection(Col.CHILDREN).document(it)
        }

    /** The parent switches the website filter on/off and edits the extra blocked sites. */
    private fun listenForWebFilter() {
        webFilterRegistration?.remove()
        val doc = childDoc() ?: return
        webFilterRegistration = doc.collection(Col.SETTINGS).document(SettingsDoc.WEB_FILTER)
            .addSnapshotListener { snap, e ->
                if (e != null || snap == null) return@addSnapshotListener
                // "Missing" from the local cache just means "not loaded yet".
                if (snap.metadata.isFromCache && !snap.exists()) return@addSnapshotListener
                val enabled = snap.getBoolean("enabled") == true
                val domains = (snap.get("domains") as? List<*>)?.filterIsInstance<String>().orEmpty()
                WebFilterConfig.save(applicationContext, enabled, domains)
                WebFilterConfig.apply(applicationContext)
            }
    }

    /** The parent's daily screen-time goal (minutes, 0 = none). */
    private fun listenForScreenTimeGoal() {
        screenTimeRegistration?.remove()
        val doc = childDoc() ?: return
        screenTimeRegistration = doc.collection(Col.SETTINGS).document(SettingsDoc.SCREEN_TIME)
            .addSnapshotListener { snap, e ->
                if (e != null || snap == null) return@addSnapshotListener
                if (snap.metadata.isFromCache && !snap.exists()) return@addSnapshotListener
                val minutes = (snap.getLong("minutes") ?: 0L).toInt()
                ScreenTimeConfig.setGoalMinutes(applicationContext, minutes)
            }
    }

    /** "Locate now": the parent asked. The child's phone says so, then shares a fresh location. */
    private fun listenForLocateRequests() {
        locateRegistration?.remove()
        val doc = childDoc() ?: return
        locateRegistration = doc.collection(Col.SETTINGS).document(SettingsDoc.LOCATE)
            .addSnapshotListener { snap, e ->
                if (e != null || snap == null || !snap.exists()) return@addSnapshotListener
                val requestedAt = snap.getTimestamp("requestedAt")?.toDate()?.time
                    ?: return@addSnapshotListener
                val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val handled = prefs.getLong(KEY_LOCATE_HANDLED, 0L)
                if (!LocateRequest.shouldHandle(requestedAt, handled, System.currentTimeMillis())) {
                    return@addSnapshotListener
                }
                prefs.edit().putLong(KEY_LOCATE_HANDLED, requestedAt).apply()
                ChildNotifier.parentAskedForLocation(applicationContext)
                locateNow()
            }
    }

    @SuppressLint("MissingPermission")
    private fun locateNow() {
        if (!Permissions.hasForegroundLocation(this)) return
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { loc ->
                if (loc != null) onLocation(loc, forced = true) else touchLastSeen()
            }
            .addOnFailureListener { touchLastSeen() }
    }

    /** A notification on the child's phone when the parent sends a chat message. */
    private fun listenForParentMessages() {
        messagesRegistration?.remove()
        val doc = childDoc() ?: return
        messagesRegistration = doc.collection(Col.MESSAGES)
            .orderBy("ts", Query.Direction.DESCENDING)
            .limit(1)
            .addSnapshotListener { snap, e ->
                if (e != null || snap == null) return@addSnapshotListener
                val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val latest = snap.documents.firstOrNull()
                val ts = latest?.getTimestamp("ts")?.toDate()?.time

                // First run after install/update: everything already in the chat counts as read.
                if (!prefs.getBoolean(KEY_MSG_BASELINE, false)) {
                    if (snap.metadata.isFromCache && snap.isEmpty) return@addSnapshotListener
                    prefs.edit().putBoolean(KEY_MSG_BASELINE, true).putLong(KEY_MSG_SEEN, ts ?: 0L).apply()
                    return@addSnapshotListener
                }

                if (latest == null || ts == null || latest.metadata.hasPendingWrites()) {
                    return@addSnapshotListener
                }
                if (ts <= prefs.getLong(KEY_MSG_SEEN, 0L)) return@addSnapshotListener
                prefs.edit().putLong(KEY_MSG_SEEN, ts).apply()

                if (latest.getString("senderRole") == "parent" && !ChatVisibility.isOpen) {
                    ChildNotifier.parentMessage(applicationContext, latest.getString("text").orEmpty())
                }
            }
    }

    /** Safe places set by the parent -> Google geofences (battery-friendly, works in background). */
    private fun listenForPlaces() {
        placesRegistration?.remove()
        val doc = childDoc() ?: return
        placesRegistration = doc.collection(Col.PLACES)
            .addSnapshotListener { snap, e ->
                if (e != null || snap == null) return@addSnapshotListener
                // An empty answer from the local cache just means "not loaded yet".
                if (snap.metadata.isFromCache && snap.isEmpty) return@addSnapshotListener
                val places = snap.documents.mapNotNull { d ->
                    PlaceDef(
                        id = d.id,
                        name = d.getString("name") ?: return@mapNotNull null,
                        lat = d.getDouble("lat") ?: return@mapNotNull null,
                        lng = d.getDouble("lng") ?: return@mapNotNull null,
                        radius = (d.getLong("radius") ?: 300L).toInt(),
                    )
                }
                val signature = places.sortedBy { it.id }.joinToString("|")
                if (signature == lastPlacesSignature) return@addSnapshotListener
                lastPlacesSignature = signature
                GeofenceManager.register(applicationContext, places)
            }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Location sharing is on")
            .setContentText("Your parent can see where you are.")
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    private companion object {
        const val CHANNEL_ID = "location_sharing"
        const val NOTIF_ID = 1001

        const val PREFS = "location_service_prefs"
        const val KEY_BATTERY_ARMED = "battery_armed"
        const val KEY_LOCATE_HANDLED = "locate_handled_ms"
        const val KEY_MSG_BASELINE = "msg_baseline_set"
        const val KEY_MSG_SEEN = "msg_seen_ts"

        // Still: same low-power settings as before (5 min, at most every 2 min, only after a 50 m move).
        const val STILL_INTERVAL_MS = 5 * 60_000L
        const val STILL_MIN_INTERVAL_MS = 2 * 60_000L
        const val STILL_MIN_DISTANCE_M = 50f

        // Moving: a steady trail.
        const val MOVING_INTERVAL_MS = 60_000L
        const val MOVING_MIN_INTERVAL_MS = 30_000L
        const val MOVING_MIN_DISTANCE_M = 25f

        // Heartbeat: "still here" even when nobody moves.
        const val HEARTBEAT_INTERVAL_MS = 10 * 60_000L
        const val HEARTBEAT_MIN_INTERVAL_MS = 5 * 60_000L
    }
}
