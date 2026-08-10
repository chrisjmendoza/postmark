package com.plusorminustwo.postmark.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.plusorminustwo.postmark.domain.customization.FontFamilyPreference
import com.plusorminustwo.postmark.ui.theme.toFontFamilyOrNull

/**
 * Picker for the app-wide font family. Every option renders its own name IN the typeface it
 * selects, so the list is the preview — with nine options a plain radio list would name
 * fonts the user has no way to picture, and applying each one just to look is a poor trade.
 *
 * A tap applies immediately (like the radio rows this replaced) and closes the dialog, so
 * the whole app re-renders in the chosen face with no extra confirm step.
 *
 * @param current  The currently applied preference — its row shows selected.
 * @param onSelect Called with the chosen preference. The host persists it.
 * @param onDismiss Called when the dialog is dismissed without a new selection.
 */
@Composable
fun FontFamilyDialog(
    current: FontFamilyPreference,
    onSelect: (FontFamilyPreference) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Font") },
        text = {
            // heightIn caps the dialog on small screens; without it nine rows can push the
            // dismiss button off the bottom edge.
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                FontFamilyPreference.entries.forEach { preference ->
                    FontOptionRow(
                        preference = preference,
                        selected = preference == current,
                        onClick = { onSelect(preference) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

/**
 * One option: a radio button, the typeface's name set in that typeface, and a plain-font
 * description line. The description deliberately stays in the ambient font — it's UI chrome
 * describing the option, and setting it in the preview face too made the row read as a
 * sample block rather than a choice.
 */
@Composable
private fun FontOptionRow(
    preference: FontFamilyPreference,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = preference.displayName,
                // SYSTEM maps to null (no override) — preview it in the same platform
                // default the option itself applies.
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = preference.toFontFamilyOrNull() ?: FontFamily.Default
                )
            )
            Text(
                text = preference.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
