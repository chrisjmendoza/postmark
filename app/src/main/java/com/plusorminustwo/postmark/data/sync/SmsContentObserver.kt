package com.plusorminustwo.postmark.data.sync

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmsContentObserver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val smsSyncHandler: SmsSyncHandler
) : ContentObserver(Handler(Looper.getMainLooper())) {

    private val smsUri: Uri = Telephony.Sms.CONTENT_URI
    private val mmsUri: Uri = Uri.parse("content://mms")

    fun register() {
        context.contentResolver.registerContentObserver(smsUri, true, this)
        context.contentResolver.registerContentObserver(mmsUri, true, this)
    }

    fun unregister() {
        context.contentResolver.unregisterContentObserver(this)
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        val resolved = uri ?: smsUri
        if (resolved.toString().startsWith("content://mms")) {
            smsSyncHandler.onMmsContentChanged(resolved)
        } else {
            smsSyncHandler.onSmsContentChanged(resolved)
        }
    }
}
