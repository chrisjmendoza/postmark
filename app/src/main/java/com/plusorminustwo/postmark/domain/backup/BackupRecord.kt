package com.plusorminustwo.postmark.domain.backup

/*
 * Record types and line codecs for the v2 backup format, plus the v1 reader.
 *
 * A v2 archive's data.jsonl holds one compact JSON object per line: a thread record
 * ("t":"thread") followed implicitly by that thread's message records ("t":"msg").
 * Records deliberately carry NO local row ids — message/thread ids are device-local
 * system-provider ids, so cross-device identity is the content fingerprint
 * (see RestoreMerge.kt) and the stream's thread grouping.
 */

/** Contents of a v2 archive's manifest.json. Counts let the restore UI show what a
 *  file contains without reading the whole archive. [encryption] is a forward-compat
 *  field for TODO item #13 — always "none" today; a future reader must refuse
 *  values it doesn't understand. */
data class BackupManifest(
    val version: Int,
    val exportedAt: Long,
    val threadCount: Int,
    val messageCount: Int,
    val attachmentCount: Int,
    val encryption: String = "none"
)

/** One attachment of a backed-up message: the SHA-256 (hex) naming its blob entry
 *  in the archive, plus the MIME type needed to rebuild [MessageAttachment]. */
data class AttachmentRef(
    val sha256: String,
    val mimeType: String
)

/** One reaction on a backed-up message. No message id — a reaction belongs to the
 *  message record that carries it. */
data class ReactionRecord(
    val sender: String,
    val emoji: String,
    val timestamp: Long,
    val rawText: String
)

/** Thread metadata as backed up. [backupPolicy] is the enum name as a string so the
 *  format doesn't break if the enum gains values. */
data class ThreadRecord(
    val address: String,
    val displayName: String,
    val nickname: String?,
    val isPinned: Boolean,
    val isMuted: Boolean,
    val notificationsEnabled: Boolean,
    val backupPolicy: String,
    val participants: List<String>,
    val lastMessageAt: Long,
    val lastMessagePreview: String
)

/** One message as backed up — full fidelity minus device-local ids. */
data class MessageRecord(
    val address: String,
    val body: String,
    val timestamp: Long,
    val isSent: Boolean,
    val type: Int,
    val deliveryStatus: Int,
    val isMms: Boolean,
    val isRead: Boolean,
    val isStarred: Boolean,
    val attachments: List<AttachmentRef>,
    val reactions: List<ReactionRecord>
)

/** A decoded data.jsonl line. */
sealed interface BackupLine {
    data class ThreadStart(val thread: ThreadRecord) : BackupLine
    data class Msg(val message: MessageRecord) : BackupLine
}

// ---------------------------------------------------------------------------
// Encoding
// ---------------------------------------------------------------------------

fun encodeManifest(manifest: BackupManifest): String = encodeJson(
    linkedMapOf(
        "version" to manifest.version,
        "exportedAt" to manifest.exportedAt,
        "threadCount" to manifest.threadCount,
        "messageCount" to manifest.messageCount,
        "attachmentCount" to manifest.attachmentCount,
        "encryption" to manifest.encryption
    )
)

fun encodeThreadLine(thread: ThreadRecord): String = encodeJson(
    linkedMapOf(
        "t" to "thread",
        "address" to thread.address,
        "displayName" to thread.displayName,
        "nickname" to thread.nickname,
        "isPinned" to thread.isPinned,
        "isMuted" to thread.isMuted,
        "notificationsEnabled" to thread.notificationsEnabled,
        "backupPolicy" to thread.backupPolicy,
        "participants" to thread.participants,
        "lastMessageAt" to thread.lastMessageAt,
        "lastMessagePreview" to thread.lastMessagePreview
    )
)

fun encodeMessageLine(message: MessageRecord): String = encodeJson(
    linkedMapOf(
        "t" to "msg",
        "address" to message.address,
        "body" to message.body,
        "timestamp" to message.timestamp,
        "isSent" to message.isSent,
        "type" to message.type,
        "deliveryStatus" to message.deliveryStatus,
        "isMms" to message.isMms,
        "isRead" to message.isRead,
        "isStarred" to message.isStarred,
        "attachments" to message.attachments.map {
            linkedMapOf("sha256" to it.sha256, "mimeType" to it.mimeType)
        },
        "reactions" to message.reactions.map {
            linkedMapOf(
                "sender" to it.sender,
                "emoji" to it.emoji,
                "timestamp" to it.timestamp,
                "rawText" to it.rawText
            )
        }
    )
)

// ---------------------------------------------------------------------------
// Decoding
// ---------------------------------------------------------------------------

/** Parses manifest.json. Returns null if the text isn't a parseable manifest. */
fun decodeManifest(text: String): BackupManifest? {
    val map = runCatching { parseJson(text) }.getOrNull() as? Map<*, *> ?: return null
    return BackupManifest(
        version = map.int("version") ?: return null,
        exportedAt = map.long("exportedAt") ?: 0L,
        threadCount = map.int("threadCount") ?: 0,
        messageCount = map.int("messageCount") ?: 0,
        attachmentCount = map.int("attachmentCount") ?: 0,
        encryption = map.str("encryption") ?: "none"
    )
}

/**
 * Decodes one data.jsonl line. Returns null for blank lines and for record types
 * this build doesn't know (forward compatibility — a future format revision can add
 * record types without breaking old readers). Malformed JSON throws; the restore
 * worker catches per line and counts skips rather than aborting the whole restore.
 */
