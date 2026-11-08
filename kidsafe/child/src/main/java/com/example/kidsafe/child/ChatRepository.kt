package com.example.kidsafe.child

import com.example.kidsafe.core.ChatMessage
import com.example.kidsafe.core.Col
import com.example.kidsafe.core.EventType
import com.example.kidsafe.core.Fmt
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query

/**
 * The parent <-> child chat, child side. Both apps write into the same
 * `children/{childId}/messages` subcollection (see firestore.rules).
 */
class ChatRepository {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    /** Live updates for this child's chat, oldest first. Call `.remove()` on the result when done. */
    fun listen(onChange: (List<ChatMessage>) -> Unit): ListenerRegistration? {
        val uid = auth.currentUser?.uid ?: return null
        return db.collection(Col.CHILDREN).document(uid)
            .collection(Col.MESSAGES)
            .orderBy("ts", Query.Direction.ASCENDING)
            .limitToLast(500)
            .addSnapshotListener { snap, _ ->
                onChange(snap?.documents.orEmpty().map { it.toChatMessage() })
            }
    }

    /** Sends a message to the parent. Also raises the usual alert, so the parent is notified. */
    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val uid = auth.currentUser?.uid ?: return
        val ref = db.collection(Col.CHILDREN).document(uid)

        ref.collection(Col.MESSAGES).add(
            mapOf(
                "senderId" to uid,
                "senderRole" to "child",
                "text" to trimmed,
                "ts" to FieldValue.serverTimestamp(),
            ),
        )

        // Reuses the normal event pipeline (same as SOS / "I need to talk"), so this message also
        // shows up as a push notification and in the parent's notification history.
        // "place" is reused as a generic preview field, same as WEB_BLOCKED does.
        ChildEvents.post(EventType.CHAT_MESSAGE, mapOf("place" to Fmt.preview(trimmed)))
    }
}

private fun DocumentSnapshot.toChatMessage() = ChatMessage(
    id = id,
    fromChild = getString("senderRole") == "child",
    text = getString("text").orEmpty(),
    time = getTimestamp("ts")?.toDate()?.time ?: System.currentTimeMillis(),
)
