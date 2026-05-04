package com.plusorminustwo.postmark.service.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.plusorminustwo.postmark.data.sync.SmsSyncHandler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

// Receives WAP_PUSH_DELIVER broadcasts when a new MMS arrives. Delegates to
// SmsSyncHandler which queries content://mms for rows we haven't seen yet.
@AndroidEntryPoint
class MmsReceiver : BroadcastReceiver() {

    @Inject lateinit var smsSyncHandler: SmsSyncHandler

    override fun onReceive(context: Context, intent: Intent) {
        smsSyncHandler.onMmsContentChanged(Uri.parse("content://mms"))
    }
}
