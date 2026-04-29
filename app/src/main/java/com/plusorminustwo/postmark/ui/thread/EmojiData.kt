package com.plusorminustwo.postmark.ui.thread

internal data class EmojiSection(val name: String, val emojis: List<Pair<String, String>>)

internal val ALL_EMOJI_SECTIONS = listOf(
    EmojiSection("Smileys", listOf(
        "😀" to "grinning smile happy",
        "😂" to "joy laugh tears funny",
        "🥹" to "holding back tears touched",
        "😍" to "heart eyes love adore",
        "🤩" to "star struck excited amazing",
        "😎" to "cool sunglasses chill",
        "🥳" to "party celebrate birthday",
        "😅" to "sweat smile nervous awkward",
        "😭" to "crying loud sad sob",
        "🙄" to "eye roll annoyed bored",
        "😤" to "huffing triumph steam",
        "🤔" to "thinking pensive hmm"
    )),
    EmojiSection("Hands", listOf(
        "👍" to "thumbs up like good approve",
        "👎" to "thumbs down dislike bad",
        "👏" to "clapping hands applause",
        "🙌" to "raising hands celebration hooray",
        "🤝" to "handshake deal agreement",
        "🫶" to "heart hands love",
        "❤️" to "red heart love",
        "💪" to "muscle strong flex bicep",
        "🤞" to "crossed fingers hope luck",
        "✌️" to "victory peace",
        "🫡" to "saluting face respect",
        "👋" to "waving hand hello goodbye"
    )),
    EmojiSection("Objects", listOf(
        "🔥" to "fire hot lit flame",
        "💯" to "hundred perfect score",
        "✅" to "check mark yes done correct",
        "❌" to "cross no wrong",
        "⭐" to "star excellent favorite",
        "🎉" to "party celebration tada confetti",
        "💀" to "skull dead",
        "👀" to "eyes looking watching",
        "💬" to "speech bubble talking chat",
        "‼️" to "double exclamation important",
        "🚀" to "rocket launch fast"
    )),
    EmojiSection("Animals & Nature", listOf(
        "🐐" to "goat",
        "🦁" to "lion king",
        "🐻" to "bear",
        "🦊" to "fox",
        "🐺" to "wolf",
        "🦋" to "butterfly",
        "🌸" to "cherry blossom flower spring",
        "🌊" to "wave ocean water",
        "⚡" to "lightning bolt electric",
        "🌙" to "crescent moon night",
        "☀️" to "sun sunny bright",
        "🍀" to "four leaf clover luck"
    ))
)
