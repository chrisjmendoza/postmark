# Android SmsManager Deep Research
> Sourced from: https://developer.android.com/reference/kotlin/android/telephony/SmsManager
> Researched: 2025-05-02

---

## 1. The Critical Rule (Default SMS App Contract)

The single most important thing to understand:

> **"Beginning with Android 4.4 (API level 19), if and only if an app is NOT selected as the
> default SMS app, the system automatically writes messages sent using this method to the SMS
> Provider. The DEFAULT SMS app is ALWAYS responsible for writing its sent messages to the SMS
> Provider."**

This applies to both `sendTextMessage()` and `sendMultipartTextMessage()`.

**What this means for Postmark:** We must manually call `ContentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, ...)` before or after transmitting. ✅ We already do this in `SmsManagerWrapper.kt`.

---

## 2. Getting an SmsManager Instance

### Modern API (use this)
```kotlin
// API 31+ — preferred
val smsManager = context.getSystemService(SmsManager::class.java)

// For a specific SIM slot (multi-SIM devices)
val smsManager = context.getSystemService(SmsManager::class.java)
    .createForSubscriptionId(subId)
```

### Deprecated (avoid)
```kotlin
SmsManager.getDefault()                          // deprecated API 31
SmsManager.getSmsManagerForSubscriptionId(subId) // deprecated API 31
```

**Current code:** `SmsManagerWrapper` uses `context.getSystemService(SmsManager::class.java)` — **correct**. ✅

### Multi-SIM note
Using `getDefault()` on a multi-SIM device where no default is set will either:
- Show a disambiguation dialog (foreground only, API ≤ 28 sends anyway on first SIM)
- Fail with `RESULT_ERROR_GENERIC_FAILURE` + extra `"noDefault" = true` (API > 28)
Using `createForSubscriptionId()` avoids this entirely.

---

## 3. sendTextMessage

```kotlin
fun sendTextMessage(
    destinationAddress: String,   // recipient phone number
    scAddress: String?,           // SMSC address, null = use device default
    text: String,                 // message body
    sentIntent: PendingIntent?,   // fires after radio transmission attempt
    deliveryIntent: PendingIntent? // fires when delivered to recipient
)

// API 30+ overload — adds messageId for logging only
fun sendTextMessage(
    destinationAddress: String,
    scAddress: String?,
    text: String,
    sentIntent: PendingIntent?,
    deliveryIntent: PendingIntent?,
    messageId: Long               // our internal ID — used for logging/diagnostics only, can be 0
)
```

**`messageId` (API 30+):** This is just a diagnostic ID passed to the telephony framework for
logging. It does NOT affect any functional behavior. We are NOT currently using this overload but
could pass our internal Room message ID to help correlate telephony logs with our messages.

**`scAddress`:** Always pass `null` to use the carrier's default SMSC. Only non-null for special
carrier configurations.

---

## 4. sentIntent — Result Codes

The `sentIntent` `PendingIntent` is broadcast after the SMS is handed to the radio. Its result
code (`Activity.resultCode`) will be one of:

| Code | Meaning |
|------|---------|
| `Activity.RESULT_OK` (0) | Successfully transmitted over radio |
| `RESULT_ERROR_GENERIC_FAILURE` (1) | Generic failure |
| `RESULT_ERROR_RADIO_OFF` (2) | Airplane mode |
| `RESULT_ERROR_NULL_PDU` (3) | Internal PDU error |
| `RESULT_ERROR_NO_SERVICE` (4) | No network service |
| `RESULT_ERROR_LIMIT_EXCEEDED` (5) | Send queue full |
| `RESULT_ERROR_FDN_CHECK_FAILURE` (6) | Fixed dialing numbers blocked it |
| `RESULT_ERROR_SHORT_CODE_NOT_ALLOWED` (7) | User denied premium shortcode |
| `RESULT_RADIO_NOT_AVAILABLE` (9) | Radio not started / resetting |
| `RESULT_NETWORK_REJECT` (10) | Network rejected the SMS |
| `RESULT_NO_MEMORY` (13) | No memory |
| `RESULT_MODEM_ERROR` (16) | Modem error |
| `RESULT_NETWORK_ERROR` (17) | Network error |
| `RESULT_SMS_BLOCKED_DURING_EMERGENCY` (29) | Emergency call in progress |
| `RESULT_NO_DEFAULT_SMS_APP` (32) | No default SMS app set (MMS only) |
| `RESULT_RIL_RADIO_NOT_AVAILABLE` (100) | RIL radio not started |
| `RESULT_RIL_SMS_SEND_FAIL_RETRY` (101) | RIL failed, retry advised |
| `RESULT_RIL_NETWORK_REJECT` (102) | Network rejected at RIL level |
| ... many more RIL-level codes ... | |

