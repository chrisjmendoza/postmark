package com.plusorminustwo.postmark.domain.customization

import java.io.File

/**
 * A built-in chat background: a subtle vertical gradient (or, when both stops are
 * equal, a flat tint) painted behind the message list.
 *
 * Colors are packed ARGB Ints (0xAARRGGBB) — the same representation
 * [ContactPalette] and [com.plusorminustwo.postmark.domain.model.Thread.chatBackgroundId]'s
 * resolved target use. Callers wrap each stop in `Color(...)` and build a
 * `Brush.verticalGradient(...)` at the UI layer.
 *
 * @param darkColorsArgb  Gradient stops (top → bottom) for dark theme. Empty for [ChatBackgrounds.None].
 * @param lightColorsArgb Gradient stops (top → bottom) for light theme. Empty for [ChatBackgrounds.None].
 */
data class ChatBackground(
    val id: String,
    val displayName: String,
    val darkColorsArgb: List<Int>,
    val lightColorsArgb: List<Int>
)

/**
 * Catalog of built-in chat backgrounds (Phase C of user customization). Pure Kotlin,
 * no Android/Compose imports, so it stays plain-JVM testable.
 *
 * Phase FB recalibration: v1's stops were calibrated to stay within ~8% luminance of
 * the theme background (#1C1C1E dark / #F2F2F7 light, see ui/theme/Theme.kt) — on
 * device (S24 Ultra, dark theme, Dark Mauve) that read as "barely noticeable" next to
 * the ~0.008-luminance OLED background.
 *
 * Phase FB2 re-recalibration: Phase FB over-corrected — its dark stops at ~0.04–0.09
 * relative luminance read as a "loud" wall of color on OLED (owner, S24 Ultra, dark
 * theme, Dark Mauve: a bright magenta background). The middle ground: dark-variant
 * stops now sit at ~0.02–0.04 relative luminance (top brighter, bottom darker, ~0.019
 * luminance delta so each still reads as a gradient). The retarget dropped HSV *value*
 * substantially while holding hue and slightly raising saturation, so the result is a
 * DEEP colored wallpaper — darker, not grayer. Light-variant stops are unchanged from
 * Phase FB (clear pastels at ~0.61–0.80, unverified on-device). IDs and display names
 * are unchanged from v1 — they're already persisted per-thread on-device. See
 * [ChatBackgroundsTest][com.plusorminustwo.postmark.domain.customization.ChatBackgroundsTest]
 * for the luminance-band + gradient-delta guards.
 */
object ChatBackgrounds {

    /** No background — the thread's plain theme background shows through. */
    val None = ChatBackground(
        id = "none",
        displayName = "None",
        darkColorsArgb = emptyList(),
        lightColorsArgb = emptyList()
    )

    private val DeepNavy = ChatBackground(
        id = "deep_navy",
        displayName = "Deep Navy",
        darkColorsArgb = listOf(0xFF1B366E.toInt(), 0xFF12274D.toInt()),
        lightColorsArgb = listOf(0xFFE0E8F6.toInt(), 0xFFBFCEED.toInt())
    )

    private val DeepForest = ChatBackground(
        id = "deep_forest",
        displayName = "Deep Forest",
        darkColorsArgb = listOf(0xFF0F4120.toInt(), 0xFF0B2E17.toInt()),
        lightColorsArgb = listOf(0xFFCEEFD9.toInt(), 0xFF99DDAF.toInt())
    )

    private val DeepPlum = ChatBackground(
        id = "deep_plum",
        displayName = "Deep Plum",
        darkColorsArgb = listOf(0xFF561C72.toInt(), 0xFF3D1452.toInt()),
        lightColorsArgb = listOf(0xFFF0E3F6.toInt(), 0xFFDFC5EC.toInt())
    )

    private val WarmCharcoal = ChatBackground(
        id = "warm_charcoal",
        displayName = "Warm Charcoal",
        darkColorsArgb = listOf(0xFF4E3216.toInt(), 0xFF382310.toInt()),
        lightColorsArgb = listOf(0xFFF1E5DA.toInt(), 0xFFE2C9B1.toInt())
    )

    private val MidnightTeal = ChatBackground(
        id = "midnight_teal",
        displayName = "Midnight Teal",
        darkColorsArgb = listOf(0xFF103F3B.toInt(), 0xFF0B2D2B.toInt()),
        lightColorsArgb = listOf(0xFFCCEEEB.toInt(), 0xFF91DAD4.toInt())
    )

