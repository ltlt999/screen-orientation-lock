package com.orientlock.system

import android.content.Context
import android.content.Intent
import android.util.Log
import com.orientlock.OrientLockApp

/** 启停方向服务的抽象，供 ViewModel 依赖（测试用假实现） */
interface ServiceGateway {
    fun start()
    fun stop()
    fun restartGuard()
}

class AndroidServiceGateway(private val context: Context) : ServiceGateway {

    override fun start() {
        OrientLockApp.startOrientationServiceSafely(context)
    }

    override fun stop() {
        context.stopService(Intent(context, OrientationService::class.java))
    }

    /**
     * 重载守护。
     *
     * 服务没在跑时不能默默丢掉这次开关：设置本身已经落盘，下次服务启动会照它布防，
     * 所以这里只能记录日志然后返回。**不能反过来把服务拉起来**——用户可能刚关掉
     * 常驻通知，此刻启动服务等于把通知又唤回来，与他的意图相反。
     */
    override fun restartGuard() {
        val active = OrientationService.activeInstance()
        if (active == null) {
            Log.i(TAG, "restartGuard：服务未运行，设置已落盘，下次启动生效")
            return
        }
        active.restartGuard()
    }

    private companion object {
        const val TAG = "AndroidServiceGateway"
    }
}