**For Postmark:** In `SmsSentDeliveryReceiver`, the action `ACTION_SMS_SENT` checks `resultCode`.
- `Activity.RESULT_OK` → mark message delivered/sent
- Anything else → mark as `DeliveryStatus.FAILED`

---

## 5. deliveryIntent — Delivery Report

The `deliveryIntent` fires when the recipient's handset acknowledges receipt (delivery report /
status report). This requires the carrier and recipient to support delivery reports.

- Only fires if the network returns a status report PDU
- Raw PDU is in the intent's extended data as `"pdu"` (ByteArray)
- Not guaranteed to fire on all carriers

**For Postmark:** `SmsSentDeliveryReceiver.ACTION_SMS_DELIVERED` handles this — updates
`DeliveryStatus.DELIVERED`.

---

## 6. divideMessage + sendMultipartTextMessage

```kotlin
// Step 1: split the text
val parts: ArrayList<String> = smsManager.divideMessage(text)

// Step 2: send
if (parts.size == 1) {
    smsManager.sendTextMessage(destination, null, text, sentIntent, deliveredIntent)
} else {
    smsManager.sendMultipartTextMessage(destination, null, parts, sentIntents, deliveryIntents)
}
```

**For multipart:**
- `sentIntents` — one `PendingIntent` per part
- `deliveryIntents` — one per part (nullable; typically only set for the last part)
- Each part fires its own `sentIntent` independently when that part is transmitted

**Content provider for multipart:** Insert ONE row to `content://sms/sent` for the full combined
message. The fragmentation is transparent to the user and the provider. ✅ Current code does this.

---

## 7. Content Provider Fields for content://sms/sent

These are the fields to include in `ContentValues` when writing to `Telephony.Sms.Sent.CONTENT_URI`:

| Field constant | String key | Type | Notes |
|---------------|-----------|------|-------|
| `Telephony.Sms.ADDRESS` | `"address"` | String | Recipient phone number |
| `Telephony.Sms.BODY` | `"body"` | String | Message text |
| `Telephony.Sms.DATE` | `"date"` | Long | Timestamp when sent (ms since epoch) |
| `Telephony.Sms.DATE_SENT` | `"date_sent"` | Long | Timestamp when sent to SMSC (can match `date`) |
| `Telephony.Sms.READ` | `"read"` | Int | 1 = read (sent msgs should always be 1) |
| `Telephony.Sms.TYPE` | `"type"` | Int | `Telephony.Sms.MESSAGE_TYPE_SENT` = 2 |
| `Telephony.Sms.STATUS` | `"status"` | Int | See status values below |
| `Telephony.Sms.THREAD_ID` | `"thread_id"` | Long | Android derives from address if omitted |
| `Telephony.Sms.SUBSCRIPTION_ID` | `"sub_id"` | Int | SIM slot; omit to use default |
| `Telephony.Sms.SEEN` | `"seen"` | Int | 1 = seen in conversation (set to 1) |

### STATUS values (Telephony.Sms)
| Constant | Int value | Meaning |
|----------|-----------|---------|
| `STATUS_PENDING` | -1 | Sent but delivery report not yet received |
| `STATUS_COMPLETE` | 0 | Delivery confirmed |
| `STATUS_FAILED` | 64 | Delivery failed |

**Current code sets `STATUS_PENDING` at insert time** — correct initial value. When `deliveryIntent` fires, update to `STATUS_COMPLETE`. When `sentIntent` returns a failure code, update to `STATUS_FAILED`.

### thread_id
- Android's telephony provider auto-assigns `thread_id` from the `address` field if you don't provide it. This works on stock Android.
- On Samsung/MIUI devices, omitting `thread_id` can occasionally result in wrong thread grouping. Safer to look it up first:
  ```kotlin
  // Lookup or create a thread_id for an address
  val threadId = Telephony.Threads.getOrCreateThreadId(context, address)
  values.put(Telephony.Sms.THREAD_ID, threadId)
  ```
