# Owner Actions Needed

Three items from `docs/fable-analysis.md`'s 🟡 tier are blocked on decisions or
assets only the project owner can provide. Everything else in that tier is done
(see CHANGELOG 2026-07-12). Last updated: August 10, 2026 (audited against code;
item 2 corrected — CI ships minified `assembleStaging`, not debug builds).

---

## 1. Backup encryption (#13) — needs a design decision

**Current state:** backups/exports are plaintext zips. Anyone who obtains the file
(USB access, the SAF backup folder on shared storage, a cloud folder you point it
at) can read your entire message history. The v2 manifest already reserves an
`encryption` field, so adding this later needs **no format break**.

**The decision:** what kind of key?

| Option | Pro | Con |
|---|---|---|
| **Android Keystore-bound key** | Zero UX — no passphrase to remember | The key dies with the app install. Backups become unreadable after uninstall or on a new phone — **which defeats the entire point of restore**. Not viable given the SAF survive-uninstall feature. |
| **User passphrase → derived key** (recommended) | Backups restore anywhere the passphrase is known; coherent with "your data, owned by you" | A forgotten passphrase means a permanently unreadable backup. Needs honest UX: set/confirm passphrase in Backup settings, passphrase prompt on restore, and a very clear "we cannot recover this" warning. |
| **Stay plaintext, document it** | No work | The privacy-marketed app writes its users' full SMS history in cleartext to removable storage. |

**Recommendation:** passphrase-derived AES-GCM (PBKDF2 or Argon2), **optional** —
off by default, one toggle in Backup settings. Scheduled backups, manual backups,
and selective exports all honor it; restore prompts for the passphrase when the
manifest says `encryption != "none"`.

**What to decide:** (a) go / no-go on passphrase encryption, (b) opt-in or forced,
(c) whether selective exports should also be encryptable or always follow the
backup setting.

---

## 2. Release-signed tester builds (#14) — needs a keystore + repo secrets

**Current state:** every build testers install is `assembleStaging` (CI's
`distribute.yml` runs `./gradlew assembleStaging`) — minified and non-debuggable
like release, but signed with the **publicly committed** `debug.keystore` (password
"android") so updates install over the previous build without an uninstall. That
means: anyone with the repo can extract the private key and sign a trojan "update"
that installs over the real app on any tester's device. The `release` build type
has **no signing config at all**.

**What only you can do** (the private key must never enter the repo or a chat):

```bash
# 1. Generate a release keystore — store it somewhere safe (password manager);
#    losing it means testers must uninstall/reinstall on the next key rotation.
keytool -genkeypair -v -keystore postmark-release.jks -alias postmark \
        -keyalg RSA -keysize 4096 -validity 10000

# 2. Add it to the repo secrets (base64 for the file itself):
base64 -w0 postmark-release.jks | gh secret set RELEASE_KEYSTORE_B64
gh secret set RELEASE_KEYSTORE_PASSWORD   # prompts for value
gh secret set RELEASE_KEY_ALIAS --body postmark
gh secret set RELEASE_KEY_PASSWORD        # prompts for value

# 3. While you're in there — CI uploads have been failing since ~July 6:
gh secret set FIREBASE_SERVICE_ACCOUNT < path/to/new-service-account.json
```

**Then ask Claude to** (30-minute change, ready to go once secrets exist): add a
`signingConfigs.release` block reading those env vars, point the `release` build
type at it, and switch `distribute.yml` to decode the keystore and run
`assembleRelease`. Decide at the same time whether to enable `minifyEnabled`
(smaller/faster APK, but first release build needs a ProGuard-rules shakedown on
device).

---

## 3. Play Store approval workstream (#20) — external latency, start now

**Why now:** Postmark requests the exact permission set (`READ_SMS`, `RECEIVE_SMS`,
`SEND_SMS`, `READ_CONTACTS`, default-SMS-handler role) that triggers Google's
**SMS & Call Log Permissions Declaration** and maximum review scrutiny. This
review's latency is external — weeks, sometimes with rejection/appeal cycles —
and nothing else on the roadmap can shorten it. It should run in parallel with
feature work, not after it.

**Checklist, in order:**

1. **Privacy policy at a public URL** — required before the store listing can even
   be saved. Content must cover: what data the app accesses (SMS/MMS, contacts),
   that nothing leaves the device (no INTERNET permission — a genuinely strong
   claim), local backups, and data deletion (uninstall). Claude can draft this;
   you need to host it (GitHub Pages is fine) under a domain you control.
2. **Play Console: SMS & Call Log Declaration Form** — declare "Default SMS
   handler" as the core functionality; every requested permission must map to a
   user-visible feature. Blocking/spam being real (block ✅, spam folder ✅ — both
   shipped, see TODO.md; on-device verification pass still pending) matters here:
   the messaging-category guidelines expect it.
3. **Content rating questionnaire** — messaging app with user-generated content.
4. **Data safety form** — should be short and flattering: no collection, no
   sharing, no INTERNET permission.
5. **Store assets** — icon (the trademark question below gates this), 8 screenshots,
   feature graphic, short + long descriptions.
6. **Closed beta across 5–10 devices/carriers before any open launch** — the entire
   fix history is tuned to one Samsung device; MMS is notoriously per-carrier.

**Blocking decision on the side:** the **"Postmark" name collides with the
established Postmark email-delivery service** (ActiveCampaign's transactional
email product). Resolve before investing in branding/icon/store assets — a store
listing is exactly where a trademark complaint lands.

---

*When any of these are decided/provisioned, point Claude at this file and say
which item to execute.*
