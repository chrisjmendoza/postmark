package com.plusorminustwo.postmark.service.sms

import android.content.Context
import android.telephony.SmsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmsManagerWrapper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val smsManager: SmsManager
        get() = context.getSystemService(SmsManager::class.java)

    fun sendTextMessage(
        destinationAddress: String,
        text: String,
        onSuccess: () -> Unit = {},
        onFailure: (Int) -> Unit = {}
    ) {
        val parts = smsManager.divideMessage(text)
        if (parts.size == 1) {
            smsManager.sendTextMessage(
                destinationAddress,
                null,
                text,
                null,
                null
            )
        } else {
            smsManager.sendMultipartTextMessage(
                destinationAddress,
                null,
                parts,
                null,
                null
            )
        }
        onSuccess()
    }
}
