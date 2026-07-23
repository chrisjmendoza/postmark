package com.plusorminustwo.postmark.domain.selection

/** The two directions a conversation-row swipe can complete toward. `Settled` (no swipe
 *  in progress) never reaches [resolveSwipeAction], so it isn't modeled here — callers
 *  filter it out before asking what action a completed swipe performs. */
enum class SwipeDirection { StartToEnd, EndToStart }

/** The action a completed conversation-row swipe performs. */
enum class SwipeAction { Delete, MarkRead, MarkUnread }

/**
 * Decides what a completed swipe on a conversation row (ConversationsScreen) does.
 *
 * Swiping EndToStart (right-to-left) always deletes the conversation. Swiping StartToEnd
 * (left-to-right) toggles the thread's read state: an unread thread is marked read, and a
 * read thread is marked unread — so the same gesture direction serves as a single toggle
 * regardless of the thread's current state.
 *
 * @param isRead whether the thread is currently fully read (no unread messages).
 */
fun resolveSwipeAction(direction: SwipeDirection, isRead: Boolean): SwipeAction = when (direction) {
    SwipeDirection.EndToStart -> SwipeAction.Delete
    SwipeDirection.StartToEnd -> if (isRead) SwipeAction.MarkUnread else SwipeAction.MarkRead
}
