package com.example.kidsafe.child

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class UsageWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result =
        try {
            UsageReporter.report(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
}

object UsageScheduler {
    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Every 30 minutes; survives reboots (WorkManager handles that). Safe to call many times. */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<UsageWorker>(30, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "usage_report", ExistingPeriodicWorkPolicy.KEEP, request,
        )
    }

    /** One report right now (e.g. just after the child allowed usage access). */
    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<UsageWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }
}
