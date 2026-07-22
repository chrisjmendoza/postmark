package com.plusorminustwo.postmark.domain.customization

import com.plusorminustwo.postmark.domain.backup.encodeJson
import com.plusorminustwo.postmark.domain.backup.parseJson

/**
 * Serializes / parses a [ThemePreset] to the `.postmarktheme` share format (v1) — a
 * small, self-contained, hand-editable JSON document (see docs/theme-presets-plan.md).
 * This codec **is** the seed of the future file-based theme "market": the app exports a
 * preset through the system share sheet and imports one via file-open. It never touches
 * the network — Postmark ships with no INTERNET permission, and this keeps it that way.
 *
 * The schema is flat:
 * ```
 * {
 *   "format": "postmark-theme",
 *   "schemaVersion": 1,
 *   "name": "Sunset",
 *   "author": "",
 *   "sent": "#7C3AC9",
 *   "contact": "#F2694B",
 *   "background": "deep_plum"
 * }
 * ```
 * Colors are uppercase "#RRGGBB" hex; a null [ThemePreset.backgroundId] serializes as
 * "none" (the [ChatBackgrounds.None] id).
 *
 * JSON tokenizing reuses the repo's existing pure, JVM-testable codec
 * ([encodeJson]/[parseJson]) rather than `org.json` — which is only an unmocked
 * `android.jar` stub in unit tests (see BackupJson.kt) — or a second hand-rolled parser.
 * This file owns only the schema: the format tag, the version gate, hex/id mapping, and
 * tolerant field extraction.
 *
 * Contract (mirrors the design doc):
 *  - unknown keys are ignored (forward compatibility);
 *  - a `schemaVersion` other than [SCHEMA_VERSION] → null (politely refuse a newer format);
 *  - any malformed input → null; [parse] NEVER throws;
 *  - round-trip is lossless (the preset id is re-derived from the name as kebab-case,
 *    matching how the built-in catalog assigns ids);
 *  - names with quotes / backslashes / emoji survive the round-trip (JSON-escaped).
 */
object ThemePresetCodec {

    /** File extension (no dot) for a shared theme document. */
    const val FILE_EXTENSION = "postmarktheme"

    private const val FORMAT_TAG = "postmark-theme"
    private const val SCHEMA_VERSION = 1
    private val WHITESPACE = Regex("\\s+")

    /** Serializes [preset] to the pretty-printed `.postmarktheme` JSON document. */
    fun serialize(preset: ThemePreset): String {
        val fields: List<Pair<String, Any>> = listOf(
            "format" to FORMAT_TAG,
            "schemaVersion" to SCHEMA_VERSION,
            "name" to preset.name,
            "author" to "",
            "sent" to ColorMath.formatHexColor(preset.sentArgb),
            "contact" to ColorMath.formatHexColor(preset.contactArgb),
            "background" to (preset.backgroundId ?: ChatBackgrounds.None.id)
        )
        // Pretty-print by hand (the flat schema is trivial to lay out) but delegate every
        // value's escaping to encodeJson, so quotes/backslashes/emoji are handled correctly.
        return fields.joinToString(separator = ",\n", prefix = "{\n", postfix = "\n}") { (key, value) ->
            "  ${encodeJson(key)}: ${encodeJson(value)}"
        }
    }

    /**
     * Parses a `.postmarktheme` document back into a [ThemePreset], or null when the text
     * is not a valid v1 theme (bad JSON, wrong/missing format tag, unsupported
     * schemaVersion, missing/invalid name or colors). Never throws.
     */
    fun parse(json: String): ThemePreset? {
        val root = runCatching { parseJson(json) }.getOrNull() as? Map<*, *> ?: return null
        if (root["format"] != FORMAT_TAG) return null
        if ((root["schemaVersion"] as? Number)?.toInt() != SCHEMA_VERSION) return null
        val name = root["name"] as? String ?: return null
        val sentArgb = (root["sent"] as? String)?.let(ColorMath::parseHexColor) ?: return null
        val contactArgb = (root["contact"] as? String)?.let(ColorMath::parseHexColor) ?: return null
        // A missing/unknown background id is tolerated: "none" (and any non-string) mean no
        // background, and ChatBackgrounds.resolve() already maps a stale id to None at render.
        val backgroundId = (root["background"] as? String)?.takeIf { it != ChatBackgrounds.None.id }
        return ThemePreset(
            id = kebabCase(name),
            name = name,
            sentArgb = sentArgb,
            contactArgb = contactArgb,
            backgroundId = backgroundId
        )
    }

    /** "Mono Pop" → "mono-pop": trimmed, lowercased, runs of whitespace collapsed to hyphens. */
    private fun kebabCase(name: String): String =
        name.trim().lowercase().replace(WHITESPACE, "-")
}
