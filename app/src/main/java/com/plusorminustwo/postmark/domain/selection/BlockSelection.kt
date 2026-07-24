package com.plusorminustwo.postmark.domain.selection

/**
 * One selected thread's shape relevant to the conversation-list bulk "Block" action: the
 * address that would be written to the system [android.provider.BlockedNumberContract]
 * provider, and whether the thread is a group thread (more than one participant).
 */
data class BlockCandidate(val address: String, val isGroup: Boolean)

/**
 * Result of splitting a bulk-block selection into addresses to actually block vs. group
 * threads skipped because there's no single number to block.
 */
data class BlockPartition(
    val addressesToBlock: List<String>,
    val skippedGroupCount: Int
)

/**
 * Pure partition for the bulk "Block" action: every non-group candidate's address is kept
 * (selection order preserved); every group candidate is counted as skipped instead of
 * blocked — a group thread has no single number to block system-wide, the same reason
 * "Block number" is hidden in the thread ⋮ menu for group threads.
 */
fun partitionForBlock(candidates: List<BlockCandidate>): BlockPartition {
    val (groups, singles) = candidates.partition { it.isGroup }
    return BlockPartition(
        addressesToBlock = singles.map { it.address },
        skippedGroupCount = groups.size
    )
}

/**
 * Human-readable Snackbar summary for a bulk-block result, e.g. "Blocked 2" or
 * "Blocked 2 · skipped 1 group chat" / "Blocked 2 · skipped 3 group chats".
 */
fun blockResultMessage(blockedCount: Int, skippedGroupCount: Int): String {
    val blocked = "Blocked $blockedCount"
    if (skippedGroupCount == 0) return blocked
    val chats = if (skippedGroupCount == 1) "group chat" else "group chats"
    return "$blocked · skipped $skippedGroupCount $chats"
}
