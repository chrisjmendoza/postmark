package com.plusorminustwo.postmark.domain.messageinfo

import com.plusorminustwo.postmark.domain.model.Message

/**
 * A value to render on the Message info sheet, decided purely from message fields.
 *
 * The pure layer picks WHICH rows show and WHAT each row's value comes from; it never
 * formats or touches Android. [Timestamp] is resolved to an absolute date+time string by
 * the UI's date formatter, and [SmsParts] by the platform `SmsMessage.calculateLength`
 * call — neither belongs here, which is what keeps [messageInfoRows] unit-testable.
 */
sealed interface MessageInfoValue {
    /** An epoch-millis instant the UI formats to an absolute date+time. */
    data class Timestamp(val epochMillis: Long) : MessageInfoValue

    /** A ready-to-render literal (character count, attachment count, transport). */
    data class Text(val value: String) : MessageInfoValue

    /** Marker: the UI computes the SMS segment count from [body] via the platform
     *  `SmsMessage.calculateLength(body, false)[0]` call. */
    data class SmsParts(val body: String) : MessageInfoValue
}

/** One labeled row of the Message info sheet. */
data class MessageInfoRow(val label: String, val value: MessageInfoValue)

/**
 * Pure decision of which rows the Message info sheet shows for [message] and where each
 * row's value comes from. No Android, no string formatting — fully unit-testable.
 *
 * Row rules:
 *  - "Sent" for sent messages (falling back to the receipt [Message.timestamp] when the
 *    provider recorded no distinct [Message.sentAt]); "Received" for incoming messages,
 *    always labeled by receipt time.
 *  - "Delivered" only when a carrier delivery report actually set [Message.deliveredAt].
 *  - "Characters" omitted for blank / media-only bodies.
 *  - "Type" ("SMS"/"MMS") for every message; SMS additionally shows "Parts" (segment
 *    count), MMS instead shows "Attachments" when it carries any.
 */
fun messageInfoRows(message: Message): List<MessageInfoRow> = buildList {
    if (message.isSent) {
        add(MessageInfoRow("Sent", MessageInfoValue.Timestamp(message.sentAt ?: message.timestamp)))
    } else {
        add(MessageInfoRow("Received", MessageInfoValue.Timestamp(message.timestamp)))
    }

    message.deliveredAt?.let {
        add(MessageInfoRow("Delivered", MessageInfoValue.Timestamp(it)))
    }

    if (message.body.isNotBlank()) {
        add(MessageInfoRow("Characters", MessageInfoValue.Text(message.body.length.toString())))
    }

    if (message.isMms) {
        add(MessageInfoRow("Type", MessageInfoValue.Text("MMS")))
        if (message.attachments.isNotEmpty()) {
            add(MessageInfoRow("Attachments", MessageInfoValue.Text(message.attachments.size.toString())))
        }
    } else {
        add(MessageInfoRow("Type", MessageInfoValue.Text("SMS")))
        add(MessageInfoRow("Parts", MessageInfoValue.SmsParts(message.body)))
    }
}
