package com.plusorminustwo.postmark.ui.thread

enum class SelectionScope {
    /** Tap individual message bubbles to toggle selection. */
    MESSAGES,
    /** Tap date headers to toggle all messages for that day. */
    DAY,
    /** All messages in the thread are selected. */
    ALL
}
