> **DRAFT — not published. Requires owner review and a public hosting URL before Play submission.**
> Every factual claim below was checked against the current codebase (`app/src/main/AndroidManifest.xml`,
> `app/build.gradle.kts`, `gradle/libs.versions.toml`, `.github/workflows/distribute.yml`) on 2026-07-24.
> Items that need a human decision, not a code check, are marked **[OWNER CONFIRM]**.

---

# Privacy Policy — Postmark

**Effective date:** [OWNER CONFIRM — date of first publication]
**Contact:** chrisjmendoza@gmail.com

Postmark is an SMS/MMS messaging app for Android. This policy explains what
data the app touches, where it goes, and what you can do about it.

The short version: **Postmark keeps your messages on your phone. Period.**
There is no server. There is no account. Nothing you type, send, or receive
through Postmark is transmitted anywhere by us, because Postmark has no way
to transmit it — the app does not request Android's `INTERNET` permission,
so it is not technically capable of making a network call, not merely
"configured" not to.

---

## 1. Data Postmark collects

**None, in the sense of "collects and stores somewhere other than your
device."** Postmark does not operate a backend, does not use any analytics
SDK, does not use any crash-reporting SDK, and does not show ads. There is
no Firebase Analytics, Crashlytics, or any comparable library compiled into
the app — checked directly against the app's dependency list
(`app/build.gradle.kts`, `gradle/libs.versions.toml`).

What Postmark *does* read and store, entirely on your device:

| Data | Why | Where it lives |
|---|---|---|
| SMS/MMS messages (text, timestamps, attachments) | This is a messaging app — it needs the messages to show them to you | Android's own SMS/MMS content provider (`content://sms`, `content://mms`), which Postmark copies into its own local database (Room, SQLite) so search, stats, and offline browsing work |
| Contact names and photos | So a thread shows "Mom" instead of a phone number | Read live from Android's Contacts provider each time a name is needed; not copied into a separate Postmark contacts store |
| Voice memos you record in the app | To attach and send them as MMS | A local file (`filesDir/voice_memo_*.m4a`) on your device, deleted when the message is sent or discarded |
| Backup archives (optional) | Manual or scheduled backup of your message history | A zip file written to `Android/data/com.plusorminustwo.postmark/files/backups/` by default, or a folder you pick yourself via Android's Storage Access Framework — see §3 |
| App preferences (theme, notification settings, per-contact colors, etc.) | Remembering your settings | Local `SharedPreferences` / Room, on-device only |

None of the above ever leaves the device as part of using the app. There is
no "phone home," no telemetry ping, no update-check network call from
Postmark's own code.

---

## 2. Data Postmark shares

**None.** There is no third party for Postmark to share data with, because
there is no network channel out of the app. Specifically:

- No ad networks (Postmark shows no ads).
- No analytics vendors.
- No crash-reporting vendors.
- No cloud sync provider.

**One clarification about Firebase, because it does appear in this
project's tooling and an honest policy should say so plainly:** the
project's GitHub Actions CI pipeline uses **Firebase App Distribution** to
push test builds to the developer's own phone during development. That is
a build-and-distribute tool that runs on GitHub's servers, driven by the
`firebase-tools` CLI, uploading the compiled APK file after it's built —
it is not a software development kit inside the app, and no user's message
data ever touches it. A production Play Store release is built and
uploaded through the Play Console, not this pipeline. **[OWNER CONFIRM]**
that this remains true for the Play-published build (i.e., the same
`assembleRelease`/Play App Signing path, no Firebase App Distribution step
in the release build variant).

---

## 3. Backups — the one place data leaves "the app" (but not the device, unless you choose)

Postmark can create a backup archive of your messages, either on a
schedule (daily/weekly/monthly, configurable) or on demand. By default the
backup file is written to app-private external storage
(`Android/data/com.plusorminustwo.postmark/files/backups/`), which stays on
the device and is removed if you uninstall the app.

You may instead choose your own backup destination — any folder accessible
through Android's Storage Access Framework (SAF), including a
device-connected cloud-sync folder (e.g., a phone's Google Drive/Dropbox
sync folder) if you've set one up yourself. **Postmark does not integrate
with or upload to any cloud service directly** — if a backup ends up in the
cloud, it is because you pointed Android's own file picker at a folder that
your phone happens to sync there, the same as saving any other file to that
folder would. Postmark has no code path that talks to Google Drive, Dropbox,
iCloud, or any other cloud API.

Backup files are currently **unencrypted** (plain zip). Anyone with access
to the backup file — the phone's storage, a USB connection, or a synced
cloud folder — can read the message history it contains.
**[OWNER CONFIRM]**: whether to mention encryption-on-the-roadmap; per
`docs/OWNER-ACTIONS.md` this is an open, undecided design item, not
something to promise a timeline for here.