fun decodeBackupLine(line: String): BackupLine? {
    if (line.isBlank()) return null
    val map = parseJson(line) as? Map<*, *>
        ?: throw IllegalArgumentException("Record line is not a JSON object")
    return when (map.str("t")) {
        "thread" -> BackupLine.ThreadStart(decodeThreadRecord(map))
        "msg"    -> BackupLine.Msg(decodeMessageRecord(map))
        else     -> null
    }
}

private fun decodeThreadRecord(map: Map<*, *>): ThreadRecord = ThreadRecord(
    address = map.str("address")
        ?: throw IllegalArgumentException("Thread record missing address"),
    displayName = map.str("displayName") ?: "",
    nickname = map.str("nickname"),
    isPinned = map.bool("isPinned") ?: false,
    isMuted = map.bool("isMuted") ?: false,
    notificationsEnabled = map.bool("notificationsEnabled") ?: true,
    backupPolicy = map.str("backupPolicy") ?: "GLOBAL",
    participants = (map["participants"] as? List<*>)?.filterIsInstance<String>()
        ?: emptyList(),
    lastMessageAt = map.long("lastMessageAt") ?: 0L,
    lastMessagePreview = map.str("lastMessagePreview") ?: ""
)

private fun decodeMessageRecord(map: Map<*, *>): MessageRecord = MessageRecord(
    address = map.str("address") ?: "",
    body = map.str("body") ?: "",
    timestamp = map.long("timestamp")
        ?: throw IllegalArgumentException("Message record missing timestamp"),
    isSent = map.bool("isSent") ?: false,
    type = map.int("type") ?: if (map.bool("isSent") == true) 2 else 1,
    deliveryStatus = map.int("deliveryStatus") ?: 0,
    isMms = map.bool("isMms") ?: false,
    isRead = map.bool("isRead") ?: true,
    isStarred = map.bool("isStarred") ?: false,
    attachments = (map["attachments"] as? List<*>).orEmpty().mapNotNull { att ->
        (att as? Map<*, *>)?.let { a ->
            val sha = a.str("sha256") ?: return@let null
            AttachmentRef(sha, a.str("mimeType") ?: "application/octet-stream")
        }
    },
    reactions = (map["reactions"] as? List<*>).orEmpty().mapNotNull { r ->
        (r as? Map<*, *>)?.let { m ->
            ReactionRecord(
                sender = m.str("sender") ?: return@let null,
                emoji = m.str("emoji") ?: return@let null,
                timestamp = m.long("timestamp") ?: 0L,
                rawText = m.str("rawText") ?: ""
            )
        }
    }
)

// ---------------------------------------------------------------------------
// v1 reader
// ---------------------------------------------------------------------------

/** One thread of a parsed v1 backup file. */
data class V1Thread(val thread: ThreadRecord, val messages: List<MessageRecord>)

/**
 * Parses a whole v1 backup document (`{"version":1,...,"threads":[...]}`).
 *
 * v1 was lossy — messages carried only body/timestamp/isSent — so the missing fields
 * are synthesized: address from the owning thread, `type` from isSent, SMS-only,
 * read, no attachments/reactions, no thread metadata. Throws on structurally
 * unparseable input; individual malformed thread/message entries are skipped.
 */
fun parseV1Backup(text: String): List<V1Thread> {
    val root = parseJson(text) as? Map<*, *>
        ?: throw IllegalArgumentException("v1 backup root is not a JSON object")
    val threads = root["threads"] as? List<*>
        ?: throw IllegalArgumentException("v1 backup has no threads array")
    return threads.mapNotNull { entry ->
        val t = entry as? Map<*, *> ?: return@mapNotNull null
        val address = t.str("address") ?: return@mapNotNull null
        val messages = (t["messages"] as? List<*>).orEmpty().mapNotNull { m ->
            val msg = m as? Map<*, *> ?: return@mapNotNull null
            val timestamp = msg.long("timestamp") ?: return@mapNotNull null
            val isSent = msg.bool("isSent") ?: false
            MessageRecord(
                address = address,
                body = msg.str("body") ?: "",
                timestamp = timestamp,
                isSent = isSent,
                type = if (isSent) 2 else 1,
                deliveryStatus = 0,
                isMms = false,
                isRead = true,
                isStarred = false,
                attachments = emptyList(),
                reactions = emptyList()
            )
        }
        V1Thread(
            thread = ThreadRecord(
                address = address,
                displayName = t.str("displayName") ?: address,
                nickname = null,
                isPinned = false,
                isMuted = false,
                notificationsEnabled = true,
                backupPolicy = "GLOBAL",
                participants = emptyList(),
                lastMessageAt = messages.maxOfOrNull { it.timestamp } ?: 0L,
                lastMessagePreview = messages.lastOrNull()?.body ?: ""
            ),
            messages = messages
        )
    }
}

// ---------------------------------------------------------------------------
// Typed map accessors shared by the decoders
// ---------------------------------------------------------------------------

private fun Map<*, *>.str(key: String): String? = this[key] as? String

private fun Map<*, *>.bool(key: String): Boolean? = this[key] as? Boolean

private fun Map<*, *>.long(key: String): Long? = when (val v = this[key]) {
    is Long   -> v
    is Int    -> v.toLong()
    is Double -> v.toLong()
    else      -> null
}

private fun Map<*, *>.int(key: String): Int? = long(key)?.toInt()
