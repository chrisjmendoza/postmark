package com.plusorminustwo.postmark.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Opens [url] in whatever app handles [Intent.ACTION_VIEW] for it (normally the
 * system browser). Silently does nothing on a headless/browserless device (some
 * CI emulators, stripped-down ROMs) instead of crashing — there is no reasonable
 * in-app fallback for "no browser installed".
 *
 * NOTE: this is also defined on the unmerged `feat/about-licenses` branch (PR #30),
 * added there first for the Settings › Licenses screen. Same signature and behavior;
 * if both branches merge, this is a trivial duplicate-file conflict — keep either copy.
 */
fun Context.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        // No app can handle it — nothing sensible to do.
    }
}
