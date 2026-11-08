package com.example.kidsafe.child

import com.example.kidsafe.core.Col
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

/**
 * The one place the child app tells the parent that something happened:
 * adds a line to the event history and updates `lastEvent` (which the parent's alert service watches).
 * Works offline: Firestore queues the write and sends it when the phone is online again.
 */
object ChildEvents {
    fun post(type: String, extra: Map<String, Any> = emptyMap()) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val event = HashMap<String, Any>(extra)
        event["type"] = type
        event["ts"] = FieldValue.serverTimestamp()
        val ref = FirebaseFirestore.getInstance().collection(Col.CHILDREN).document(uid)
        ref.collection(Col.EVENTS).add(event)
        ref.update("lastEvent", event)
    }
}
