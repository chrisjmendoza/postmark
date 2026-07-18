package com.plusorminustwo.postmark.domain.customization

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
 * Every non-[None] entry is calibrated to stay close in luminance to the app's theme
 * background (#1C1C1E dark / #F2F2F7 light, see ui/theme/Theme.kt) — subtle enough that
 * bubbles (#2C2C2E received / accent containers) stay clearly distinguishable on top.
 * See [ChatBackgroundsTest][com.plusorminustwo.postmark.domain.customization.ChatBackgroundsTest]
 * for the luminance guard.
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
        darkColorsArgb = listOf(0xFF1A1E2C.toInt(), 0xFF14161F.toInt()),
        lightColorsArgb = listOf(0xFFECF0FA.toInt(), 0xFFE4EAF5.toInt())
    )

    private val DeepForest = ChatBackground(
        id = "deep_forest",
        displayName = "Deep Forest",
        darkColorsArgb = listOf(0xFF182420.toInt(), 0xFF121C18.toInt()),
        lightColorsArgb = listOf(0xFFEBF4EE.toInt(), 0xFFE1EFE6.toInt())
    )

    private val DeepPlum = ChatBackground(
        id = "deep_plum",
        displayName = "Deep Plum",
        darkColorsArgb = listOf(0xFF221A2A.toInt(), 0xFF1A1420.toInt()),
        lightColorsArgb = listOf(0xFFF2EAF7.toInt(), 0xFFF0E7F7.toInt())
    )

    private val WarmCharcoal = ChatBackground(
        id = "warm_charcoal",
        displayName = "Warm Charcoal",
        darkColorsArgb = listOf(0xFF221E1A.toInt(), 0xFF1A1714.toInt()),
        lightColorsArgb = listOf(0xFFF6F1E9.toInt(), 0xFFF0E8DC.toInt())
    )

    private val MidnightTeal = ChatBackground(
        id = "midnight_teal",
        displayName = "Midnight Teal",
        darkColorsArgb = listOf(0xFF14222A.toInt(), 0xFF0E1A20.toInt()),
        lightColorsArgb = listOf(0xFFE7F3F5.toInt(), 0xFFDCEDF0.toInt())
    )

    private val DarkMauve = ChatBackground(
        id = "dark_mauve",
        displayName = "Dark Mauve",
        darkColorsArgb = listOf(0xFF241A20.toInt(), 0xFF1C1418.toInt()),
        lightColorsArgb = listOf(0xFFF7EAEF.toInt(), 0xFFF4E7EC.toInt())
    )

    /** [None] plus the 6 curated gradients, in picker display order. */
    val all: List<ChatBackground> = listOf(
        None, DeepNavy, DeepForest, DeepPlum, WarmCharcoal, MidnightTeal, DarkMauve
    )

    private val byId: Map<String, ChatBackground> = all.associateBy { it.id }

    /**
     * Resolves [id] to its catalog entry. Null (nothing set) or an unknown id (a stale
     * id from a since-removed catalog entry, or corrupt data) both fall back to [None]
     * — this never throws.
     */
    fun resolve(id: String?): ChatBackground = id?.let { byId[it] } ?: None

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
