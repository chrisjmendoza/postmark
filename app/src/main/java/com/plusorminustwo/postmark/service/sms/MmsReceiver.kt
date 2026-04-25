package com.plusorminustwo.postmark.service.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import dagger.hilt.android.AndroidEntryPoint

// Placeholder: MMS handling requires WAP push parsing which is handled
// by the default telephony stack. This receiver claims the intent so
// Postmark can be set as the default SMS app.
@AndroidEntryPoint
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // MMS delivery intent — no-op for now; full MMS sync
        // reads from content://mms during FirstLaunchSyncWorker
    }
}
