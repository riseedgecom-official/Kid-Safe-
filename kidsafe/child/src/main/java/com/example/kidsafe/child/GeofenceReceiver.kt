package com.example.kidsafe.child

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.example.kidsafe.core.Col
import com.example.kidsafe.core.EventType
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.util.concurrent.atomic.AtomicBoolean

/** Called by Android when the child enters or leaves a safe place. Tells the parent. */
class GeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        val inside = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> true
            Geofence.GEOFENCE_TRANSITION_EXIT -> false
            else -> return
        }
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        val pending = goAsync()
        val finished = AtomicBoolean(false)
        fun finish() {
            if (finished.compareAndSet(false, true)) pending.finish()
        }

        val ref = FirebaseFirestore.getInstance().collection(Col.CHILDREN).document(uid)
        val tasks = mutableListOf<Task<*>>()
        event.triggeringGeofences.orEmpty().forEach { g ->
            if (!GeofenceManager.shouldReport(context, g.requestId, inside)) return@forEach
            val data = hashMapOf<String, Any>(
                "type" to (if (inside) EventType.GEOFENCE_ENTER else EventType.GEOFENCE_EXIT),
                "place" to GeofenceManager.nameOf(context, g.requestId),
                "ts" to FieldValue.serverTimestamp(),
            )
            tasks += ref.collection(Col.EVENTS).add(data)
            tasks += ref.update("lastEvent", data)
        }

        if (tasks.isEmpty()) {
            finish()
            return
        }
        Tasks.whenAllComplete(tasks).addOnCompleteListener { finish() }
        // Never keep the system waiting: Firestore keeps the write queued if we are offline.
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 8_000L)
    }
}
