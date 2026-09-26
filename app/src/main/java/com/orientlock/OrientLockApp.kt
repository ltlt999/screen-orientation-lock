package com.orientlock

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.orientlock.system.NotificationHelper
import com.orientlock.system.OrientationService

class OrientLockApp : Application() {

    override fun onCreate() {
        super.onCreate()
        NotificationHelper(this).ensureChannel()
    }

    companion object {
        /**
         * 启动方向服务，带兜底。
         *
         * Android 15 起从 BOOT_COMPLETED 直接启动前台服务可能被系统拒绝
         * （抛 IllegalStateException）。被拒时退化为 WorkManager 立即执行，
         * 由 Worker 在系统允许的时机再拉起服务。
         */
        fun startOrientationServiceSafely(context: Context) {
            try {
                startNow(context)
            } catch (e: IllegalStateException) {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<BootRetryWorker>().build(),
                )
            }
        }

        private fun startNow(context: Context) {
            val intent = Intent(context, OrientationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        private const val WORK_NAME = "orientation-service-start"
    }
}

/** 开机启动被拒后的兜底 Worker */
class BootRetryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val intent = Intent(applicationContext, OrientationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(intent)
            } else {
                applicationContext.startService(intent)
            }
            Result.success()
        } catch (e: IllegalStateException) {
            Result.retry()
        }
    }
}
