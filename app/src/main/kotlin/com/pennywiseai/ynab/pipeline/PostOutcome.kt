package com.pennywiseai.ynab.pipeline

/**
 * The classified result of POSTing to YNAB (design spec, Error handling).
 * - Posted: 2xx — every element accepted (created OR duplicate; a duplicate
 *   import_id is a successful post, ADR-0005).
 * - Unauthorized: 401 — the token is invalid; the pipeline pauses posting.
 * - RouteBroken: 404 — the budget/account is gone; the pipeline marks the rule
 *   broken (fail-fast) and records this row terminal.
 * - Failed(retryable=true): offline / timeout / 429 / 5xx — self-resolving, the
 *   caller schedules a background retry (Plan 5).
 * - Failed(retryable=false): 400 (our bug) / other 4xx — needs human action, no
 *   auto-retry. `error` is a short, PAT-free detail string.
 */
sealed interface PostOutcome {
    data object Posted : PostOutcome
    data object Unauthorized : PostOutcome
    data object RouteBroken : PostOutcome
    data class Failed(val retryable: Boolean, val error: String) : PostOutcome
}
