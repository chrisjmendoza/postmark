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

/**
 * [ContentObserver] that listens for changes to the system SMS (`content://sms`)
 * and MMS (`content://mms`) content providers and forwards them to [SmsSyncHandler].
 *
 * [register] is called from [PostmarkApplication] when the app gains the default
 * SMS role; [unregister] is called when the role is lost.
 */
@Singleton
class SmsContentObserver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val smsSyncHandler: SmsSyncHandler
) : ContentObserver(Handler(Looper.getMainLooper())) {

    private val smsUri: Uri = Telephony.Sms.CONTENT_URI
    private val mmsUri: Uri = Uri.parse("content://mms")

    /** Registers observers for both the SMS and MMS content-provider URIs. */
    fun register() {
        context.contentResolver.registerContentObserver(smsUri, true, this)
        context.contentResolver.registerContentObserver(mmsUri, true, this)
    }

    /** Unregisters both content-provider observers. Safe to call multiple times. */
    fun unregister() {
        context.contentResolver.unregisterContentObserver(this)
    }

    /** Routes the changed URI to the appropriate [SmsSyncHandler] path. */
    override fun onChange(selfChange: Boolean, uri: Uri?) {
        val resolved = uri ?: smsUri
        if (resolved.toString().startsWith("content://mms")) {
            smsSyncHandler.onMmsContentChanged(resolved)
        } else {
            smsSyncHandler.onSmsContentChanged(resolved)
        }
    }
}