---

## 4. Permissions Postmark requests, and why

Every permission below is declared in `AndroidManifest.xml` as of this
draft; nothing is requested "just in case."

| Permission | Plain-English reason |
|---|---|
| Receive SMS, Read SMS, Write SMS, Receive MMS, Send SMS | Postmark is a full SMS/MMS app — to read your conversation history, receive new texts, and let you send replies, it must be able to do everything a default SMS app does. Most of these only work at all once you set Postmark as your device's default SMS app (Android requires this — it's not a Postmark choice). |
| Read contacts | To show "Mom" instead of a raw phone number in your conversation list |
| Record audio | To record voice memos you choose to attach to a message; only requested the first time you tap the mic button, and denying it never crashes the app — you just can't record memos |
| Post notifications (Android 13+) | To alert you to new messages |
| Write external storage (only requested on Android 8.0–9.0; not used at all on newer Android) | Saving an MMS photo to your phone's public Pictures folder on very old Android versions that require it |
| Receive boot completed | So scheduled backups and message sync still work correctly after you restart your phone |
| Foreground service, Foreground service (data sync) | Lets Android show a visible, honest "Postmark is syncing" notification while it imports your SMS history in the background, instead of doing that work invisibly |
| Access network state | Two narrow uses, neither of which sends your data anywhere: (1) a "Wi-Fi only" option for scheduled backups checks whether you're on Wi-Fi before running; (2) if a text fails to send because you have no signal, Postmark waits for the phone to report connectivity again before retrying. This permission only lets Postmark *ask the OS whether a connection exists* — it does not grant the ability to use one. |

**Why an SMS app needs SMS permissions (Play's SMS & Call Log Declaration):**
Postmark's entire purpose is to be a default SMS/MMS client — reading,
displaying, sending, and organizing text messages. The default-SMS-handler
role and its associated permissions (`READ_SMS`, `RECEIVE_SMS`, `SEND_SMS`,
`WRITE_SMS`, `RECEIVE_MMS`) are not incidental to a feature; they are the
core functionality the app exists to provide, exactly as Google's messaging
app policy anticipates for an app in the Messaging category.
**[OWNER CONFIRM]**: the actual Play Console "Core functionality"
declaration text and category selection — that's a console form, not
something this file can fill in for you.

---

## 5. Data retention and deletion

- Messages stay in Postmark's local database for as long as they exist in
  Android's own SMS/MMS provider and you haven't deleted them.
- Deleting a conversation in Postmark is a real, permanent delete — it
  removes the conversation from Android's system SMS/MMS store, not just
  from Postmark's own view of it (matching what any default SMS app's
  delete does). This only happens as the direct result of you tapping
  delete and confirming a dialog; Postmark never deletes messages during
  sync, import, or any background process.
- Uninstalling Postmark removes its local database and app-private backup
  files. It does **not** remove your messages from Android's own SMS/MMS
  store (that's the OS's data, not Postmark's) unless you deleted them
  through Postmark first, and it does not remove backup files you saved to
  a folder outside app-private storage (that's your file, in your chosen
  location).
- There is nothing for Postmark to delete on a server, because there is no
  server.

## 6. Your rights

Because all data stays on your device and under your control:

- **Access:** everything Postmark has is already visible to you, in the
  app, at all times.
- **Export:** the Export feature lets you copy or save any conversation,
  date range, or your full history at any time, to a file or the clipboard,
  under your control.
- **Deletion:** delete any conversation directly in the app (see §5), or
  uninstall the app to remove Postmark's local copy entirely.
- **Portability:** backup files are your own zip files, on your own
  storage, readable independent of Postmark.

There is no account to close, no data-deletion request to file with us,
because we — the developer — never receive a copy of your data in the
first place.

## 7. Children's privacy

Postmark is a general-purpose SMS/MMS replacement app, not directed at
children. **[OWNER CONFIRM]**: the age rating / target-audience answers for
the Play Console content-rating questionnaire; that's a policy decision,
not a code fact.

## 8. Changes to this policy

If this policy changes, the "Effective date" above will be updated.
**[OWNER CONFIRM]**: whether you want a changelog of policy changes at the
hosted URL, and how you'll notify existing users (Play Store listing update
is the minimum; an in-app notice is a further product decision).

## 9. Contact

Questions about this policy or Postmark's data handling:
**chrisjmendoza@gmail.com** (placeholder — **[OWNER CONFIRM]** the address
you want listed publicly on a hosted privacy policy page).

---

*Drafted 2026-07-24 against commit `a424059`. Not yet hosted anywhere public.
Play Console requires a live URL for this content before the store listing
can be saved — see `docs/OWNER-ACTIONS.md` item 3 for the full Play Store
approval checklist this feeds into.*
