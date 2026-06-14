package com.plusorminustwo.postmark.service.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import com.plusorminustwo.postmark.data.sync.SmsSyncHandler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Receives [ACTION_ARCHIVAL_UPDATE] broadcasts sent by Google Messages when an RCS
 * message is sent or received.
 *
 * Prior to the June 2026 Google Play Services update, Google Messages wrote RCS
 * messages to content://sms / content://mms in a way that triggered the standard
 * Android content observer, so [SmsSyncHandler] would pick them up automatically.
 * After the update, Google Messages instead fires an explicit GOOGLE_MESSAGES_ARCHIVAL_UPDATE
 * broadcast carrying the URI of the affected Telephony provider row. The content
 * observer no longer fires, so without this receiver new RCS messages are silently
 * skipped by Postmark's sync pipeline.
 *
 * On receipt we route to [SmsSyncHandler.onSmsContentChanged] or
 * [SmsSyncHandler.onMmsContentChanged] based on the URI scheme, triggering the
 * same incremental sync that the content observer would have started.
 */
@AndroidEntryPoint
class RcsArchivalReceiver : BroadcastReceiver() {

    @Inject lateinit var smsSyncHandler: SmsSyncHandler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ARCHIVAL_UPDATE) return

        val uri: Uri? = intent.data
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_ARCHIVAL_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_ARCHIVAL_URI)
            }
            ?: intent.getStringExtra(EXTRA_ARCHIVAL_URI)?.let { Uri.parse(it) }

        val uriStr = uri?.toString() ?: ""
        when {
            uriStr.startsWith("content://sms") ->
                smsSyncHandler.onSmsContentChanged(uri!!)
            uriStr.startsWith("content://mms") ->
                smsSyncHandler.onMmsContentChanged(uri!!)
            else -> {
                // URI absent or unrecognised — trigger both syncs to be safe.
                smsSyncHandler.onSmsContentChanged(Telephony.Sms.CONTENT_URI)
                smsSyncHandler.onMmsContentChanged(Uri.parse("content://mms"))
            }
        }
    }

    companion object {
        // Broadcast sent by Google Messages when an RCS message is archived to the
        // Telephony provider. The action is not publicly documented; this namespaced form
        // matches the extra key's namespace (com.google.android.apps.messaging.*).
        // TODO: verify on a device running Play Services v26.22+ — run:
        //   adb shell am broadcast-receiver -a com.google.android.apps.messaging.GOOGLE_MESSAGES_ARCHIVAL_UPDATE
        //   or capture with: adb logcat | grep ARCHIVAL
        // If the action turns out to be the bare "GOOGLE_MESSAGES_ARCHIVAL_UPDATE", revert this.
        const val ACTION_ARCHIVAL_UPDATE =
            "com.google.android.apps.messaging.GOOGLE_MESSAGES_ARCHIVAL_UPDATE"
        private const val EXTRA_ARCHIVAL_URI =
            "com.google.android.apps.messaging.EXTRA_ARCHIVAL_URI"
    }
}
