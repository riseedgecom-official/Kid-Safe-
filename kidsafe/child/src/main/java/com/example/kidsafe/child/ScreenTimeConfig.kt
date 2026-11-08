package com.example.kidsafe.child

import android.content.Context

/** The daily screen-time goal the parent chose (0 = no goal), cached on the phone. */
object ScreenTimeConfig {
    private const val PREFS = "screentime_prefs"
    private const val KEY_GOAL = "goal_minutes"
    private const val KEY_NOTIFIED = "notified_for"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun goalMinutes(c: Context): Int = prefs(c).getInt(KEY_GOAL, 0)

    fun setGoalMinutes(c: Context, minutes: Int) {
        prefs(c).edit().putInt(KEY_GOAL, minutes.coerceAtLeast(0)).apply()
    }

    /**
     * True only the first time "goal reached" is announced for this day and this goal.
     * (If the parent raises the goal and it is reached again the same day, it is announced again.)
     */
    fun markReachedNotified(c: Context, day: String, goalMinutes: Int): Boolean {
        val key = "$day:$goalMinutes"
        val p = prefs(c)
        if (p.getString(KEY_NOTIFIED, null) == key) return false
        p.edit().putString(KEY_NOTIFIED, key).apply()
        return true
    }
}
