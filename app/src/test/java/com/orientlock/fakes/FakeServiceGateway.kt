package com.orientlock.fakes

import com.orientlock.system.ServiceGateway

class FakeServiceGateway : ServiceGateway {
    var startCalls = 0
    var stopCalls = 0
    var restartGuardCalls = 0

    override fun start() { startCalls++ }
    override fun stop() { stopCalls++ }
    override fun restartGuard() { restartGuardCalls++ }
}
