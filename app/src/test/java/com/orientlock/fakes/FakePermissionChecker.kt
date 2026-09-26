package com.orientlock.fakes

import com.orientlock.system.PermissionChecker

class FakePermissionChecker(
    var writeSettings: Boolean = true,
    var postNotifications: Boolean = true,
) : PermissionChecker {
    override fun canWriteSettings(): Boolean = writeSettings
    override fun canPostNotifications(): Boolean = postNotifications
}
