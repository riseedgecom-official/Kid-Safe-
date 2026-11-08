package com.example.kidsafe.child

import android.annotation.SuppressLint
import android.content.Context
import com.example.kidsafe.core.Col
import com.example.kidsafe.core.EventType
import com.example.kidsafe.core.PairingCode
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/** Handles pairing with a parent, consent records, SOS and check-in. */
class PairingRepository(private val context: Context) {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val prefs = context.getSharedPreferences("child_prefs", Context.MODE_PRIVATE)

    val isPaired: Boolean
        get() = prefs.getBoolean(KEY_PAIRED, false) && auth.currentUser != null

    /** The name the child typed when pairing (shown in the greeting). Null if unknown yet. */
    val childName: String?
        get() = prefs.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() }

    /** Redeems the parent's pairing code. Needs internet. */
    suspend fun pair(rawCode: String, childName: String): Result<Unit> = runCatching {
        val code = PairingCode.normalize(rawCode)
        require(code.length == PairingCode.LENGTH) { "Code must be ${PairingCode.LENGTH} characters." }

        val uid = auth.currentUser?.uid ?: auth.signInAnonymously().await().user!!.uid

        val pairingRef = db.collection(Col.PAIRINGS).document(code)
        val pairing = pairingRef.get().await()
        check(pairing.exists()) { "Code not found." }
        check(pairing.getBoolean("used") != true) { "This code was already used." }
        val expires = pairing.getTimestamp("expiresAt")?.toDate()?.time ?: 0L
        check(expires > System.currentTimeMillis()) { "This code has expired. Ask your parent for a new one." }
        val parentUid = pairing.getString("parentUid") ?: error("Invalid code.")

        val childRef = db.collection(Col.CHILDREN).document(uid)
        db.runBatch { batch ->
            batch.set(
                childRef,
                mapOf(
                    "parentUid" to parentUid,
                    "pairingCode" to code,
                    "name" to childName.trim(),
                    "pairedAt" to FieldValue.serverTimestamp(),
                ),
            )
            batch.update(pairingRef, mapOf("used" to true, "usedBy" to uid))
        }.await()

        prefs.edit()
            .putBoolean(KEY_PAIRED, true)
            .putString(KEY_NAME, childName.trim())
            .apply()
    }

    /**
     * Phones that were paired before the greeting existed don't have the name saved locally yet:
     * read it once from the child's own record. Silent on failure (the greeting just stays generic).
     */
    suspend fun loadNameIfMissing() {
        if (childName != null) return
        val uid = auth.currentUser?.uid ?: return
        runCatching {
            val name = db.collection(Col.CHILDREN).document(uid).get().await().getString("name")
            if (!name.isNullOrBlank()) prefs.edit().putString(KEY_NAME, name).apply()
        }
    }

    /** Tells the parent the child has read the disclosure and allowed location. Works offline (queued). */
    fun recordConsent() {
        val uid = auth.currentUser?.uid ?: return
        val ref = db.collection(Col.CHILDREN).document(uid)
        val event = mapOf(
            "type" to EventType.PERMISSION_GRANTED,
            "detail" to "Location (all the time) allowed by child",
            "ts" to FieldValue.serverTimestamp(),
        )
        ref.update(
            mapOf(
                "consent" to mapOf("location" to true, "at" to FieldValue.serverTimestamp()),
                "lastEvent" to event,
            ),
        )
        ref.collection(Col.EVENTS).add(event)
    }

    /** Tells the parent the child switched on usage access (only once). Works offline (queued). */
    fun recordUsageConsentOnce() {
        if (prefs.getBoolean(KEY_USAGE_CONSENT, false)) return
        val uid = auth.currentUser?.uid ?: return
        val ref = db.collection(Col.CHILDREN).document(uid)
        val event = mapOf(
            "type" to EventType.USAGE_ACCESS_GRANTED,
            "detail" to "App usage access allowed by child",
            "ts" to FieldValue.serverTimestamp(),
        )
        ref.update(mapOf("consent.usage" to true, "lastEvent" to event))
        ref.collection(Col.EVENTS).add(event)
        prefs.edit().putBoolean(KEY_USAGE_CONSENT, true).apply()
    }

    /** Child taps "I need to talk": tells the parent, no location attached. Works offline (queued). */
    fun sendNeedTalk() {
        ChildEvents.post(EventType.NEED_TALK)
    }

    /** Child taps SOS: send current location + alert. Works offline (queued). */
    @SuppressLint("MissingPermission")
    suspend fun sendSos(): Result<Unit> = runCatching {
        auth.currentUser?.uid ?: error("Not signed in.")
        val loc = LocationServices.getFusedLocationProviderClient(context)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .await()

        val extra = mutableMapOf<String, Any>()
        if (loc != null) {
            extra["lat"] = loc.latitude
            extra["lng"] = loc.longitude
        }
        ChildEvents.post(EventType.SOS, extra)
    }

    /** Child taps "I'm safe": a calm "I'm okay" note with the current location. Works offline (queued). */
    @SuppressLint("MissingPermission")
    suspend fun sendCheckIn(): Result<Unit> = runCatching {
        auth.currentUser?.uid ?: error("Not signed in.")
        val loc = LocationServices.getFusedLocationProviderClient(context)
            .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
            .await()

        val extra = mutableMapOf<String, Any>()
        if (loc != null) {
            extra["lat"] = loc.latitude
            extra["lng"] = loc.longitude
        }
        ChildEvents.post(EventType.CHECK_IN, extra)
    }

    private companion object {
        const val KEY_PAIRED = "paired"
        const val KEY_NAME = "child_name"
        const val KEY_USAGE_CONSENT = "usage_consent"
    }
}
