package com.plusorminustwo.postmark.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.plusorminustwo.postmark.util.openUrl

/**
 * One row in the open-source licenses list: a library (or font) name, the
 * license it's under, and a URL to the project/font page for the full text.
 */
private data class LicenseEntry(
    val name: String,
    val license: String,
    val url: String
)

// ── Static dependency list ──────────────────────────────────────────────────
// Hand-maintained rather than generated (e.g. via Google's oss-licenses
// plugin) — deliberate: this app has a small, slow-changing dependency set,
// so a build-plugin + runtime dependency to enumerate it is more moving parts
// than the problem needs. Keep in sync with app/build.gradle.kts and
// gradle/libs.versions.toml when dependencies change.
//
// Plain Apache-2.0 AndroidX libraries are grouped into one row rather than
// listed individually; libraries with their own project identity (Room,
// Hilt/Dagger, Coil, Media3, WorkManager, the emoji picker) get their own row.
private val LICENSE_ENTRIES = listOf(
    LicenseEntry(
        name = "Kotlin & kotlinx.coroutines",
        license = "Apache License 2.0",
        url = "https://github.com/JetBrains/kotlin"
    ),
    LicenseEntry(
        name = "Jetpack Compose (UI, Material 3, Material Icons Extended)",
        license = "Apache License 2.0",
        url = "https://developer.android.com/jetpack/compose"
    ),
    LicenseEntry(
        name = "AndroidX libraries (Core KTX, Lifecycle, Activity, Navigation, " +
            "ExifInterface, DocumentFile, Metrics Performance)",
        license = "Apache License 2.0",
        url = "https://developer.android.com/jetpack/androidx"
    ),
    LicenseEntry(
        name = "Room",
        license = "Apache License 2.0",
        url = "https://developer.android.com/training/data-storage/room"
    ),
    LicenseEntry(
        name = "Hilt / Dagger",
        license = "Apache License 2.0",
        url = "https://dagger.dev/hilt/"
    ),
    LicenseEntry(
        name = "WorkManager",
        license = "Apache License 2.0",
        url = "https://developer.android.com/jetpack/androidx/releases/work"
    ),
    LicenseEntry(
        name = "Coil",
        license = "Apache License 2.0",
        url = "https://coil-kt.github.io/coil/"
    ),
    LicenseEntry(
        name = "Media3 / ExoPlayer",
        license = "Apache License 2.0",
        url = "https://github.com/androidx/media"
    ),
    LicenseEntry(
        name = "emoji2-emojipicker",
        license = "Apache License 2.0",
        url = "https://developer.android.com/jetpack/androidx/releases/emoji2"
    ),
    LicenseEntry(
        name = "Inter",
        license = "SIL Open Font License 1.1",
        url = "https://fonts.google.com/specimen/Inter"
    ),
    LicenseEntry(
        name = "Poppins",
        license = "SIL Open Font License 1.1",
        url = "https://fonts.google.com/specimen/Poppins"
    ),
    LicenseEntry(
        name = "Nunito",
        license = "SIL Open Font License 1.1",
        url = "https://fonts.google.com/specimen/Nunito"
    ),
    LicenseEntry(
        name = "Lora",
        license = "SIL Open Font License 1.1",
        url = "https://fonts.google.com/specimen/Lora"
    ),
    LicenseEntry(
        name = "Playfair Display",
        license = "SIL Open Font License 1.1",
        url = "https://fonts.google.com/specimen/Playfair+Display"
    ),
    LicenseEntry(
        name = "JetBrains Mono",
        license = "SIL Open Font License 1.1",
        url = "https://fonts.google.com/specimen/JetBrains+Mono"
    ),
)

/**
 * Open-source licenses list — Settings → About → "Open-source licenses".
 *
 * A hand-maintained static list rather than a generated one (see the comment
 * above [LICENSE_ENTRIES]): no new build-plugin or runtime dependency for what
 * is a short, slow-changing list. Rows link out to the project/font page for
 * the full license text instead of bundling it into the APK.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Open-source licenses") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // Scaffold padding carries the status-bar (top, below the app bar) and
            // nav-bar (bottom) insets; as contentPadding the last row scrolls clear
            // of the nav bar instead of hiding behind it.
            contentPadding = padding
        ) {
            items(LICENSE_ENTRIES, key = { it.name }) { entry ->
                LicenseRow(
                    entry = entry,
                    onClick = { context.openUrl(entry.url) }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun LicenseRow(entry: LicenseEntry, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(entry.name, style = MaterialTheme.typography.bodyLarge)
        Text(
            entry.license,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