- `Telephony.Threads.getOrCreateThreadId()` is the official API for this.

---

## 8. What sendTextMessage Does NOT Do

- **Does NOT write to the SMS content provider** (when you are the default SMS app)
- **Does NOT update any existing database row**
- **Does NOT handle thread ID assignment**
- **Does NOT retry on failure** (RESULT_RIL_SMS_SEND_FAIL_RETRY means the RIL advises retry, but
  the app must implement retry logic)
- **Does NOT guarantee delivery** — only guarantees radio transmission

---

## 9. sendTextMessageWithoutPersisting

```kotlin
fun sendTextMessageWithoutPersisting(destinationAddress, scAddress, text, sentIntent, deliveryIntent)
```

- Requires `SEND_SMS` + `MODIFY_PHONE_STATE` permissions (or carrier privileges)
- Sends over radio without ANY content provider interaction
- **Not for us** — requires system-level permissions we don't have

---

## 10. Subscription Management (Multi-SIM)

For Postmark to correctly handle multi-SIM devices:

```kotlin
// Get the default SMS subscription ID
val subId = SubscriptionManager.getDefaultSmsSubscriptionId()

// Get an SmsManager for that subscription
val smsManager = context.getSystemService(SmsManager::class.java)
    .createForSubscriptionId(subId)
```

`SmsManager.getDefaultSmsSubscriptionId()` differs from `SubscriptionManager.getDefaultSmsSubscriptionId()`:
- If only one active SIM, SmsManager's version returns that SIM's ID even if no default is set
- `SubscriptionManager`'s version returns `INVALID_SUBSCRIPTION_ID` if no default set

**Current code** uses `context.getSystemService(SmsManager::class.java)` without subscription
specification — fine for single-SIM, but on multi-SIM devices without a default set, may fail.
For future-proofing: wrap with `createForSubscriptionId(SmsManager.getDefaultSmsSubscriptionId())`.

---

## 11. Current SmsManagerWrapper — Gap Analysis

### What's correct ✅
- Uses modern `context.getSystemService(SmsManager::class.java)`
- Inserts to `content://sms/sent` before radio send
- Uses correct fields: `ADDRESS`, `BODY`, `DATE`, `TYPE`, `READ`, `STATUS`
- Handles multipart with `divideMessage()` → `sendMultipartTextMessage()`
- PendingIntents use `FLAG_IMMUTABLE | FLAG_ONE_SHOT`

### Potential improvements to consider
1. **`thread_id`** — Not currently set on insert. Should call
   `Telephony.Threads.getOrCreateThreadId(context, destinationAddress)` and include it.
   Risk: rare Samsung/MIUI mis-grouping if omitted.

2. **`date_sent`** — Not currently set. Low impact, but good practice.

3. **`seen`** — Not set. Should be 1 for sent messages.

4. **Status update after send** — Currently `STATUS_PENDING` is set at insert and never updated
   in the content provider. `SmsSentDeliveryReceiver` updates Room's `DeliveryStatus` but does NOT
   update `content://sms`. Other apps (Google Messages) won't see delivery confirmation.
   Fix: In `SmsSentDeliveryReceiver`, also call `ContentResolver.update()` on the matching row.

5. **API 30+ `messageId` overload** — Could pass our Room message ID to `sendTextMessage()` for
   better telephony-side logging.

6. **Multi-SIM** — Use `createForSubscriptionId()` for robustness on dual-SIM phones.

---

## 12. Key Takeaway Summary

```
sendTextMessage() / sendMultipartTextMessage()
  → ONLY transmits over radio
  → Does NOT touch the content provider (when you are default SMS app)
  → sentIntent fires = radio accepted the PDU (not delivered yet)
  → deliveryIntent fires = recipient handset acknowledged (delivery report)

As default SMS app, you MUST:
  1. Insert to content://sms/sent before (or immediately after) calling sendTextMessage
  2. Include: address, body, date, type=2, read=1, status=STATUS_PENDING
  3. Optionally include: thread_id (via Telephony.Threads.getOrCreateThreadId), date_sent, seen=1
  4. On delivery confirmation: update status to STATUS_COMPLETE in content provider
  5. On send failure: update status to STATUS_FAILED in content provider
```
