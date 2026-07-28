package com.pennywiseai.ynab.pipeline

import com.pennywiseai.ynab.data.local.MessageStatus

/**
 * What TransactionPipeline.process(...) reports to its caller (the capture layer,
 * Plan 5). The pipeline has already recorded the log row; this drives what the
 * caller does next:
 * - Dropped: parser returned null — nothing logged, nothing to do.
 * - Skipped: logged as a SKIPPED_* status (incl. a broken route); no retry.
 * - Posted: logged POSTED (incl. a locally-known duplicate); done.
 * - Failed: logged FAILED. retryable=true → the caller schedules a background
 *   retry (Plan 5); retryable=false → terminal / awaiting token, manual retry only.
 */
sealed interface PipelineResult {
    data object Dropped : PipelineResult
    data class Skipped(val status: MessageStatus) : PipelineResult
    data object Posted : PipelineResult
    data class Failed(val retryable: Boolean) : PipelineResult
}
