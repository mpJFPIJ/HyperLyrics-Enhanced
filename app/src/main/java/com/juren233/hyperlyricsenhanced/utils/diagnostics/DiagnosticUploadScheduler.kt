package com.juren233.hyperlyricsenhanced.utils.diagnostics

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle

/** Android-owned jobs survive process death and respect network/battery constraints. */
internal object DiagnosticUploadScheduler {
    private const val PERIODIC_ID = 64012
    private const val ON_ERROR_ID = 64013
    private const val MANUAL_ID = 64014
    private const val FIFTEEN_MINUTES = 15 * 60 * 1000L

    fun sync(context: Context) {
        val app = context.applicationContext
        val scheduler = app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val config = DiagnosticGitHubSettings.configuration(app)
        if (!config.enabled || config.repository == null || !config.hasToken) {
            scheduler.cancel(PERIODIC_ID)
            scheduler.cancel(ON_ERROR_ID)
            scheduler.cancel(MANUAL_ID)
            return
        }
        if (scheduler.getPendingJob(PERIODIC_ID) == null) {
            val job = JobInfo.Builder(PERIODIC_ID, ComponentName(app, DiagnosticUploadJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(FIFTEEN_MINUTES)
                .build()
            scheduler.schedule(job)
        }
    }

    fun onTranslationFailure(context: Context) {
        val app = context.applicationContext
        val configuration = DiagnosticGitHubSettings.configuration(app)
        if (!configuration.enabled || !configuration.hasToken || configuration.repository == null) return
        val elapsed = System.currentTimeMillis() - DiagnosticGitHubSettings.lastAttempt(app)
        if (elapsed >= 0L && elapsed < FIFTEEN_MINUTES) return
        val scheduler = app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        if (scheduler.getPendingJob(ON_ERROR_ID) == null) {
            scheduler.schedule(JobInfo.Builder(ON_ERROR_ID, ComponentName(app, DiagnosticUploadJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(8_000L)
                .build())
        }
    }

    fun uploadNow(context: Context) {
        val app = context.applicationContext
        if (!DiagnosticGitHubSettings.configuration(app).enabled) return
        val scheduler = app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        scheduler.schedule(JobInfo.Builder(MANUAL_ID, ComponentName(app, DiagnosticUploadJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setExtras(PersistableBundle().apply { putBoolean("force", true) })
            .setMinimumLatency(0L)
            .build())
    }
}
