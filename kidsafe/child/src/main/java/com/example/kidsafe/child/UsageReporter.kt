package com.example.kidsafe.child

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.example.kidsafe.core.Col
import com.example.kidsafe.core.EventType
import com.example.kidsafe.core.ScreenTimeGoal
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Reads "which app, how long" from Android for today. Only app name + time, nothing inside an app. */
object TodayUsage {

    class App(val pkg: String, val name: String, val ms: Long)

    fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun dayKey(start: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(start))

    /** Total screen time today in ms (same counting as the report). 0 if usage access is off. */
    fun totalMsToday(context: Context): Long {
        if (!Permissions.hasUsageAccess(context)) return 0L
        return collect(context, startOfToday(), System.currentTimeMillis()).sumOf { it.ms }
    }

    @Suppress("DEPRECATION")
    fun collect(context: Context, start: Long, end: Long): List<App> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val pm = context.packageManager

        // Don't count our own app, system UI or the home screen.
        val skip = buildSet {
            add(context.packageName)
            add("com.android.systemui")
            pm.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo?.packageName?.let { add(it) }
        }

        val totals = HashMap<String, Long>()
        var curPkg: String? = null
        var curStart = 0L

        fun close(pkg: String, at: Long) {
            if (at > curStart) totals[pkg] = (totals[pkg] ?: 0L) + (at - curStart)
        }

        val events = usm.queryEvents(start, end)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            when (e.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    if (curPkg != pkg) {
                        curPkg?.let { close(it, e.timeStamp) }
                        curPkg = pkg
                        curStart = e.timeStamp
                    }
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    if (curPkg == pkg) {
                        close(pkg, e.timeStamp)
                        curPkg = null
                    }
                }
            }
        }
        curPkg?.let { close(it, end) } // app still open right now

        return totals.entries
            .filter { it.key !in skip && it.value >= 30_000L }
            .sortedByDescending { it.value }
            .take(40)
            .map { App(it.key, labelOf(pm, it.key), it.value) }
    }

    @Suppress("DEPRECATION")
    private fun labelOf(pm: PackageManager, pkg: String): String =
        try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
}

/** Builds today's report and uploads it, then checks the parent's daily screen-time goal. */
object UsageReporter {

    /** Runs on a background thread (WorkManager). */
    fun report(context: Context) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (!Permissions.hasUsageAccess(context)) return

        val start = TodayUsage.startOfToday()
        val day = TodayUsage.dayKey(start)
        val apps = TodayUsage.collect(context, start, System.currentTimeMillis())
        val total = apps.sumOf { it.ms }
        val goal = ScreenTimeConfig.goalMinutes(context)

        val childRef = FirebaseFirestore.getInstance().collection(Col.CHILDREN).document(uid)
        val data = mapOf(
            "date" to day,
            "totalMs" to total,
            "apps" to apps.map { mapOf("pkg" to it.pkg, "name" to it.name, "ms" to it.ms) },
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        val task = childRef.collection(Col.USAGE).document(day).set(data)
        Tasks.await(task, 30, TimeUnit.SECONDS)

        // A one-line summary on the child's record: the parent's family list shows "today's screen time".
        childRef.update("usageToday", mapOf("date" to day, "totalMs" to total))

        // Daily goal: tell the child and the parent once when it is reached. Nothing is blocked.
        if (ScreenTimeGoal.isExceeded(total, goal) && ScreenTimeConfig.markReachedNotified(context, day, goal)) {
            ChildEvents.post(
                EventType.SCREEN_LIMIT,
                mapOf("goalMinutes" to goal, "usedMinutes" to (total / 60_000L).toInt()),
            )
            ChildNotifier.screenGoalReached(context, goal)
        }
    }
}
