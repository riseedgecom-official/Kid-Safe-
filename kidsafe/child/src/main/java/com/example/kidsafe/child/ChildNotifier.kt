package com.example.kidsafe.child

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.kidsafe.core.Fmt

/** True while the chat screen is on screen, so a new message doesn't also pop a notification. */
object ChatVisibility {
    @Volatile
    var isOpen: Boolean = false
}

/**
 * Notifications shown to the CHILD about things the parent did. Nothing here is hidden from the
 * child: whenever the parent asks for something, the child's phone says so.
 */
object ChildNotifier {

    private const val CHANNEL = "parent_updates"
    private const val ID_LOCATE = 2001
    private const val ID_MESSAGE = 2002
    private const val ID_GOAL = 2003

    private fun ensureChannel(c: Context) {
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Updates from your parent", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    @SuppressLint("MissingPermission")
    private fun show(c: Context, id: Int, title: String, text: String, openChat: Boolean) {
        if (!Permissions.hasNotifications(c)) return
        ensureChannel(c)
        val open = Intent(c, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_CHAT, openChat)
        }
        val pi = PendingIntent.getActivity(
            c, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        NotificationManagerCompat.from(c).notify(id, n)
    }

    /** "Locate now" arrived: tell the child their parent asked. */
    fun parentAskedForLocation(c: Context) =
        show(c, ID_LOCATE, "Your parent asked where you are", "Your phone is sharing its location now.", false)

    fun parentMessage(c: Context, text: String) =
        show(c, ID_MESSAGE, "Message from your parent", Fmt.preview(text, 140), true)

    fun screenGoalReached(c: Context, goalMinutes: Int) =
        show(
            c, ID_GOAL, "You reached today's screen-time goal",
            "Your goal was ${Fmt.goal(goalMinutes)}. Time for a break!", false,
        )
}
