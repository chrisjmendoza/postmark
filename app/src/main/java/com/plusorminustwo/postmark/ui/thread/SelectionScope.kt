package com.plusorminustwo.postmark.ui.thread

/** Defines the granularity of message selection in the thread view. */
enum class SelectionScope {
    /** Tap individual message bubbles or date headers to toggle selection. */
    MESSAGES,
    /** All messages in the thread are selected. */
    ALL
}
