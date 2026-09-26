package com.orientlock

import android.app.Application
import android.content.Context
import android.content.Intent
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
         * 为什么抓 IllegalStateException 这个超类型：ForegroundServiceStartNotAllowedException
         * 与 ServiceStartNotAllowedException 都是它的子类，但 API 31 才有，
         * 而 minSdk 是 26——26~30 上系统抛的就是裸 IllegalStateException。
         * 用超类型是唯一能覆盖整个支持区间的单一写法；同时它不会吞掉
         * ForegroundServiceTypeException 那一类（那些继承 IllegalArgumentException），
         * manifest 配错照样响亮地崩。
         *
         * 触发场景不止开机：Android 12 起应用没有可见窗口时启动前台服务也会被拒。
         * 被拒时退化为 WorkManager 立即执行，由 Worker 在系统允许的时机再拉起服务。
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
            // minSdk 26，startForegroundService 一直可用，不需要版本分支
            context.startForegroundService(Intent(context, OrientationService::class.java))
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
            // minSdk 26，startForegroundService 一直可用，不需要版本分支
            applicationContext.startForegroundService(
                Intent(applicationContext, OrientationService::class.java)
            )
            Result.success()
        } catch (e: IllegalStateException) {
            Result.retry()
        }
    }
}
