# Android SMS/MMS API Reference for Postmark
> Compiled: May 3, 2026 — Last audited: May 3, 2026
> Sources: developer.android.com official API reference + project briefing audit
> Purpose: Single reference doc for all Android APIs touching this app — use this
>          before implementing any feature that reads/writes SMS, MMS, Contacts,
>          or Blocked Numbers.

---

## Table of Contents
1. [Default SMS App Contract](#1-default-sms-app-contract)
2. [Telephony.TextBasedSmsColumns — All Fields](#2-telephonytextbasedsmscolumns--all-fields)
3. [SMS Content Provider URIs](#3-sms-content-provider-uris)
4. [SmsManager — Sending](#4-smsmanager--sending)
5. [Delivery / Sent PendingIntent Result Codes](#5-delivery--sent-pendingintent-result-codes)
6. [MMS Content Provider](#6-mms-content-provider)
7. [Telephony.Threads — Thread ID Management](#7-telephonythreads--thread-id-management)
8. [BlockedNumberContract — Number Blocking](#8-blockednumbercontract--number-blocking)
9. [ContactsContract.PhoneLookup — Contact Photos](#9-contactscontractphonelookup--contact-photos)
10. [Permissions Reference](#10-permissions-reference)
11. [Postmark Gap Analysis — What Still Needs Doing](#11-postmark-gap-analysis--what-still-needs-doing)

---

## 1. Default SMS App Contract

**The single most important rule:**
> Beginning with API 19, if and only if an app is NOT the default SMS app, the system
> automatically writes sent messages to the SMS provider. The **default SMS app is ALWAYS
> responsible for writing its own sent messages**.

### What the default SMS app MUST do
1. Insert to `content://sms/sent` before or immediately after calling `sendTextMessage()`
2. Include at minimum: `address`, `body`, `date`, `type=MESSAGE_TYPE_SENT`, `read=1`, `status=STATUS_PENDING`
3. Include `thread_id` (via `Telephony.Threads.getOrCreateThreadId()`) to avoid Samsung/MIUI mis-grouping
4. On delivery confirmation: update `status` to `STATUS_COMPLETE` in `content://sms`
5. On send failure: update `status` to `STATUS_FAILED` in `content://sms`

### Roles / permission gates
- **API 29+**: Use `RoleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)`
- **API 26–28**: Use `Telephony.Sms.getDefaultSmsPackage()` + `ACTION_CHANGE_DEFAULT`
- `READ_SMS` and `READ_CONTACTS` can be requested without being default SMS app
- Default role is required to: send, delete, write to `content://sms`, use `BlockedNumberContract`

### Querying the current default
```kotlin
// API 19+
Telephony.Sms.getDefaultSmsPackage(context)  // returns package name or null
```

### Exempt apps that can read SMS without 3-hour delay
1. Default SMS app
2. Default Assistant app
3. Default Dialer app
4. Carrier apps, Connected device companion apps, System apps, Known signer apps
5. `SYSTEM_UI_INTELLIGENCE` role holder

---

## 2. Telephony.TextBasedSmsColumns — All Fields

These columns exist on every SMS table (`content://sms`, `content://sms/inbox`,
`content://sms/sent`, `content://sms/draft`, `content://sms/outbox`, etc.).

| Constant | Column key | Type | Notes |
|----------|------------|------|-------|
| `ADDRESS` | `"address"` | TEXT | Phone number of other party |
| `BODY` | `"body"` | TEXT | Message text |
| `DATE` | `"date"` | INTEGER (Long) | Timestamp received (ms since epoch) |
| `DATE_SENT` | `"date_sent"` | INTEGER (Long) | Timestamp when sent to SMSC |
| `TYPE` | `"type"` | INTEGER | See MESSAGE_TYPE_* constants below |
| `STATUS` | `"status"` | INTEGER | TP-Status; see STATUS_* constants below |
| `READ` | `"read"` | INTEGER (bool) | 1 = message has been read |
| `SEEN` | `"seen"` | INTEGER (bool) | 1 = notification shown; determines whether to show notif |
| `THREAD_ID` | `"thread_id"` | INTEGER | Links to Telephony.Threads |
| `SUBSCRIPTION_ID` | `"sub_id"` | INTEGER (Long) | SIM slot; < 0 if unknown (added API 22) |
| `LOCKED` | `"locked"` | INTEGER (bool) | Is message locked from deletion? |
| `ERROR_CODE` | `"error_code"` | INTEGER | Error on send/receive |
| `PERSON` | `"person"` | INTEGER | Contact ID reference |
| `PROTOCOL` | `"protocol"` | INTEGER | Protocol identifier code |
| `REPLY_PATH_PRESENT` | `"reply_path_present"` | BOOLEAN | TP-Reply-Path flag |
| `SEEN` | `"seen"` | INTEGER (bool) | Seen by user; controls notification display |
| `SERVICE_CENTER` | `"service_center"` | TEXT | SMSC address |
| `SUBJECT` | `"subject"` | TEXT | Subject (MMS-style; rarely set for SMS) |
| `TRANSACTION_ID` | `"tr_id"` | TEXT | App-defined opaque ID (API 37+); platform ignores content |
| `CREATOR` | `"creator"` | TEXT | Package name of sender; READ-ONLY, set by provider |

### MESSAGE_TYPE_* constants
| Constant | Int | Meaning |
|----------|-----|---------|
| `MESSAGE_TYPE_ALL` | 0 | All messages |
| `MESSAGE_TYPE_INBOX` | 1 | Received messages |
| `MESSAGE_TYPE_SENT` | 2 | Sent messages ← write this when inserting |
| `MESSAGE_TYPE_DRAFT` | 3 | Drafts |
| `MESSAGE_TYPE_OUTBOX` | 4 | In-progress outgoing |
| `MESSAGE_TYPE_FAILED` | 5 | Failed to send |
| `MESSAGE_TYPE_QUEUED` | 6 | Queued to send later |

### STATUS_* constants (TP-Status)
| Constant | Int | Meaning |
|----------|-----|---------|
| `STATUS_NONE` | -1 | No status received (use for received messages) |
| `STATUS_COMPLETE` | 0 | Delivery confirmed |
| `STATUS_PENDING` | 32 | Sent but no delivery report yet ← set this on insert |
| `STATUS_FAILED` | 64 | Delivery failed |

> ✅ **Fixed (May 3, 2026):** `SmsManagerWrapper` correctly inserts `STATUS_PENDING (32)` via
> `put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_PENDING)`. Doc was written before this was implemented.

---

## 3. SMS Content Provider URIs

| URI | Purpose |
|-----|---------|
| `content://sms` | All messages (all types) |
| `content://sms/inbox` | Received (TYPE=1) |
| `content://sms/sent` | Sent (TYPE=2) — **insert here after sending** |
| `content://sms/draft` | Drafts (TYPE=3) |
| `content://sms/outbox` | In-progress outgoing (TYPE=4) |
| `content://sms/failed` | Failed (TYPE=5) |
| `Telephony.Sms.CONTENT_URI` | Same as `content://sms`; preferred constant |

**Default sort:** `"date DESC"` (newest first)

### Samsung fallback (confirmed needed for S24 Ultra)
`content://sms` returns a null cursor despite permissions on Samsung OneUI.
Fallback queries:
1. `content://sms/inbox`
2. `content://sms/sent`
3. `content://sms/draft`
4. `content://sms/outbox`
5. `content://sms/failed`

---

## 4. SmsManager — Sending

### Getting an instance
```kotlin
// API 31+ — preferred (already used in SmsManagerWrapper ✅)
val smsManager = context.getSystemService(SmsManager::class.java)

// For a specific SIM slot (multi-SIM)
val smsManager = context.getSystemService(SmsManager::class.java)
    .createForSubscriptionId(subId)

// DEPRECATED — avoid:
SmsManager.getDefault()                           // deprecated API 31
SmsManager.getSmsManagerForSubscriptionId(subId)  // deprecated API 31
```

### Single message
```kotlin
smsManager.sendTextMessage(
    destinationAddress: String,   // recipient phone number
    scAddress: String?,           // SMSC — always pass null (use carrier default)
    text: String,
    sentIntent: PendingIntent?,   // fires after radio transmission attempt
    deliveryIntent: PendingIntent? // fires when recipient handset acknowledges
)

// API 30+ overload adds diagnostic messageId (does NOT affect behavior)
smsManager.sendTextMessage(destination, null, text, sentIntent, deliveryIntent, messageId: Long)
```

### Multipart message
```kotlin
// Step 1: split
val parts: ArrayList<String> = smsManager.divideMessage(text)

// Step 2: send
if (parts.size == 1) {
    smsManager.sendTextMessage(dest, null, text, sentIntent, deliveryIntent)
} else {
    smsManager.sendMultipartTextMessage(dest, null, parts, sentIntents, deliveryIntents)
    // sentIntents/deliveryIntents = one PendingIntent per part
    // Each part fires its own sentIntent independently
}
```

**Content provider note:** Insert ONE row to `content://sms/sent` for the full combined message.
Fragmentation is transparent to the user and content provider.

### What sendTextMessage does NOT do
- Does NOT write to the SMS content provider (when you are default SMS app)
- Does NOT retry on failure
- Does NOT guarantee delivery — only guarantees radio PDU acceptance
- Does NOT handle thread ID assignment

### ContentValues to insert into content://sms/sent
```kotlin
val values = ContentValues().apply {
    put(Telephony.Sms.ADDRESS, destinationAddress)
    put(Telephony.Sms.BODY, text)
    put(Telephony.Sms.DATE, System.currentTimeMillis())
    put(Telephony.Sms.DATE_SENT, System.currentTimeMillis())   // ← add this
    put(Telephony.Sms.READ, 1)
    put(Telephony.Sms.SEEN, 1)                                  // ← add this
    put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
    put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_PENDING)     // ← 32, not -1
    put(Telephony.Sms.THREAD_ID,
        Telephony.Threads.getOrCreateThreadId(context, destinationAddress)) // ← add this
}
val insertUri = contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
// Parse row ID from insertUri for use in sentIntent/deliveredIntent extras
```

### sendTextMessageWithoutPersisting
```kotlin
fun sendTextMessageWithoutPersisting(destinationAddress, scAddress, text, sentIntent, deliveryIntent)
```
- Requires `SEND_SMS` + `MODIFY_PHONE_STATE` permissions (or carrier privileges)
- Sends over radio with NO content provider interaction whatsoever
- **Not for Postmark** — requires system-level permissions we don't hold

### Multi-SIM note
Using `getDefault()` on a multi-SIM device without a default SIM set will either:
- Show a disambiguation dialog (foreground only, API ≤ 28)
- Return `RESULT_ERROR_GENERIC_FAILURE` + extra `"noDefault" = true` (API > 28)

`SmsManager.getDefaultSmsSubscriptionId()` vs `SubscriptionManager.getDefaultSmsSubscriptionId()`:
- **SmsManager's version**: if only one active SIM, returns that SIM's ID even when no default is explicitly set
- **SubscriptionManager's version**: returns `INVALID_SUBSCRIPTION_ID` if no default is set

Prefer `SmsManager.getDefaultSmsSubscriptionId()` — it's more forgiving on single-SIM devices.
Use `createForSubscriptionId(SmsManager.getDefaultSmsSubscriptionId())` for robustness on all devices.

---

## 5. Delivery / Sent PendingIntent Result Codes

The `sentIntent` broadcast result code after `sendTextMessage()`:

| Code | Int | Meaning |
|------|-----|---------|
| `RESULT_OK` | 0 | Successfully transmitted over radio |
| `RESULT_ERROR_GENERIC_FAILURE` | 1 | Generic failure |
| `RESULT_ERROR_RADIO_OFF` | 2 | Airplane mode |
| `RESULT_ERROR_NULL_PDU` | 3 | Internal PDU error |
| `RESULT_ERROR_NO_SERVICE` | 4 | No network service |
| `RESULT_ERROR_LIMIT_EXCEEDED` | 5 | Send queue full |
| `RESULT_ERROR_FDN_CHECK_FAILURE` | 6 | Fixed dialing numbers blocked it |
| `RESULT_ERROR_SHORT_CODE_NOT_ALLOWED` | 7 | User denied premium shortcode |
| `RESULT_RADIO_NOT_AVAILABLE` | 9 | Radio not started / resetting |
| `RESULT_NETWORK_REJECT` | 10 | Network rejected the SMS |
| `RESULT_NO_MEMORY` | 13 | No memory |
| `RESULT_MODEM_ERROR` | 16 | Modem error |
| `RESULT_NETWORK_ERROR` | 17 | Network error |
| `RESULT_SMS_BLOCKED_DURING_EMERGENCY` | 29 | Emergency call in progress |
| `RESULT_NO_DEFAULT_SMS_APP` | 32 | No default SMS app (MMS only) |
| `RESULT_RIL_RADIO_NOT_AVAILABLE` | 100 | RIL radio not started |
| `RESULT_RIL_SMS_SEND_FAIL_RETRY` | 101 | RIL failed, **retry advised** |
| `RESULT_RIL_NETWORK_REJECT` | 102 | Network rejected at RIL level |

**In SmsSentDeliveryReceiver:**
- `RESULT_OK` → update Room DeliveryStatus to SENT / DELIVERED; update `content://sms` status to `STATUS_COMPLETE`
- `RESULT_RIL_SMS_SEND_FAIL_RETRY` → retry advised (Postmark does not yet retry)
- Anything else → update Room DeliveryStatus to FAILED; update `content://sms` status to `STATUS_FAILED`

---

## 6. MMS Content Provider

### Top-level MMS table
```
content://mms              ← Telephony.Mms.CONTENT_URI
content://mms/inbox
content://mms/sent
content://mms/draft
content://mms/outbox
```
Default sort: `"date DESC"`

### MMS Parts table — `Telephony.Mms.Part`
Each MMS message can have multiple parts (text body, images, video, audio, SMIL).

**CONTENT_URI** (API 29+): `Telephony.Mms.Part.CONTENT_URI`
**Per-message parts** (manual construction, pre-API 29): `content://mms/{messageId}/part`
**API 30+**: `Telephony.Mms.Part.getPartUriForMessage(messageId: String): Uri`

> ⚠️ **Part ID reuse:** The ID of a deleted part must NOT be reused.

| Constant | Column key | Type | Notes |
|----------|------------|------|-------|
| `MSG_ID` | `"mid"` | INTEGER | Foreign key → MMS message `_id` |
| `SEQ` | `"seq"` | INTEGER | Part order |
| `CONTENT_TYPE` | `"ct"` | TEXT | MIME type: `"text/plain"`, `"image/jpeg"`, `"video/mp4"`, `"audio/mpeg"`, `"application/smil"` |
| `NAME` | `"name"` | TEXT | File name of part |
| `FILENAME` | `"fn"` | TEXT | Alternative filename |
| `CHARSET` | `"chset"` | TEXT | Character set for text parts |
| `TEXT` | `"text"` | TEXT | Text content (for text/plain parts) |
| `_DATA` | `"_data"` | TEXT | Filesystem path for binary parts (internal; use Uri instead) |
| `CONTENT_ID` | `"cid"` | INTEGER | Content-ID header |
| `CONTENT_LOCATION` | `"cl"` | INTEGER | Content-Location header |
| `CONTENT_DISPOSITION` | `"cd"` | TEXT | Content-Disposition |

### Reading MMS parts (Postmark pattern)
```kotlin
// Query parts for a given MMS message ID
val partUri = Uri.parse("content://mms/$mmsId/part")
val cursor = contentResolver.query(
    partUri,
    arrayOf("_id", "ct", "text"),  // Telephony.Mms.Part._ID, CONTENT_TYPE, TEXT
    null, null, null
)
cursor?.use {
    while (it.moveToNext()) {
        val partId = it.getLong(0)
        val contentType = it.getString(1)
        val text = it.getString(2)

        when {
            contentType == "text/plain" -> // use text field as body
            contentType.startsWith("image/") ->
                // URI = "content://mms/part/$partId"
            contentType.startsWith("video/") ->
                // URI = "content://mms/part/$partId"
            contentType.startsWith("audio/") ->
                // URI = "content://mms/part/$partId"
            contentType == "application/smil" -> // skip SMIL layout descriptor
        }
    }
}
```

### MMS Addr table — multi-recipient threads
`Telephony.Mms.Addr` contains recipient addresses for each MMS message.
- `address` — the phone number
- `type` — 151 = FROM, 137 = TO, 130 = CC, 129 = BCC
- `msg_id` — foreign key to the MMS message
Use this to build group MMS threads where a single message has multiple TO addresses.

---

## 7. Telephony.Threads — Thread ID Management

Thread IDs are determined by participants. A deleted thread's ID must **never** be reused.

### Key methods
```kotlin
// Single recipient (SMS)
val threadId: Long = Telephony.Threads.getOrCreateThreadId(context, address)

// Multiple recipients (group MMS)
val threadId: Long = Telephony.Threads.getOrCreateThreadId(context, recipientSet)
```

- If a thread already exists for those participants, returns existing ID
- If no thread exists, creates one and returns its new ID
- **Always use this** when inserting to `content://sms/sent` — especially on Samsung/MIUI

### Thread types
| Constant | Int | Meaning |
|----------|-----|---------|
| `COMMON_THREAD` | 0 | Standard 1-to-1 SMS/MMS thread |
| `BROADCAST_THREAD` | 1 | Broadcast (send-only group) |

### URIs
- `Telephony.Threads.CONTENT_URI` — by conversation
- `Telephony.Threads.OBSOLETE_THREADS_URI` — obsolete threads

---

## 8. BlockedNumberContract — Number Blocking

**API 24+.** Only accessible by: default SMS app, default dialer app, carrier apps.

### URIs / columns
```kotlin
BlockedNumberContract.BlockedNumbers.CONTENT_URI         // main table
BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER  // required on insert
BlockedNumberContract.BlockedNumbers.COLUMN_E164_NUMBER   // optional; provider auto-populates
BlockedNumberContract.BlockedNumbers.COLUMN_ID            // _id
```

### Operations
```kotlin
// Block a number
val values = ContentValues().apply {
    put(BlockedNumbers.COLUMN_ORIGINAL_NUMBER, "5551234567")
    // Optionally: put(BlockedNumbers.COLUMN_E164_NUMBER, "+15551234567")
}
val uri = contentResolver.insert(BlockedNumbers.CONTENT_URI, values)

// Unblock
BlockedNumberContract.unblock(context, "5551234567")

// Check if blocked
val isBlocked = BlockedNumberContract.isBlocked(context, "5551234567")

// Query all blocked numbers
val cursor = contentResolver.query(
    BlockedNumbers.CONTENT_URI,
    arrayOf(BlockedNumbers.COLUMN_ID, BlockedNumbers.COLUMN_ORIGINAL_NUMBER,
            BlockedNumbers.COLUMN_E164_NUMBER),
    null, null, null
)
```

**Updates are NOT supported** — delete then re-insert to change a blocked number.

### Platform behavior
- Platform silently discards calls and messages from blocked numbers
- Emergency numbers are never blocked
- Blocking is disabled for a carrier-defined duration after an emergency call

### Postmark implementation plan
```kotlin
// In SmsReceiver — check before posting notification
if (BlockedNumberContract.isBlocked(context, senderAddress)) return

// In thread ⋮ menu "Block number"
// Insert to BlockedNumbers.CONTENT_URI
// Set ThreadEntity.notificationsEnabled = false as well
// Do NOT delete thread — move to "Blocked" folder (isBlocked flag on ThreadEntity)
```

---

## 9. ContactsContract.PhoneLookup — Contact Photos

### Basic lookup pattern
```kotlin
val lookupUri = Uri.withAppendedPath(
    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
    Uri.encode(phoneNumber)
)
val cursor = contentResolver.query(
    lookupUri,
    arrayOf(
        ContactsContract.PhoneLookup.DISPLAY_NAME,
        ContactsContract.PhoneLookup.PHOTO_URI,
        ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI,
        ContactsContract.PhoneLookup.LOOKUP_KEY,
        ContactsContract.PhoneLookup._ID
    ),
    null, null, null
)
```

### Available columns from PhoneLookup
| Column | Source | Notes |
|--------|--------|-------|
| `DISPLAY_NAME` | ContactsColumns | Contact's display name |
| `PHOTO_URI` | ContactsColumns | Full-size photo URI (load with Coil) |
| `PHOTO_THUMBNAIL_URI` | ContactsColumns | Thumbnail URI (use in avatars) |
| `PHOTO_ID` | ContactsColumns | Photo ID (use with `ContactsContract.Contacts.openContactPhotoInputStream`) |
| `LOOKUP_KEY` | ContactsColumns | Stable key for `ContactsContract.QuickContact.showQuickContact()` |
| `_ID` | BaseColumns | Contact ID |
| `NUMBER` | PhoneLookupColumns | The matched phone number |
| `TYPE` | PhoneLookupColumns | Phone number type (mobile/home/work) |

### Open contact card (quick contact)
```kotlin
// Show system contact popover
ContactsContract.QuickContact.showQuickContact(
    context, anchorView, contactUri, QuickContact.MODE_LARGE, null
)

// Open full contact editor
val intent = Intent(Intent.ACTION_EDIT, contactUri)
context.startActivity(intent)

// Open view-only contact screen
val intent = Intent(Intent.ACTION_VIEW, contactUri)
context.startActivity(intent)
```

### Adding a new contact from unknown number
```kotlin
val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
    type = ContactsContract.RawContacts.CONTENT_TYPE
    putExtra(ContactsContract.Intents.Insert.PHONE, phoneNumber)
}
context.startActivity(intent)
```

### Loading photo with Coil (in Compose)
```kotlin
// Use PHOTO_THUMBNAIL_URI from PhoneLookup for avatars
AsyncImage(
    model = ImageRequest.Builder(LocalContext.current)
        .data(photoThumbnailUri)
        .crossfade(true)
        .build(),
    contentDescription = displayName,
    error = painterResource(R.drawable.ic_letter_avatar_fallback)
)
```

---

## 10. Permissions Reference

| Permission | When needed | Min API |
|------------|-------------|---------|
| `READ_SMS` | Read SMS content provider | 1 |
| `SEND_SMS` | Send messages | 1 |
| `RECEIVE_SMS` | Receive SMS via broadcast | 1 |
| `RECEIVE_MMS` | Receive MMS via broadcast | 1 |
| `WRITE_SMS` | Write to SMS content provider | 1 (required for default SMS) |
| `READ_CONTACTS` | Read ContactsContract | 1 |
| `WRITE_CONTACTS` | Create/edit contacts | 1 |
| `POST_NOTIFICATIONS` | Show notifications | 33 |
| `READ_MEDIA_IMAGES` | Pick images for MMS attach | 33 |
| `CAMERA` | Take photos for MMS | 1 |
| `RECEIVE_BOOT_COMPLETED` | Restart WorkManager after reboot | 1 |

### Manifest queries block (required API 30+ to detect default SMS app)
```xml
<queries>
    <intent>
        <action android:name="android.provider.Telephony.SMS_DELIVER"/>
    </intent>
</queries>
```

---

## 11. Postmark Gap Analysis — What Still Needs Doing

Cross-referenced against the project BRIEFING.md and TODO.md. Items are ordered by tier.
Last audited: May 3, 2026.

### 🔴 TIER 1 — Critical

#### Multipart SMS delivery verification
- Current gap: `sendMultipartTextMessage()` fires one `sentIntent` per part; we mark
  the whole message delivered when any part arrives.
- Fix: Track part count; only mark DELIVERED after all parts' sentIntents return `RESULT_OK`.
- `deliveryIntents` array — typically only set for the last part; verify carrier behavior.

#### Send queue (offline resilience)
- No queue exists. If radio is off, `sendTextMessage()` returns `RESULT_ERROR_RADIO_OFF`.
- Required: store message with `type=MESSAGE_TYPE_QUEUED` in `content://sms/outbox`,
  register a `BroadcastReceiver` for `ConnectivityManager.CONNECTIVITY_ACTION`,
  retry on reconnect. Update bubble state to "Queued".

#### ~~STATUS_PENDING correction~~ ✅ DONE (May 3, 2026)
- ~~Current code sets `STATUS_NONE (-1)` at insert. Should be `STATUS_PENDING (32)`.~~
- `SmsManagerWrapper` correctly sets `STATUS_PENDING (32)`. Closed.

### 🟡 TIER 2 — Feature Complete

#### Block number (stub → real)
- Implementation: `BlockedNumberContract` insert as documented in §8 above.
- Also: check `BlockedNumberContract.isBlocked()` in `SmsReceiver` before posting notification.
- Also: add `isBlocked BOOLEAN DEFAULT 0` to `ThreadEntity`; filter blocked threads to separate folder.

#### Contact photo in avatars
- Use `ContactsContract.PhoneLookup.CONTENT_FILTER_URI` query (§9 above) to get `PHOTO_THUMBNAIL_URI`.
- Load with Coil `AsyncImage`. Fall back to `LetterAvatar` when null.
- Cache per-thread: store `photoUri: String?` on `ThreadEntity` — update during sync when contact name changes.

#### Group MMS
- Use `Telephony.Mms.Addr` table to get all participants for a message.
- `Telephony.Threads.getOrCreateThreadId(context, recipientSet: Set<String>)` handles multi-recipient thread IDs.
- `displayName` in `ThreadEntity` becomes comma-joined names.
- Per-bubble sender display requires `address` column from `Mms.Addr` type=FROM.

#### MMS fallback coverage
- `syncAllMms()` in `FirstLaunchSyncWorker` currently falls back to `content://mms/inbox` +
  `content://mms/sent` only. The SMS fallback correctly covers inbox / sent / draft / outbox / failed.
- Add `content://mms/draft` and `content://mms/outbox` to the MMS fallback list for symmetry.
  Draft/outbox MMS are rare but can exist after a send failure.

#### Tap contact name → contact viewer
- `ContactsContract.QuickContact.showQuickContact()` or `Intent(ACTION_VIEW, contactUri)`.
- Edit: `Intent(ACTION_EDIT, contactUri)`.

#### Save number prompt (unknown numbers)
- Detect: `PhoneLookup` query returns no rows → number is unsaved.
- Show banner above thread: "Add [number] to contacts" → `ContactsContract.Intents.Insert.ACTION`.

#### MMS media playback
- Image full-screen: `Dialog` composable with `SubcomposeAsyncImage` + pinch-to-zoom (`TransformableState`).
- Video: `AndroidView { ExoPlayer }` inside a `Dialog`. Use `androidx.media3:media3-exoplayer`.
- Audio: `MediaPlayer` or ExoPlayer, play/pause toggle on the audio chip.

#### Rich media in reply bar
- Attachment picker: `ActivityResultContracts.PickVisualMedia()` (Photo Picker API, API 33+ preferred).
- Camera: `ActivityResultContracts.TakePicture()`.
- Permissions: `READ_MEDIA_IMAGES` (API 33+) / `READ_EXTERNAL_STORAGE` (API ≤ 32).

### 🟢 TIER 3 — Polish

#### ~~Delivery timestamps in content://sms~~ ✅ DONE (May 3, 2026)
- ~~`SmsSentDeliveryReceiver` currently updates Room `DeliveryStatus` but does NOT update `content://sms`.~~
- `SmsSentDeliveryReceiver` writes `STATUS_FAILED` on send failure and `STATUS_COMPLETE` on delivery
  receipt using `ContentResolver.update()` on the matching `content://sms` row. Closed.

#### ~~DATE_SENT / SEEN fields~~ ✅ DONE (May 3, 2026)
- ~~Currently not written on `content://sms/sent` insert.~~
- `SmsManagerWrapper` writes both `DATE_SENT` and `SEEN = 1` at insert time. Closed.

#### Multi-SIM robustness
- Current: `context.getSystemService(SmsManager::class.java)` with no sub ID.
- Improvement: `createForSubscriptionId(SmsManager.getDefaultSmsSubscriptionId())`.

#### LOCKED column use
- `TextBasedSmsColumns.LOCKED` exists in the content provider.
- When implementing Postmark's "locked message" feature (Tier 3 in TODO.md),
  mirror the `isLocked` flag to this column so system backup tools respect it.

#### TRANSACTION_ID column (API 37+)
- New opaque column `"tr_id"` — platform ignores content.
- Could be used to store our internal Room message ID for cross-correlation.

---

---

## Quick Reference — Default SMS App Checklist

```
sendTextMessage() / sendMultipartTextMessage()
  → ONLY transmits over radio
  → Does NOT touch the content provider (when you are the default SMS app)
  → sentIntent fires  = radio accepted the PDU (not delivered yet)
  → deliveryIntent fires = recipient handset acknowledged (delivery report)

As default SMS app, you MUST:
  1. Insert to content://sms/sent before (or immediately after) sendTextMessage()
  2. Include: address, body, date, type=2, read=1, status=32 (STATUS_PENDING)
  3. Include: thread_id via Telephony.Threads.getOrCreateThreadId()
  4. Include: date_sent, seen=1
  5. sentIntent RESULT_OK    → update content://sms STATUS to STATUS_COMPLETE (0)
  6. sentIntent failure code → update content://sms STATUS to STATUS_FAILED (64)
  7. deliveryIntent fires    → update Room DeliveryStatus to DELIVERED
```

---

## API Reference Links

| Resource | URL |
|----------|-----|
| `Telephony.Sms` | https://developer.android.com/reference/android/provider/Telephony.Sms |
| `Telephony.TextBasedSmsColumns` | https://developer.android.com/reference/android/provider/Telephony.TextBasedSmsColumns |
| `Telephony.Mms` | https://developer.android.com/reference/android/provider/Telephony.Mms |
| `Telephony.Mms.Part` | https://developer.android.com/reference/android/provider/Telephony.Mms.Part |
| `Telephony.Mms.Addr` | https://developer.android.com/reference/android/provider/Telephony.Mms.Addr |
| `Telephony.Threads` | https://developer.android.com/reference/android/provider/Telephony.Threads |
| `BlockedNumberContract` | https://developer.android.com/reference/android/provider/BlockedNumberContract |
| `ContactsContract.PhoneLookup` | https://developer.android.com/reference/android/provider/ContactsContract.PhoneLookup |
| `SmsManager` | https://developer.android.com/reference/kotlin/android/telephony/SmsManager |
| Existing SMS deep-dive | docs/SMS_RESEARCH.md |
