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

    fun register() {
        context.contentResolver.registerContentObserver(
            smsUri,
            /* notifyForDescendants = */ true,
            this
        )
    }

    fun unregister() {
        context.contentResolver.unregisterContentObserver(this)
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        smsSyncHandler.onSmsContentChanged(uri ?: smsUri)
    }
}