    private val DarkMauve = ChatBackground(
        id = "dark_mauve",
        displayName = "Dark Mauve",
        darkColorsArgb = listOf(0xFF651D41.toInt(), 0xFF48142F.toInt()),
        lightColorsArgb = listOf(0xFFF4E3EB.toInt(), 0xFFE8C4D6.toInt())
    )

    /** [None] plus the 6 curated gradients, in picker display order. */
    val all: List<ChatBackground> = listOf(
        None, DeepNavy, DeepForest, DeepPlum, WarmCharcoal, MidnightTeal, DarkMauve
    )

    private val byId: Map<String, ChatBackground> = all.associateBy { it.id }

    /**
     * Resolves [id] to its BUILT-IN catalog entry. Null (nothing set) or an unknown id
     * (a stale id from a since-removed catalog entry, or corrupt data) both fall back to
     * [None] — this never throws.
     *
     * Contract: this only serves the built-in catalog. A custom `image:<fileName>` id
     * (see [isImageId] / [makeImageId]) is NOT a catalog entry, so `resolve(imageId)`
     * returns [None]. Callers that support image backgrounds must branch on [isImageId]
     * FIRST and render the file themselves; only fall through to [resolve] for non-image ids.
     */
    fun resolve(id: String?): ChatBackground = id?.let { byId[it] } ?: None

    // ── Custom image id codec (Phase J) ──────────────────────────────────────────
    // Custom image backgrounds reuse the same String id column/pref as the built-in
    // catalog, tagged with an "image:" prefix. The file itself lives in
    // filesDir/chat_backgrounds/ (see ChatBackgroundImageStore); only the id is
    // persisted per-thread / in the global pref (and in backups — the bytes are not,
    // so a restored image id whose file is absent falls back to no background).

    /** Prefix marking a chat-background id as a custom image reference. */
    const val IMAGE_ID_PREFIX = "image:"

    /** Label shown for a custom-image background in rows/tiles/subtitles. */
    const val CUSTOM_IMAGE_LABEL = "Custom image"

    /** True when [id] is a custom-image id (as produced by [makeImageId]). */
    fun isImageId(id: String?): Boolean = id != null && id.startsWith(IMAGE_ID_PREFIX)

    /**
     * The stored file name inside filesDir/chat_backgrounds/ for a custom-image [id],
     * or null when [id] is not a well-formed `image:<fileName>` — the file name must be
     * non-empty and must not contain a path separator or `.`/`..` traversal segment.
     * This is the single guard against a corrupt id escaping the backgrounds directory.
     */
    fun imageFileName(id: String): String? {
        if (!id.startsWith(IMAGE_ID_PREFIX)) return null
        val fileName = id.substring(IMAGE_ID_PREFIX.length)
        if (fileName.isEmpty()) return null
        if (fileName == "." || fileName == "..") return null
        if (fileName.any { it == '/' || it == '\\' }) return null
        return fileName
    }

    /** Builds a custom-image id from a bare [fileName] (the inverse of [imageFileName]). */
    fun makeImageId(fileName: String): String = IMAGE_ID_PREFIX + fileName

    /**
     * Resolves a custom-image [id] to its backing [File] via [fileFor], or null when [id]
     * is null, isn't an image id, or its file is missing ([fileFor] returns null).
     * Collapses the repeated `id?.takeIf { isImageId(it) }?.let { fileFor(it) }` guard at
     * the UI/ViewModel boundary. `java.io.File` is JVM (not Android), so it's fine in this
     * pure-domain object.
     */
    fun resolveImageFile(id: String?, fileFor: (String) -> File?): File? =
        id?.takeIf(::isImageId)?.let(fileFor)

    /**
     * Collapses [None]'s id to null — the global chat-background preference's own
     * "unset" representation — so only one value ever means "no background" at that
     * layer. Any other id (including null) passes through unchanged.
     */
    fun toGlobalPreferenceId(id: String?): String? = id?.takeIf { it != None.id }

    /**
     * Inverse of [toGlobalPreferenceId]: the global preference's null ("unset")
     * normalizes to [None]'s id, so a picker with no "Default" option shows the None
     * tile as selected rather than nothing selected.
     */
    fun fromGlobalPreferenceId(stored: String?): String = stored ?: None.id
}
