package com.plusorminustwo.postmark.util

import android.app.role.RoleManager
import android.content.Context
import android.os.Build
import android.provider.Telephony

/**
 * True when Postmark currently holds the default-SMS-app role.
 *
 * On Android 10+ the [RoleManager] is the authoritative source — it can report the
 * role as held slightly before [Telephony.Sms.getDefaultSmsPackage] catches up right
 * after the system grant dialog — so it is checked first. The package comparison is a
 * fallback that also covers pre-Q devices.
 */
fun Context.isDefaultSmsApp(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val rm = getSystemService(RoleManager::class.java)
        if (rm?.isRoleHeld(RoleManager.ROLE_SMS) == true) return true
    }
    return Telephony.Sms.getDefaultSmsPackage(this) == packageName
}
