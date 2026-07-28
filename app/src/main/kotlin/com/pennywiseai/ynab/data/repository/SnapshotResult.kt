package com.pennywiseai.ynab.data.repository

import com.pennywiseai.ynab.core.model.MappingRule

/**
 * Outcome of a token save / snapshot refresh. Unauthorized is kept distinct from
 * Error because a 401 means "no valid token" (the pipeline pauses posting; the UI
 * prompts for a new PAT), whereas Error is a transient/offline failure the user
 * can retry. `brokenRules` (populated in Task 5) are existing routes whose target
 * budget/account no longer exists in the fresh snapshot — surfaced to the user,
 * not treated as a posting failure.
 */
sealed interface SnapshotResult {
    data class Success(
        val budgetCount: Int,
        val accountCount: Int,
        val brokenRules: List<MappingRule>,
    ) : SnapshotResult

    data object Unauthorized : SnapshotResult

    data class Error(val message: String) : SnapshotResult
}
